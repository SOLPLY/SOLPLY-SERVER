# 2026-09-09 · 재빌드가 결과 전량을 동시에 들지 않게 — Hibernate streaming과 재빌드 경로 정리

> **지위: #401의 확정 설계이자 판단 기록이다.** #400(표시값 분리, 전량 재빌드 유지)의 후속이다.
> 출발점은 #400 규모 캠페인에서 본 100배 규모의 Full GC였고, 답은 "조회 모델을 바꾸지 않고
> 재빌드가 중간 결과를 쥐는 방식만 바꾼다"였다. 결과물보다 **어디서 멈췄고 왜 더 내려가지
> 않았는지**가 이 문서의 본체다. 구현이 이 문서와 달라지면 문서를 먼저 고친다.
> 측정 수치는 구현 뒤 캠페인에서 채운다(§7). 빈칸은 빈칸으로 둔다.

## 1. 어디서 시작했나

#400 캠페인(2026-09-09, `load-test/campaigns/2026-09-09_rebuild-scale/`)에서 전량 재빌드를
세 규모로 태웠다. 부하 없이 발화 1회씩의 관측이다.

| 규모 | 장소 수 | 재빌드 소요 | GC |
|---|---|---|---|
| 현재 | 6,320 | 87ms | Young만 |
| 10배 | 63,200 | 0.4~0.5초 | Young 1회, 스로틀 없음 |
| 100배 | 632,000 | 약 5초 | 힙 상한 도달, **Full GC(G1 Compaction Pause 327ms)**, cgroup 스로틀 |

100배는 이 서비스가 실제로 요구하는 규모가 아니다. 그래서 인메모리 조회 모델과 전량 재빌드
방식은 그대로 두기로 했다(#400 §5). 다만 100배가 보여 준 것은 규모 자체가 아니라 **재빌드가
결과를 쥐는 모양**이었다. 로더는 native query의 `getResultList()`로 결과 전체를
`List<Object[]>`로 만든 뒤에야 순회를 시작한다.

```
MySQL 결과
 → Connector/J가 결과 전체를 버퍼링
 → Hibernate가 행마다 Object[]를 만들어
 → List<Object[]> 전체를 완성한 뒤
 → 그다음에야 애플리케이션이 순회해 PlaceEntry·PlaceView를 만든다
```

즉 어느 순간에는 "중간 행 전량 + 최종 엔트리·표시 맵 + 보존 중인 옛 스냅샷 3장"이 힙에 함께
있다. 총 할당량이 아니라 **동시에 살아 있는 양**이 힙 정점을 정하고, 그 정점이 상한을 치면
Full GC가 된다. 이 이슈가 겨누는 것은 그 정점이다.

## 2. 현재 데이터 흐름 (구현 전 형상, `bfd1bdc`)

`SnapshotLoader#rebuild()`의 실제 순서다.

```
공통 쓰기 락(CacheWriteLock) → 새 읽기 트랜잭션(REQUIRES_NEW, readOnly)
  ├─ 문장② 썸네일 전량 → List<Object[]>(이미지 수만큼)
  │    → 장소별 첫 행만 골라 → Map<장소ID, 완성 URL>
  ├─ 문장① 장소·통계·MAIN 태그 → List<Object[]>(장소 수 + MAIN 중복분)
  │    → List<PlaceEntry> + HashMap<장소ID, PlaceView>
  └─ 문장③ 태그 전량(34행) → HashMap
트랜잭션 종료
  → 동네별 그룹화 + 정렬 배열 5종(SortedPlaces.of)
  → 버전 발급(자기 트랜잭션)
  → 표시 맵을 ConcurrentHashMap으로 **복사**해 교체
  → 태그 맵을 ConcurrentHashMap으로 **복사**해 교체
  → 스냅샷 교체(SnapshotBox.adopt)
```

큰 중간 결과는 넷이다. 이미지 전체 행, 장소 전체 행, 썸네일 URL 맵, 표시 맵 복사본.
정렬 배열 5종은 같은 `PlaceEntry`를 참조하므로 엔트리를 다섯 벌 만드는 구조는 아니다.

주석과 달리 문장①은 MAIN 태그가 둘 이상인 장소에서 여러 행을 내고, 자바에서 직전 id 비교로
첫 행만 남긴다. 이 규칙은 그대로 지켜야 한다(`place_tag.id` 오름차순의 첫 행).

(2026-09-09 후속: 이 규칙은 `place_stats.main_tag_id`로 옮겨졌다 — 문장①이 조인 없는 단일
테이블이 되면서 여러 행도, 직전 id 비교도 사라졌다.
`docs/design/2026-09-09-place-stats-self-contained.md`)

## 3. 결과를 소비하는 방식 — 세 단계 중 어디서 멈추나

사용자가 준 사다리는 넷이었다. ① 지금의 `getResultList()`, ② Hibernate/JPA 계층의
streaming·scroll, ③ 전용 로더 안에 한정한 JDBC `ResultSet` streaming, ④ JDBC streaming +
primitive getter로 `Object[]`·boxing까지 제거. 원칙은 "가장 단순하게 문제를 줄이는 단계에서
멈춘다"였다.

**②에서 멈춘다.** ORM을 유지한 채로 "전량 `List<Object[]>`"와 "드라이버의 전량 버퍼링"을
둘 다 없앨 수 있다는 것을 확인했기 때문이다.

### 3-1. ②가 실제로 성립하는 근거 (Hibernate 6.5.3.Final · Connector/J 8.4.0, 바이트코드로 확인)

문서나 기억이 아니라 이 프로젝트가 실제로 쓰는 jar를 `javap`으로 읽었다.

- `AbstractSelectionQuery#stream()`은 `scroll(ScrollMode.FORWARD_ONLY)`를 열고
  `ScrollableResultsIterator`로 한 행씩 넘긴다. `getResultList().stream()`이 아니다.
- `StatementPreparerImpl#prepareQueryStatement`는 FORWARD_ONLY면 `prepareStatement(sql)`
  기본형을 쓴다. 즉 ResultSet은 `TYPE_FORWARD_ONLY` + `CONCUR_READ_ONLY`다.
- `DeferredResultSetAccess#executeQuery`는 `QueryOptions.getFetchSize()`가 있으면
  `PreparedStatement.setFetchSize`로 넘긴다. Hibernate `Query#setFetchSize`가 여기까지 닿는다.
- Connector/J `StatementImpl#createStreamingResultSet()`은 forward-only · CONCUR_READ_ONLY ·
  `fetchSize == Integer.MIN_VALUE` 세 조건이 모두 맞을 때만 참이다. 이것이 행 단위
  streaming(`ResultsetRowsStreaming`)의 스위치다.
- `ServerPreparedStatement#executeInternal`도 그 boolean을 `serverExecute`에 그대로 넘긴다.
  벤치·운영이 쓰는 `useServerPrepStmts=true` 구성에서도 같은 스위치가 동작한다.

따라서 로더의 대량 문장 두 개에만 다음을 적용하면 된다.

- `NativeQuery`로 unwrap해 `setFetchSize(Integer.MIN_VALUE)`.
- `stream()`을 try-with-resources 안에서 순차 소비하고, 행을 받는 즉시 기존 변환으로
  `PlaceEntry`·`PlaceView`를 만든다.
- **다음 문장 전에 반드시 스트림을 닫는다.** Connector/J는 streaming 결과가 열려 있는 동안
  같은 커넥션의 다른 문장을 거부한다(`checkForOutstandingStreamingData`). 세 문장이 한 읽기
  트랜잭션(= 한 커넥션)을 쓰므로 순서와 종료가 곧 정확성이다. 이 성질은 역으로
  "정말 streaming하고 있는가"의 증거로도 쓴다 — 스트림을 반쯤 소비한 상태에서 같은 커넥션에
  두 번째 문장을 던지면 거부돼야 한다. IT가 그것을 확인한다.

행마다 `Object[]`와 Hibernate 타입 변환은 남는다. 그것은 이번 범위에서 받아들인 비용이다(§3-3).

### 3-2. ③ JDBC로 내려가지 않는 이유

같은 드라이버 스위치를 Hibernate에서 지정할 수 있으므로, JDBC로 내려가도 이번 목표(동시 보유
제거)에 더해지는 것이 없다. 반면 컬럼 순서·타입 매핑과 `Connection`·`Statement`·`ResultSet`
수명을 직접 관리하는 코드가 로더에 생기고, 트랜잭션 경계를 Spring이 아니라 손으로 맞춰야
한다. `useCursorFetch=true` + 양수 fetch size로 서버 커서를 쓰는 길도 있지만, 그것은 커넥션
URL 전역 설정이라 한 문장에 한정할 수 없다. 문장 두 개에 `setFetchSize` 한 줄씩 붙이는 쪽이
격리가 더 좋다.

### 3-3. ④ primitive getter 최적화를 하지 않는 이유

`Object[]`, boxing된 `Long`/`Double`, `BigDecimal`, `Timestamp`는 행마다 생겼다가 바로 버려지는
짧은 수명 객체다. 총 할당량에는 잡히지만 **동시에 살아 있는 양**에는 거의 기여하지 않는다.
이번 이슈의 성공 기준이 peak heap과 Full GC이므로 여기까지 내려갈 근거가 없다. 측정 뒤
allocation 자체가 병목으로 남는 것이 확인될 때의 후속 후보로만 적어 둔다(§9). 커서·정렬 값의
표현을 바꾸는 SQL 수준 타입 변환도 같은 이유로 하지 않는다.

## 4. 함께 정리한 저비용 항목 셋

### 4-1. A. 썸네일 URL을 미리 만들지 않는다 — 채택

재빌드마다 모든 장소의 대표 이미지에 `String.format`으로 완성 URL을 만들어 `PlaceView`에
담고 있었다. URL은 응답 페이지에 실리는 열 몇 건에만 필요하다. `PlaceView`에는 DB의
`image_file_key` 원값만 담고, 응답 DTO를 조립할 때 `ImageUrlProvider#getImageUrl`을 부른다.
단건 패치(`readView`)도 같은 표현으로 통일한다.

응답 문자열은 완전히 같다. `getImageUrl`은 blank 키에 `null`을 내므로 "빈 키 → null,
이미지 없음 → null"이 그대로 성립한다. #400이 "조회 경로에서 문자열 결합조차 하지 않는다"고
적었던 계약은 뒤집힌다 — 페이지당 최대 50번의 `String.format`은 재빌드마다 N번 만드는 것보다
싸고, 재빌드가 쥐는 문자열도 그만큼 준다.

### 4-2. B. 표시 맵을 두 번 만들지 않는다 — 채택

로더가 `HashMap`을 만들면 `PlaceViewHolder#replaceAll`이 `new ConcurrentHashMap<>(fresh)`로
다시 복사했다. 장소 맵은 어드민 단건 패치가 `put`으로 항목을 고치므로 동시 수정 가능한 맵이
맞다. 그러니 로더가 처음부터 `ConcurrentHashMap`을 만들어 소유권째 넘기고, 홀더는 참조만
교체한다. 시그니처를 `ConcurrentMap`으로 좁혀 일반 맵이 들어올 수 없게 한다. 새 동시성
구조는 만들지 않는다 — 쓰기는 여전히 `CacheWriteLock` 안이고 읽기는 락 없이 그대로다.

태그 맵은 항목 단위 수정이 없고 전량 교체뿐이다. `ConcurrentHashMap`일 이유가 없어 불변 맵으로
바꾼다. 34행이라 복사 비용은 의미가 없고, 요점은 "이 맵은 바뀌지 않는다"를 타입으로 말하는 것이다.

### 4-3. C. DB에서 대표 이미지 1건만 받는다 — 측정 뒤 기각, 현재 문장 유지

> (2026-09-09 후속: 대표 이미지 선택은 쓰기 시점으로 옮겨 `place_stats.thumbnail_file_key`가 됐다 —
> 이 절이 기각한 것은 재빌드마다 도는 비용이다. `docs/design/2026-09-09-place-stats-self-contained.md`)

문장②는 장소별 이미지 전부를 받아 자바에서 첫 행만 쓴다. "DB가 장소당 1건만 주면 전송도
중간 데이터도 준다"는 가설로 후보 넷을 `EXPLAIN ANALYZE`로 쟀다(x10·x100 스키마, 워밍업 1회 뒤
3회 중앙값, 최상위 actual time). 원출력은
`load-test/campaigns/2026-09-09_rebuild-streaming/results/explain-thumbnail/`.

| 후보 | x10 | x100 | 계획 모양 |
|---|---|---|---|
| 현재 문장②(별도, 전량) | 27.6ms | 333ms | 테이블 스캔 + filesort 하나. 임시 테이블 없음 |
| 별도 문장 + ROW_NUMBER() | 74.4ms | 1,360ms | 정렬 → 윈도우 → 파생 테이블 머티리얼라이즈(x100에서 디스크) → 다시 정렬 |
| 문장①에 상관 스칼라 서브쿼리 | 문장① 대비 +177ms | +2,280ms | 장소마다 인덱스 조회 **+ filesort**(타이브레이커 `image_file_key`가 인덱스 밖). 조인 결과가 디스크 임시 테이블로 |
| 문장①에 ROW_NUMBER 파생 테이블 LEFT JOIN | +112ms | +2,029ms | 머티리얼라이즈 1회 + 장소마다 조회. 문장① filesort merge pass가 정확히 2배(TEXT가 정렬 버퍼에 실림) |

참고로 문장① 단독은 x10 212ms / x100 2,386ms이고, 두 문장을 따로 도는 지금 구조의 합은
x100에서 2,719ms다. 합친 후보 둘은 그보다 1.6~1.7배 걸렸다.

**기각 이유는 데이터 모양이다.** 장소당 이미지가 평균 1.1건(최대 3건)이라 현재 문장이 더
보내는 행은 전체의 10%뿐이고, 계획은 filesort 하나짜리 가장 단순한 형태다. "1건만 반환"하는
어느 후보도 그 10%를 아끼려고 더 비싼 일(장소마다 정렬, 머티리얼라이즈, 디스크 임시 테이블)을
한다. 그리고 §3의 streaming이 들어오면 그 10%조차 동시에 들고 있지 않으므로, C의 원래 동기는
streaming이 흡수한다.

결과 동일성은 x10에서 세 후보 모두 현재 규칙과 체크섬까지 같았다. 다만 이 데이터에는
`display_order` NULL·중복도, 빈 키도 없어 타이브레이커 규칙이 한 번도 발동하지 않았다. 후보가
같은 값을 냈다는 것이지 동률·NULL 상황의 동치를 증명한 것은 아니다 — 기각했으니 이 한계가
결론을 흔들지는 않는다.

**뒤집힐 조건.** 장소당 이미지 수가 크게 늘어 현재 문장의 초과 전송이 무시할 수 없어질 때.
그때는 `place_images`에 대리키를 두고(#400 후속에 이미 있음) 타이브레이커를 값이 아니라 id로
바꾼 뒤 다시 잰다.

## 5. 바뀌는 곳과 지켜야 할 것

| 곳 | 바뀌는 것 | 지켜야 할 것 |
|---|---|---|
| `SnapshotLoader` 문장①·② | `getResultList()` → `setFetchSize(MIN_VALUE)` + `stream()` 순차 소비 | 다음 문장 전 스트림 종료. 한 읽기 트랜잭션. MAIN 중복 첫 행 유지. 문장③·단건은 그대로 |
| `PlaceView` | `imageUrl` → `thumbnailFileKey` | `null`/blank 키가 응답에서 `null` URL이 되는 것 |
| `PlaceService#listPlaces` | 조립 시 `getImageUrl` 호출 | 응답 문자열 동일 |
| `PlaceViewHolder` | 복사 없이 참조 교체, `ConcurrentMap` 시그니처 | `put` 경로, 락 규칙 |
| `TagViewHolder` | 불변 맵 | 비활성 태그도 담는 것 |

등가는 기존 테스트가 지킨다 — 로더 IT(표시값 = 엔티티 경로), 목록 등가 IT(순서·커서 = DB
경로), 표시값 패치 IT(패치 = 재빌드), 어드민 갱신 IT. 여기에 "스트림 도중 두 번째 문장이
거부된다"는 streaming 증거 테스트를 더한다.

## 6. 요구와 선택의 기록

| 요구 | 선택 | 안 한 것 |
|---|---|---|
| 재빌드가 결과 전량을 동시에 쥐지 않는다 | Hibernate `stream()` + 드라이버 행 단위 streaming | JDBC 직접(③), primitive getter(④) |
| ORM 계층을 유지한다 | `NativeQuery#setFetchSize` 한 줄 | `useCursorFetch` 전역 설정 |
| 응답 문자열이 같다 | file key 저장, 조립 시 URL 생성 | 재빌드 시 URL 사전 생성 |
| 동시성 구조를 새로 만들지 않는다 | 로더가 만든 `ConcurrentHashMap`을 그대로 소유 | 홀더에서 재복사, 새 락 |
| 대표 이미지 규칙이 같다 | 현재 문장 유지(측정으로 기각) | 상관 서브쿼리, ROW_NUMBER |

## 7. 측정 — 게이트와 결과

**증명할 주장(측정 전 확정, 2026-09-09 사용자 합의).**

1. 문제 — 재빌드가 결과 전량을 `List<Object[]>`로 동시에 들고 있어, 규모가 커지면 최종
   스냅샷과 별개로 중간 결과만큼 힙 정점이 높아진다.
2. 원인 가설 — 100배 Full GC의 직접 원인은 총 allocation이 아니라 "중간 행 전량 + 최종
   엔트리·표시 맵 + 보존 중인 옛 스냅샷"이 한 시점에 겹치는 live set이다.
3. 기술적 선택 — §3의 streaming + §4의 A·B. 총 allocation은 A로 일부 줄지만 주된 목표가 아니다.
4. 검증할 주장 — 같은 조건에서 재빌드 중 peak heap(GC 직전 최대 heapUsed)이 내려가고, 100배의
   Full GC가 사라지거나 줄며, 스로틀이 늘지 않는다. 재빌드 시간은 나빠지지 않는다.
5. 성공 조건 — 100배: Full GC 0회 또는 변경 전보다 감소, peak heap 감소. 10배: peak heap
   감소, Young GC·재빌드 시간이 변경 전과 같은 수준 이하. 현재 규모: 회귀 없음(재기준선).
   **판정은 총 allocation 감소가 아니라 peak heap·Full GC로 한다.**

**방식.** 같은 창에서 변경 전 이미지(`solply-bench-app:bfd1bdc`)와 변경 후 이미지를 번갈아
현재/10배/100배에 올려 무부하 발화로 재빌드를 태운다. 과거 창(위 §1 표)과는 비교하지 않는다.
절차와 스크립트는 `load-test/campaigns/2026-09-09_rebuild-streaming/`.

**결과 (2026-09-09 17:06~17:38, 라운드 7개 연속 · 변경 전 `bfd1bdc` vs 변경 후 `78abdac`).**
정본은 `docs/perf/2026-09-09-rebuild-streaming.md`, 원값은 캠페인 README 결과 절.
값은 발화 중앙값(현재 5회 · 10배 3회 · 100배 3회), GC는 발화 합.

| 규모 | 구성 | 재빌드 소요 | peak heap(GC 직전 최대) | Young / Full GC | 재빌드 스레드 할당 | 스로틀(`throttled_usec` 라운드 합) |
|---|---|---:|---:|---:|---:|---:|
| 현재 | 변경 전 | 62 ms | (창 안 GC 없음) | 1 / 0 | 37.2 MB | 9,408 us |
| 현재 | 변경 후 | 95 ms | (창 안 GC 없음) | 1 / 0 | 29.3 MB | 4,071 us |
| 10배 | 변경 전 | 658 ms | 표본 2건(239.5 · 850.0 MB) | 2 / 0 (Old 1) | 353.1 MB | 35,257 us |
| 10배 | 변경 후 | 517 ms | 표본 1건(818.5 MB) | 2 / 0 (Old 1) | 276.6 MB | 92,071 us |
| 100배 | 변경 전 | 5,232 ms | **1,280.0 MB**(상한, 3발화 전부) | 74 / **4** | 3,564.9 MB | 229,650 us |
| 100배 | 변경 후 | 4,363 ms | **1,198.0 MB**(1,133 · 1,198 · 1,280) | 27 / **1** | 2,803.2 MB | 191,979 us |

**판정.**
- 100배 — 성공 조건 충족(Full GC 4 → 1, peak heap 상한 → 1,198 MB). 그러나 변경 후도 **셋째
  발화는 상한에 붙고 Full GC 1회**다. After GC 최소값이 419 → 643 → 720 MB로 발화마다 오르는
  것이 이유다 — 보존 스냅샷이 3장으로 차는 정상 상태에서는 중간 결과가 아니라 **스냅샷 자체
  (보존 3장 + 건설 중 1장)**가 정점을 정한다. §9의 첫 신호가 그대로 발현된 것이고, 다음 손잡이는
  로더가 아니라 보존 장수나 힙이다. 총 할당 −21%에 Full GC는 4분의 1 — 원인 가설(동시 보유가
  정점을 정한다)은 지지된다.
- 10배 — GC 횟수 같음, 할당 −22%. 재빌드 소요 −141 ms는 같은 조건 드리프트(−187 ms) 안이라
  이득으로 세지 않는다. peak heap은 창 안 GC가 있던 발화가 1~2건뿐이라 **미판정**.
- 현재 — 중앙값 +33 ms. 표본 5개·드리프트 28%로 회귀인지 잡음인지 가르지 못했다. 행 단위
  scroll의 행당 고정 비용이 6만 4천 행에서 드러났을 가능성은 있으나 측정으로 가른 것은 아니다.
  절대값은 어드민 한 번에 수십 ms.

## 8. 하지 않은 것과 그 이유

- **SoA 전환, 정렬 배열 장소 단위 패치, bit index 회수.** #400 §7·§11-6에서 기각한 그대로.
  100배는 요구가 아니다.
- **JDBC 직접 구현.** §3-2.
- **primitive getter로 `Object[]`·boxing·`BigDecimal`·`Timestamp` 제거.** §3-3. 후속 후보로만.
- **대표 이미지 1건 SQL.** §4-3. 측정으로 기각.
- **커서·정렬 값 표현을 바꾸는 SQL 타입 변환.** 근거 없는 미세 최적화.

## 9. 다시 검토할 신호

- ~~측정(§7)에서 peak heap이 내려갔는데도 100배 Full GC가 남는다~~ — **발현됐다(§7).** 셋째
  발화(보존 3장이 찬 뒤)에서만 남았다. 정점을 만드는 것은 중간 행이 아니라 최종 스냅샷 자체
  (엔트리 + 배열 5종 + 표시 맵 × 보존 3장)이고, 다음 손잡이는 `SnapshotBox.RETAINED`나 힙
  크기지 로더가 아니다. 100배가 요구가 되는 날 먼저 잴 것은 보존 장수 1·2·3의 같은 창 비교다.
- 재빌드 스레드 allocation이 여전히 시간을 지배한다는 프로파일이 나온다 — 그때 ④를 꺼낸다.
  이번 측정으로는 근거가 없다(남은 정점은 행별 임시 객체가 아니다).
- 현재 규모의 +33 ms가 발화 20회 이상에서도 재현된다 — 행 단위 scroll의 행당 고정 비용이
  실재한다는 뜻이다. 그때 선택지는 "작은 규모에서는 `getResultList()`"가 아니라(경로가 둘로
  갈린다) 양수 fetch size(`useCursorFetch`)로 행 단위 왕복을 묶는 것이다. 어드민 한 번에 수십
  ms라 지금은 손대지 않는다.
- 장소당 이미지 수가 크게 는다 — §4-3의 뒤집힐 조건.

## 10. 이 문서로 말할 수 없는 것

- 부하 중 재빌드가 사용자 조회에 주는 영향. 이번 측정은 무부하 발화다(#400 캠페인의 몫).
- 다중 인스턴스. 앱 1대다.
- streaming이 DB 쪽 자원(커넥션 점유 시간, 서버 측 결과 버퍼)에 주는 변화. 잰 것은 앱 힙이다.
