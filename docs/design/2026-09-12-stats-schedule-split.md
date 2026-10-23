# 통계 매시 회차 분리 — 리뷰 :30, 북마크 델타 :15

2026-09-12 · 이슈 #404 · main 구현만. 테스트 작성·수정·실행과 빌드는 후속 작업자가 맡는다.

## 1. 무엇이 갈렸나

2026-08-17부터 매시 :30 회차 하나가 두 축을 순차로 돌았다. `PlaceStatsFacade.recalculatePlaceCounts()`
한 메서드 안에서 기준 시각을 한 번 잡고, 리뷰 축 전량 재계산과 북마크 델타 소비를 차례로 불렀다.
그 한 메서드가 두 메서드가 됐다.

| | 분리 전 | 분리 후 |
|---|---|---|
| 리뷰 축 (`review_count`·`avg_rating`) | :30, 통합 회차의 앞 절반 | **:30, 독립 회차** |
| 북마크 축 (`bookmark_count`) | :30, 통합 회차의 뒤 절반 | **:15, 독립 회차** |
| 기준 시각 | 두 축이 하나를 공유 | 회차마다 각자 `LocalDateTime.now()` |
| 락 | 두 축이 `place-stats-count` 하나 | 회차마다 자기 이름 |
| 재시도 설정 | 두 축이 `batch-*` 공용 | 회차마다 자기 키 |

점수(01:00)·안전망(01:45)·스냅샷(10분 `fixedDelay`)·기동 백필은 건드리지 않았다.

## 2. 왜 갈랐나 — 성능 때문이 아니다

**분리가 DB가 하는 일을 줄이지 않는다.** 두 축은 갈리기 전에도 각자 트랜잭션이었고
(`PlaceStatsBatchProcessor`·`BookmarkCountDeltaProcessor` 둘 다 READ COMMITTED),
SET 목록이 겹치지 않는 것이 명시된 계약이라(`PlaceStatsRepository`) 서로를 기다리지도 않았다.
같은 문장을 같은 횟수로 돌리므로 총량이 같다. **이 변경을 성능 개선으로 적지 말 것.**

실제로 달라지는 것은 둘이다.

1. **두 축의 주기를 따로 잡을 자유.** 리뷰 축의 신선도 요구(전량 재계산, 비용이 원본 행 수에 붙는다)와
   북마크 축의 신선도 요구(델타 소비, 비용이 지난 회차 이후 토글 수에 붙는다)는 원래 다른 축인데
   한 cron에 묶여 있었다. **지금 당장 이 자유를 쓰지는 않는다** — 둘 다 매시 1회를 유지한다.
2. **한 축의 재시도 대기가 다른 축의 시작을 매번 밀지 않는다.** 합쳐져 있을 때 리뷰 축이 3회 실패하면
   델타 소비의 시작이 대기 2회 × 5초만큼 뒤로 밀렸다.

**2번을 "지연이 전파되지 않는다"로 확대하면 틀린다.** 8장을 본다.

## 3. 실제 이름 — 메서드·cron 키·락·로그

| 회차 | 메서드 | cron 키 (기본값) | ShedLock 이름 | 시작 로그 |
|---|---|---|---|---|
| 리뷰 카운트 | `PlaceStatsFacade#recalculateReviewCounts()` | `solply.place-stats.count-cron` (`0 30 * * * *`) | `place-stats-count` | `인기순 리뷰 카운트 배치 시작` |
| 북마크 델타 | `PlaceStatsFacade#consumeBookmarkCountDeltas()` | `solply.place-stats.bookmark-delta-cron` (`0 15 * * * *`) | `place-stats-bookmark-delta` | `북마크 카운트 델타 배치 시작` |
| 카운트 안전망 | `PlaceStatsFacade#recalculatePlaceCountsSafety()` (그대로) | `solply.place-stats.count-safety-cron` (`0 45 1 * * *`) | `place-stats-count-safety` | `인기순 카운트 안전망 배치 시작` |
| 인기 점수 | `PlaceStatsFacade#recalculatePopularScores()` (그대로) | `solply.place-stats.score-cron` (`0 0 1 * * *`) | `place-stats-score` | `인기점수 배치 시작` |

네 회차 모두 `zone = "Asia/Seoul"`. 매시 두 축은 `lockAtMostFor=PT10M`, `lockAtLeastFor=PT1M`
(안전망도 같고, 점수만 `PT30M`).

**`recalculatePlaceCounts()`라는 이름은 사라졌다.** 옛 이름을 부르던 코드는 테스트뿐이다
(main 호출처 0건).

### 3-1. 리뷰 축이 옛 키와 옛 락을 물려받은 이유

둘 다 "안 고쳐도 되게" 하려고 물려받았다.

- **cron 키** — 배포 환경마다 yml이 따로 있고 그 파일은 저장소에 없다(`.gitignore`의 `*.yml`).
  키 이름을 바꾸면 각 환경의 yml을 손대기 전까지 옛 키가 조용히 죽은 키가 된다.
- **락 이름** — `shedlock` 테이블의 `place-stats-count` 한 행의 `locked_at`이
  "배치가 마지막으로 돈 시각"을 읽는 지점이다(`PlaceStatsRepository` javadoc,
  `V35__drop_place_stats_count_calculated_at.sql`). 개명하면 그 서술이 낡고,
  이번 범위에서 SQL을 바꾸지 않으므로 옛 이름 행이 영구히 남는다.

북마크 축은 반대로 **이름을 공유하면 안 된다**. 락 이름이 같으면 두 회차가 겹친 시각에 깨어났을 때
한쪽이 통째로 건너뛰어진다. `shedlock` 행은 ShedLock이 첫 발화에 `INSERT`로 만들므로
(V27) **새 락을 위한 마이그레이션은 필요 없다.**

### 3-2. 성공·실패·소요 로그

**시도별 로그**는 네 회차 모두 `runWithRetry` 한 곳에서 나온다 — 진입점마다 복사하지 않았다.

- 성공: `{label} 완료 - calculatedAt=…, affectedRows=…, elapsed=…ms, 시도=n/최대`
- 중간 실패: `warn`, `{label} 실패 - {지연}ms 뒤 재시도한다 (시도 n/최대), calculatedAt=…`
- 최종 실패: `error`, `{label} 실패 - {최대}회 시도 모두 실패해 회차를 포기한다, calculatedAt=…`

`label`은 회차마다 다르다 — `인기순 리뷰 카운트 재계산` / `북마크 카운트 델타 소비` /
`인기순 카운트 안전망 배치` / `인기점수 배치`.

**회차 종료 로그**는 매시 두 축에만 있다(`logRoundFinished`). 점수·안전망은 동작을 바꾸지 않으려고
그대로 뒀다.

```
인기순 리뷰 카운트 회차 종료 - 결과=성공, calculatedAt=…, elapsed=…ms
북마크 카운트 델타 회차 종료 - 결과=실패, calculatedAt=…, elapsed=…ms
```

**시도별 `elapsed`와 재는 구간이 다르다.** 저쪽은 성공한 시도 하나의 소요라서, 앞선 시도가 실패해
재시도 대기를 거친 회차의 실제 길이가 로그 어디에도 남지 않았다. 종료 줄의 `elapsed`는 실패한
시도와 대기를 모두 포함한 회차 전체이고, **`lockAtMostFor`·회차 간격과 비교할 수 있는 유일한
수치다.** 회차마다 시작 줄과 종료 줄이 짝이므로, 짝 없는 시작 줄은 회차가 끝나지 않았다는 신호다.

실패해도 `info`로 남긴다. 원인과 스택은 `runWithRetry`가 이미 `error`로 냈고, 여기서 한 번 더
올리면 회차 하나가 알림을 두 번 울린다 — 이 줄이 더하는 것은 경보가 아니라 소요 시간이다.

`affectedRows`의 뜻이 두 축에서 정반대라는 것은 분리 전과 같다. 리뷰 축은 문장이 걸린 행 수
(= `place_stats` 행 수), 북마크 축은 이번 회차가 삼킨 전표 수다.

## 4. :15를 고른 근거

제약이 셋이었다.

- **01:00 점수 회차**는 `place_stats` 전 행에 X 락을 커밋까지 든다.
- **01:45 안전망**과 **북마크 델타 소비**는 같은 아웃박스 전표를 `FOR UPDATE`로 잡는 짝이다.
  이 둘이 서로 가장 멀어야 한다.
- **03:00 장소 임베딩 · 04:00 코스 임베딩**과 스케줄러 스레드를 다투지 않도록 정각은 쓰지 않는다.

:15면 01:00(점수)과 15분, :30(리뷰)과 15분, 01:45(안전망)와 **30분**이 떨어진다.
전표를 두고 다투는 짝의 간격이 분리 전 15분에서 30분으로 늘었다.

**15분이 충분하다는 보장은 없다.** 회차 소요는 데이터 양과 그때의 DB 상태에 달렸고, 시도 횟수와
대기를 곱해 나오는 값(기본값이면 10초)은 *대기의 합*이지 회차 소요의 상한이 아니다. 간격이 실제로
지켜지는지는 3-2의 회차 종료 로그 `elapsed`로 본다.

:50 같은 뒤쪽 분은 01:50이 안전망 01:45와 5분밖에 떨어지지 않아 쓰지 않았다.

## 5. 설정 — yml에 넣어야 할 키

**이 작업은 yml을 고치지 않았다.** `application.yml`류의 소유는 인증 작업자에게 있으므로,
넣어야 할 키를 여기 정확히 적어 둔다. 아래 여섯 키는 모두 **없어도 동작한다** —
`@Scheduled` 플레이스홀더와 `PlaceStatsProperties` 필드가 같은 기본값을 들고 있다.

`solply.place-stats` 아래:

| 키 | 기본값 | 비고 |
|---|---|---|
| `bookmark-delta-cron` | `"0 15 * * * *"` | **신규.** 북마크 델타 소비 회차 |
| `review-count-max-attempts` | `3` | **신규.** 리뷰 축 최대 시도 |
| `review-count-retry-delay` | `5s` | **신규.** 리뷰 축 시도 사이 대기 |
| `bookmark-delta-max-attempts` | `3` | **신규.** 북마크 축 최대 시도 |
| `bookmark-delta-retry-delay` | `5s` | **신규.** 북마크 축 시도 사이 대기 |
| `count-cron` | `"0 30 * * * *"` | 기존 키. **덮는 범위가 리뷰 축으로 좁아졌다** |

`count-safety-cron`·`score-cron`·`batch-max-attempts`·`batch-retry-delay`와 점수 파라미터
(`half-life-days` 등)는 그대로다.

권장 yml 블록(저장소의 `application.yml`은 미추적이므로 각 환경에 직접 넣는다):

```yaml
solply:
  place-stats:
    # 2026-09-12: 매시 회차가 리뷰 축(:30)과 북마크 델타(:15) 둘로 갈렸다.
    count-cron: "0 30 * * * *"          # 리뷰 축만 — 북마크 축은 아래 키를 읽는다
    bookmark-delta-cron: "0 15 * * * *"
    review-count-max-attempts: 3
    review-count-retry-delay: 5s
    bookmark-delta-max-attempts: 3
    bookmark-delta-retry-delay: 5s
```

**기본값 리터럴이 두 곳에 있는 것은 구조적으로 강제된 중복이다.** `@ConfigurationProperties`의
필드 기본값은 `@Scheduled` 플레이스홀더 해석 시점에 보이지 않는다. 주기를 바꿀 때
`PlaceStatsFacade`의 `@Scheduled` 리터럴과 `PlaceStatsProperties` 필드를 **함께** 고쳐야 하고,
필드만 고치면 스케줄은 그대로인 방향으로 조용히 갈라진다. 필드를 남겨 두는 이유는 `@NotBlank`가
빈 문자열을 부팅 시점에 잡아 주기 때문이다(cron과 무관해 보이는 예외로 죽는 것보다 낫다).

## 6. 이전 설정의 영향

무설정 환경의 동작은 분리 전과 같다. 신경 쓸 곳은 **값을 조정해 온 환경** 둘이다.

1. **`count-cron`을 기본값과 다르게 둔 환경.** 그 값은 이제 리뷰 축만 움직인다.
   북마크 축은 `bookmark-delta-cron`을 읽고, 그 키가 없으면 매시 :15로 돈다.
   두 축의 주기를 함께 움직이려면 두 키를 함께 고친다.
2. **`batch-max-attempts` 또는 `batch-retry-delay`를 조정해 온 환경.** 그 두 키는 이제
   점수 회차와 안전망 회차만 덮는다. 매시 두 축이 같은 재시도 설정을 유지하려면
   `review-count-*`·`bookmark-delta-*` 네 키에 같은 값을 적어야 한다.
   — 저장소에 남아 있는 `application.yml`에는 이 두 키가 선언돼 있지 않다(확인함).
   선언돼 있지 않은 환경은 아무것도 할 필요가 없다. 다른 환경의 yml은 이 저장소에서 볼 수 없으므로
   배포 전에 각 환경에서 확인해야 한다.

DB 쪽에는 할 일이 없다. **새 마이그레이션 없음** — `shedlock`은 `name`이 PK인 테이블이고
ShedLock이 새 이름의 행을 첫 발화에 만든다.

## 7. 바꾸지 않은 것

분리는 진입점까지다. 아래는 전부 그대로다.

- 집계 SQL과 프로세서 동작 — `recalculateReviewCounts`, `consumeAndApply`,
  `recalculateCountsAndClearOutbox`, `recalculateScores`, 각각의 리포지토리 문장.
- **파사드에 `@Transactional`을 붙이지 않는 계약.** `try/catch`가 트랜잭션 경계 밖에 있어야
  실패한 시도가 온전히 롤백된 뒤 다음 시도가 시작한다. 두 진입점 모두 여기 해당한다.
- **북마크 델타의 `FOR UPDATE` → 카운트 갱신 → 읽은 id만 삭제가 한 트랜잭션**이라는 성질.
  이 축의 재시도가 안전한 근거는 멱등성이 아니라 이것이다 — 실패한 시도는 전표를 그대로 남기고
  통째로 롤백된다. 그래서 이 회차에서 `calculatedAt`은 계산에 쓰이지 않고 로그 표식일 뿐이다.
- 한 회차 안의 모든 시도가 같은 `calculatedAt`을 쓴다는 것. 집계에 `created_at <= :calculatedAt`
  상한이 있어 기준 시각을 시도마다 새로 잡으면 재시도의 결과가 흔들린다.
- 인기 점수(01:00)·안전망(01:45)·스냅샷 리빌딩·기동 백필·스케줄러 스레드 풀.
- 추가하지 않은 것: PENDING/PROCESSING 상태, worker 풀, 배치용 UNIQUE, Idempotency Key,
  리뷰 아웃박스.

`runWithRetry`의 시그니처만 바뀌었다 — 최대 시도와 대기를 프로퍼티에서 직접 읽지 않고 인자로 받는다.
회차마다 설정이 갈린 뒤로, 안에서 읽으면 **어느 회차가 어느 키를 따르는지가 호출부에서 보이지 않는다.**
호출부가 자기 키를 명시하면 설정을 옮길 때 빠뜨린 회차가 그 자리에서 드러난다.

## 8. 한계 — 분리해도 남는 것

**`@Scheduled` 기본 실행기는 단일 스레드다.** 커스텀 `TaskScheduler`도 `SchedulingConfigurer`도
`spring.task.scheduling.*` 설정도 없다. 네 회차와 스냅샷 회차가 한 스레드를 나눠 쓰므로
**앞 회차가 길어지면 뒤 회차의 발화가 그만큼 밀린다.** 회차를 갈랐다고 두 축이 서로를 못 밀게
되는 것이 아니다. 갈라서 얻은 것은 한 축의 재시도 대기가 다른 축의 회차에 **매번** 얹히지 않는다는
것뿐이고, 실행 스레드는 여전히 공유 자원이다. 지금까지 관측된 소요에 비하면 15분 간격이 넉넉하지만,
그것은 여유이지 격리가 아니다.

**`lockAtMostFor`는 무제한 상호배제가 아니다.** 상한(매시 두 축 10분)을 넘겨 돌던 회차는 락이
자동 해제된 뒤에도 계속 돌고, 그 사이 다른 인스턴스가 같은 회차를 시작할 수 있다. 회차가 얼마나
걸릴지에 상한이 없으므로 이 창을 없앨 수는 없고, 상한을 관측된 소요보다 넉넉히 잡아 확률을 낮출
뿐이다. **재시도 설정을 올릴 때 이 상한을 함께 봐야 하는 이유**가 그것이다 — 이제 시도 횟수와
대기가 회차마다 따로 있으므로 한 회차만 올려도 실패한 그 회차가 그만큼 길어진다.

겹쳐 돌아도 정합성이 깨지지는 않는다 — 각 회차가 완결된 상태를 단일 트랜잭션으로 쓰므로
마지막 커밋이 이긴다. 락이 막는 것은 정합성이 아니라 중복 실행의 낭비다.

## 9. 후속 작업자가 고쳐야 할 기존 테스트

**이 작업에서 테스트를 손대지 않았다.** 아래는 main 변경 때문에 손이 가야 하는 곳의 목록이다.

### 9-1. `PlaceStatsFacadeTest` — `recalculatePlaceCounts()` 호출 13곳

메서드가 없어졌으므로 전부 컴파일이 깨진다. 단순 치환으로 끝나지 않는 것부터 적는다.

| 테스트 | 해야 할 일 |
|---|---|
| `카운트_배치는_리뷰_축과_북마크_델타를_모두_돌린다` (:112) | **검증하던 계약이 사라졌다.** "각 진입점이 자기 축만 부른다"로 뒤집는다 |
| `리뷰_축이_실패해도_델타_소비는_돌린다` (:167) | 근거가 "같은 메서드 안 순차"에서 "아예 다른 회차"로 바뀐다. 지우지 말고 프레임을 갈 것 — 분리가 이 보장을 오히려 강화한다 |
| `최대_시도_1이면_재시도하지_않는다` (:255) | `setBatchMaxAttempts(1)` → `setReviewCountMaxAttempts(1)` |
| `createFacade` (:64) | `setBatchRetryDelay(Duration.ZERO)`만으로는 매시 두 축이 실제로 5초씩 잔다. `setReviewCountRetryDelay`·`setBookmarkDeltaRetryDelay`도 0으로 |
| `매시_회차는_전량_재계산을_부르지_않는다` (:127) | 두 진입점 각각에 적용 |
| `countCronPlaceholderFallsBackToHourlyHalfPast` (:320) | `resolvedCron("recalculatePlaceCounts")` → `"recalculateReviewCounts"` |
| `세_배치의_시간대는_모두_KST로_고정돼_있다` (:379) | 셋 → 넷 |
| `세_배치의_발화_시각은_겹치지_않는다` (:395) | 넷 사이 **전 쌍** 비교로 확장 |
| `파사드에는_트랜잭션_어노테이션이_붙어_있지_않다` (:508) | 새 메서드 이름 둘 추가 |
| `logsStartBeforeInvokingProcessor` (:549) | **진입점 치환만으로는 깨진다.** 회차 종료 로그가 생겨 INFO가 두 줄(시작·종료)이 되므로 `singleElement()`(:557)를 시작 줄을 고르는 단언으로 바꿔야 한다 |
| 나머지 (:97, :142, :152, :181, :201, :218, :522) | 검증 대상 축에 맞는 진입점으로 치환 |

**추가할 것:** 북마크 델타 cron 기본값이 매시 :15로 해석되는지 (기존 cron 기본값 테스트 3개와 같은 틀),
두 축이 서로 다른 재시도 설정을 따르는지, 그리고 **매시 두 회차가 성공/실패 양쪽에서 종료 로그를
남기는지** — 특히 실패한 회차에서도 종료 줄이 나오고 `결과=실패`가 찍히는지. 재시도 대기를 0이 아닌
값으로 두면 종료 줄의 `elapsed`가 시도별 `elapsed`보다 크다는 것도 확인할 수 있다.

### 9-2. `PlaceStatsSchedulerLockIT`

- 락 이름 상수에 `place-stats-bookmark-delta` 추가. `COUNT_LOCK_NAME`은 그대로 `place-stats-count`.
- `facade.recalculatePlaceCounts()` 2연호출(:97-98) → 두 진입점 각각 2연호출.
- `containsExactly(...)` 락 이름 목록(:109) 3개 → 4개.
- `@DynamicPropertySource`(:69)에 `solply.place-stats.bookmark-delta-cron` → `"-"` 추가.

### 9-3. cron 비활성 줄을 추가해야 하는 IT 11개

각 파일의 `@DynamicPropertySource`에 이미 `count-cron`·`count-safety-cron`·`score-cron`이
`"-"`로 꺼져 있다. **`bookmark-delta-cron`을 빠뜨리면 스위트가 매시 :15를 지나는 순간 새 배치가
돌아 단언이 흔들린다.** (`"-"`는 스프링이 "등록하지 않음"으로 읽는 센티널이다.)

`BookmarkCountEventPublishIT:48` · `AdminPlaceUpdateSnapshotIT:53` ·
`PlaceListViewPatchEquivalenceIT:52` · `PlaceListSnapshotEquivalenceIT:78` ·
`PlaceListVersionIssuerIT:64` · `PlaceListSnapshotLoaderIT:62` · `PlaceListSqlCountIT:50` ·
`PlaceListFlowIT:93` · `PlaceStatsSchedulerLockIT:69` · `JwtAuthenticationFilterIT:61` ·
`PrincipalDetailsServiceIT:54`

### 9-4. 손댈 필요가 없는 것

`BookmarkCountDeltaProcessorIT`·`PlaceStatsBatchProcessorIT`는 프로세서를 직접 부르므로
진입점 분리의 영향을 받지 않는다. 시작 로그 문구를 문자열로 단언하는 테스트는 없다(확인함).

## 10. 같은 날의 후속 — 소비 경계가 한 번 더 갈렸다

이 문서 뒤에 **북마크 델타 소비의 경계가 전량 `FOR UPDATE` 읽기에서 표식 claim으로 바뀌었다**
([2026-09-12 아웃박스 DB 집계](2026-09-12-stats-commit-snapshot-and-db-delta.md)).
회차 구조·cron·락·재시도는 이 문서 그대로이고, 갈린 것은 한 회차 안에서 전표를 잡고 접는
방식이다. 안전망·기동 백필의 전표 비우기도 같은 관문으로 옮겼다.

**스냅샷 갱신도 같은 날 갈렸다 — 10분 무조건 재빌드가 사라졌다.** 이제 통계 트랜잭션이 커밋되면
그 인스턴스가 재빌드를 예약하고 Redis 채널로 다른 인스턴스에도 알린다. 재빌드는 기본 5초 폴이
요청이 있을 때만 돌리므로, 이 문서의 "다음 스냅샷 회차"는 <b>커밋 직후의 폴</b>로 읽어야 한다
(후속 문서 4장).

## 11. 이 작업에서 하지 않은 것

- 테스트 작성·수정·실행, `./gradlew` 빌드 — 후속 작업자의 몫이다.
- yml 편집 — 5장의 키 목록으로 대신한다.
- 커밋·푸시·배포.
