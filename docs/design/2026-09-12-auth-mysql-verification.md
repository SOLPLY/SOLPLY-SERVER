# 인증 MySQL 전환과 통계 스케줄 분리 — 검증 기록

2026-09-12 · 이슈 #404 · `feat/#404-auth-mysql-stats-schedules` worktree.
설계는 [2026-09-12-auth-mysql-and-stats-schedules.md](./2026-09-12-auth-mysql-and-stats-schedules.md),
구현 기록은 [인증](./2026-09-12-auth-mysql-implementation.md)·[통계](./2026-09-12-stats-schedule-split.md),
코드 검토는 [2026-09-12-auth-mysql-review.md](./2026-09-12-auth-mysql-review.md).

이 문서는 **실행한 것과 그 결과**다. 검토 문서가 "읽은 결과"였다면 여기는 "돌린 결과"이고,
그래서 아래의 모든 주장에는 그것을 낸 테스트 메서드 이름이 붙어 있다.

---

## 1. 무엇을 돌렸나

```bash
# 전체 (컴파일 + 전 테스트). 아래 수치는 이 명령의 결과다.
./gradlew clean build

# 인증만 다시 돌릴 때
./gradlew test --tests '*RefreshToken*' --tests '*AdminTempStateConsumeIT' \
               --tests '*AuthTokenCleanup*' --tests '*Jwt*' --tests '*AuthConfigValidationTest'

# 통계 스케줄만
./gradlew test --tests '*PlaceStats*' --tests '*BookmarkDelta*'
```

| | 값 |
|---|---|
| 전체 테스트 | **522** |
| 실패 | **0** |
| 오류 | **0** |
| **스킵** | **0** |
| 테스트 클래스(중첩 포함) | 60 |
| 리포트 | `build/reports/tests/test/index.html` · 원본 XML은 `build/test-results/test/` |

**스킵이 0인 것이 수치 중 하나다.** 합의문이 "환경변수 부재로 skip하지 않는다"를 요구했고,
이 스위트에는 `@Disabled`도 `assumeTrue`도 없다 — MySQL 컨테이너가 뜨지 않으면 테스트가
**실패하지 통과하지 않는다**.

### 환경

- Java 21 (Gradle toolchain), Gradle 8.14.2.
- **실제 MySQL 8.0 Testcontainers.** `MySqlContainerSupport`의 싱글턴 컨테이너 하나를 전 IT가 공유하고
  Flyway가 V1~V41을 실제로 적용한다. H2는 이 경로에 개입하지 않는다.
- 검증 시작 시점에 로컬 Docker 데몬이 꺼져 있었다(`docker info` 실패). Docker Desktop을 기동해
  격리된 Testcontainers만 사용했고, **운영 DB·compose·배포에는 접근하지 않았다.**
- 커밋·푸시·PR 없음.

---

## 2. 합의문 검증 기준 1~14 대조

| # | 요구 | 어디서 확인했나 |
|---|---|---|
| 1 | 동시 회전 자식 하나, 패자도 동일 Refresh, 정상 경쟁 전체 폐기 없음 | `RefreshTokenRotationConcurrencyIT#동시_회전은_자식_하나를_만들고_진_쪽도_같은_refresh를_받는다` |
| 2 | CAS 실패 시 최신 부모·자식 재조회, stale 함정 방지 | 같은 클래스 `#CAS에_진_요청은_최신_부모를_다시_읽는다` (+ 위 테스트의 `casResults == {1,0}`) |
| 3 | JWT↔DB 정밀도 왕복 동일 문자열, 헤더·클레임 고정, 유예/만료 무연장 | `RefreshTokenLifecycleIT.Reconstruction` 4개 — `DB_왕복_후_바이트까지_같은_refresh_문자열이_나온다` · `저장된_발급_만료_시각은_JWT의_정수_초와_같다` · `헤더와_클레임의_순서가_고정돼_있다` · `유예_재발급은_부모의_유예도_자식의_만료도_연장하지_않는다`. 결정성 자체는 `JwtTokenProviderTest#같은_재료는_항상_같은_문자열로_직렬화된다` |
| 4 | 시각 경계와 REVOKED/EXPIRED/ACTIVE/GRACE 우선순위 | `RefreshTokenRowStateTest` 7개(경계 정각까지) + `RefreshTokenLifecycleIT.TimeBoundary` 5개 |
| 5 | 연속 회전·자식 회전/폐기/만료·정합성 오류 | `RefreshTokenLifecycleIT.Chain` 7개 |
| 6 | 유예 종료·폐기 토큰 재사용의 전체 폐기가 **실제 커밋** | `RefreshTokenLifecycleIT.ReuseDetection` 4개. 커밋 여부는 전부 **독립 커넥션**으로 읽는다 |
| 7 | 전체 폐기 ↔ 회전/발급의 양방향 순서, 폐기 후 새 로그인 허용 | `RefreshTokenRotationConcurrencyIT` 3개 — `폐기가_먼저_커밋되면…` · `회전이_먼저_커밋돼도…` · `전체_폐기가_커밋된_뒤_잠금을_얻는_새_로그인은_살아남는다` |
| 8 | 자식 INSERT 실패 시 부모 UPDATE 롤백 | `RefreshTokenRotationConcurrencyIT#자식_INSERT가_실패하면_부모의_회전_표시도_되돌아간다` |
| 9 | 계열 로그아웃·반복 로그아웃·다른 계열 보존·탈퇴 재가입 차단 | `RefreshTokenLifecycleIT.LogoutAndWithdrawal` 8개 + `JwtAuthenticationFilterIT#로그아웃은_그_access를_낸_계열만_끊고_200을_돌려준다` + `UserWithdrawRefreshRevocationIT` 4개(프로덕션 탈퇴 경로) |
| 10 | 어드민 임시 상태/코드 만료·동시 일회 소비 | `AdminTempStateConsumeIT` 9개(쿠키 계약 포함) · `AdminNonceCookieTest` 4개 |
| 11 | 북마크 반영/삭제 실패 시 둘 다 롤백 | `BookmarkDeltaAtomicRollbackIT` 2개 (별도 작성자) |
| 12 | 두 cron/락/독립 재시도·로그·트랜잭션 경계, 새 스케줄 오발화 없음 | `PlaceStatsScheduleSplitTest` 13개(별도 작성자) · `PlaceStatsFacadeTest` 33개 · `PlaceStatsSchedulerLockIT` · 그리고 IT 11곳의 cron 차단(§5) |
| 13 | Access 검증 거절 목록, 일반 인증 저장소 0회, ADMIN 보호 회귀 | `JwtTokenProviderTest` 21개(중첩 포함) · `JwtAuthenticationFilterIT` 12개 |
| 14 | 실제 MySQL 마이그레이션과 보존 정리, 전체 빌드 | `RefreshTokenLifecycleIT.Migration` 4개 · `AuthTokenCleanupIT` 6개 · §1의 `./gradlew clean build` |

## 3. 검토 문서 7절의 추가 목록

| # | 요구 | 어디서 |
|---|---|---|
| 1 | 2-1을 **값으로** 고정 (로그아웃 계열의 지연 요청이 다른 계열을 죽이는가) | `#로그아웃한_계열의_토큰으로_재발급을_시도하면_다른_계열까지_끊긴다` — 로그아웃 직후 태블릿이 살아 있음을 먼저 단언하고, 지연 요청 뒤 끊기는 것을 단언한다. 시나리오 B는 `#재가입_직후_옛_토큰이_도착하면_새_계열까지_끊긴다` |
| 2 | 2-2의 상태 코드 | `#탈퇴한_사용자의_발급은_404이고_재발급은_401_AUTH_017이다` · `#주인이_사라진_refresh의_재발급도_401_AUTH_017이다` |
| 3 | 재구성은 **바이트** 비교, 사이에 DB 왕복 | `#DB_왕복_후_바이트까지_같은_refresh_문자열이_나온다` — 독립 커넥션으로 읽은 컬럼만으로 재료를 세우고 `getBytes(UTF_8)`로 비교한다 |
| 4 | CAS 0행은 저절로 재현되지 않는다 | §4 |
| 5 | H2로는 못 본다 | 인증·어드민 경로는 전부 `MySqlContainerSupport` 하위다. H2를 쓰는 IT는 이 스위트에 없다 |
| 6 | `solply.auth.cleanup-cron`이 차단 목록에 없다 | §5에서 11곳 전부에 추가 |
| 7 | `logsStartBeforeInvokingProcessor`의 `singleElement()` | 첫 INFO가 시작 줄인지 보는 단언으로 바꿨다(종료 줄이 생겨 INFO가 둘이다) |
| 8 | 전체 폐기 커밋은 다른 커넥션으로 | `AuthMySqlSupport#readCommittedRows` — 애플리케이션 풀이 아닌 `DriverManager` 세션이다 |

---

## 3-1. 검증 감정(`…-verification-review.md`)이 남긴 다섯 건

같은 날 별도 작업자가 **"단언을 뒤집어도 초록불이면 그것은 증명이 아니다"**라는 기준으로 이 스위트를
감정했고, 다섯 건을 짚었다. 전부 닫았다.

| 항목 | 무엇이 문제였나 | 어떻게 닫았나 |
|---|---|---|
| B | `폐기가_만료보다_우선한다`가 시계를 **만료 전**에 둬서 두 조건이 동시에 참이 되지 않았다 — 판정 순서를 뒤집어도 통과한다 | 시계를 `exp` 정각으로 옮겨 둘 다 참인 자리를 만들고, 같은 행에서 폐기 도장만 뺀 사본이 `EXPIRED`가 되는 것까지 함께 단언한다. 그 시점엔 JWT가 먼저 만료로 걸리므로 토큰 문자열을 다시 파싱하지 않고 손에 쥔 jti로 행을 읽는다 |
| C | "전체 폐기 대 발급"이라 적고 실제로는 `revokeFamily`를 불렀다 — 새 로그인은 **다른 계열**이라 순서가 뒤바뀌어도 살아남는다 | 재사용 감지를 실제로 일으켜 `revokeAllByUserId` 경로를 탄다. 술어가 `user_id`뿐이라 잠금 순서가 뒤집히면 새 계열도 함께 끊긴다 — 그래서 "폐기가 먼저 커밋됐기 때문에 살아남았다"가 말이 된다 |
| E | `REQUIRES_NEW`를 `REQUIRED`로 바꿔도 아무 테스트도 깨지지 않았다 — 바깥 트랜잭션 안에서 회전을 부르는 테스트가 없었다 | `RefreshTokenRotationConcurrencyIT#회전은_바깥_트랜잭션이_롤백돼도_자기_커밋을_지킨다`(동작) + `#상태를_바꾸는_메서드는_전부_REQUIRES_NEW_READ_COMMITTED다`(선언). 별도 작업자의 `AuthTransactionBoundaryIT` 4개가 외부 REPEATABLE READ까지 덮는다 |
| F | `oauth_nonce` 쿠키 속성에 테스트가 0건이었다 — 속성 하나가 빠져도 어드민 로그인은 정상 동작하고 잃는 것은 방어뿐이라 기능 테스트로는 드러나지 않는다 | `AdminTempStateConsumeIT#인가_URL_요청은_oauth_nonce_쿠키를_네_속성과_함께_내린다` + `AdminNonceCookieTest`. **`Secure`는 설정을 따라가는지와 코드 기본값이 안전한 쪽인지를 나눠서 본다** — 로컬 yml이 http로 돌기 위해 꺼 두므로 런타임 값을 그대로 단언하면 환경에 따라 깨진다 |
| G | 탈퇴가 프로덕션 경로가 아니라 테스트가 다시 짠 불변식이었다 — `UserWithdrawService`에서 `revokeAllByUserId` 한 줄을 지워도 아무것도 안 깨진다 | `RefreshTokenLifecycleIT`가 `userWithdrawService.withdraw`를 직접 부른다. 별도 작업자의 `UserWithdrawRefreshRevocationIT` 4개가 같은 경로를 더 넓게 문다 |

감정이 함께 짚은 잔가지도 닫았다 — 어드민 경로 인가 단언을 `isNotEqualTo(403)`에서 `isEqualTo(404)`로
(500도 통과하던 자리), 커넥션 관찰자에 **자기 점검**(관찰자가 죽어 있으면 "0회"가 언제나 참이 된다),
정리 배치의 덩어리 테스트를 프로세서 한 덩어리와 파사드의 `runInBatches`로 갈라 각각,
그리고 죽은 헬퍼 하나 제거.

---

## 4. 경쟁을 실제로 재현한 방법

**동시에 쏘는 것만으로는 CAS 0행 경로가 돌지 않는다.** 정상 진입 경로는 사용자 잠금으로
직렬화되므로, 두 요청을 그냥 동시에 보내면 뒤에 온 쪽이 잠금을 기다렸다가 **이미 회전된 부모를
읽고** 1행 경로를 한 번 더 도는 모양이 된다. 0행을 보려면 두 커넥션이 **각자의 분기용 SELECT를
먼저 끝낸 뒤** 잠금 획득이 어긋나야 한다.

그 만남의 지점이 `RefreshTokenRepository#lockUser`이고, `@SpyBean`이 거기서 실제 메서드를 부르기
**직전에** 두 스레드를 세운다. `rotate`의 순서가 ① 분기용 조회 → ② `lockUser` → ③ 조건부 UPDATE라,
barrier가 풀릴 때 두 스레드는 이미 같은 ACTIVE 부모를 본 상태다.

**프로덕션 코드에는 아무 장치도 넣지 않았다.** 테스트용 우회 API도 latch도 없다 — 스파이는 스프링
컨텍스트의 배선이고, 지연이 걸리는 자리는 저장소 메서드의 바깥이다.

증거로 쓰는 값은 셋이다.

1. **`markRotated`의 반환값 목록이 `{1, 0}`.** 스파이가 실제 메서드의 결과를 그대로 기록한다 —
   조건부 UPDATE가 한 번은 1행, 한 번은 0행을 냈다는 뜻이고, 0행 분기가 코드에만 있는 것이 아니라는
   증거가 이것이다.
2. **`findByJwtId` 호출 3회.** 두 스레드의 분기용 조회 둘에, 진 쪽의 재조회 하나. 재조회가 빠지면
   (또는 1차 캐시에 갇히면) 2회가 된다.
3. **`parent_jwt_id`로 찾은 자식이 정확히 1행**이고, 두 스레드가 받아 간 refresh 문자열이 서로 같다.

순서를 정해야 하는 경쟁(폐기 ↔ 회전/발급)은 barrier 대신 **latch 두 개**를 쓴다 — 경쟁자 스레드를
`lockUser` 앞에 세워 두고, 메인 스레드가 반대편 연산을 커밋한 뒤 풀어 준다. 그래야 "어느 쪽이 먼저
커밋됐는가"가 확률이 아니라 **테스트가 정한 사실**이 되고, 양쪽 순서를 각각 볼 수 있다.
스레드 이름으로 경쟁자를 가려 메인 스레드의 같은 호출은 가로막지 않는다.

---

## 5. 스케줄이 남의 테스트에서 깨어나지 않게

`@SpringBootTest`는 실제 앱을 띄우므로 `@Scheduled`가 등록된다. 스위트가 매시 :15를 지나면
북마크 델타 소비가, 04:40을 지나면 인증 보존 정리가 깨어나 다른 IT가 깔아 둔 행을 건드린다.

`"-"`(`Scheduled.CRON_DISABLED`)로 끄는 키를 **두 개 더** 넣었다 —
`solply.place-stats.bookmark-delta-cron`과 `solply.auth.cleanup-cron`. 손댄 곳은 11곳이다.

`BookmarkCountEventPublishIT` · `AdminPlaceUpdateSnapshotIT` · `PlaceListViewPatchEquivalenceIT` ·
`PlaceListSnapshotEquivalenceIT` · `PlaceListSnapshotLoaderIT` · `PlaceListVersionIssuerIT` ·
`PlaceListSqlCountIT` · `PlaceListFlowIT` · `PlaceStatsSchedulerLockIT` · `PrincipalDetailsServiceIT`,
그리고 인증 IT 전부가 상속하는 `support/AuthMySqlSupport`.

**정리 배치 쪽이 더 위험하다.** 매시 도는 델타보다 발화 확률은 낮지만, 깨어나면 지우는 것이
refresh 이력이라 실패했을 때 원인을 찾기가 훨씬 어렵다.

---

## 6. 관찰자를 바꾼 이유 — "저장소 0회"를 무엇으로 재는가

기존 `SqlStatementProbe`는 **하이버네이트가 만드는 문장만** 본다. 인증이 `JdbcTemplate`으로 내려가는
회귀는 그 관찰자에게 보이지 않으므로, 그것만으로는 "인증 경로는 저장소를 읽지 않는다"를 잴 수 없다.

`ConnectionCountingDataSourceConfig`가 `DataSource#getConnection()` 호출을 센다 — 어떤 계층이
무엇으로 질의하든 DB에 닿으려면 그 지점을 지난다. Redis 쪽은 `RedisConnectionFactory`를 스파이로
감싸 `getConnection()`이 한 번도 불리지 않음을 본다.

그래서 `JwtAuthenticationFilterIT#인증은_DB도_Redis도_읽지_않는다`의 단언은 셋이다 —
하이버네이트 문장 0, **커넥션 획득 0**, Redis 커넥션 0.

---

## 7. 이 검증이 찾아낸 것

### 7-1. `Refresh-Token` 헤더 누락이 500이었다 (보고 → 수정됨)

`POST /api/auth/refresh`에 헤더를 아예 빼면 스프링이 `MissingRequestHeaderException`을 던지는데
`GlobalExceptionHandler`에 그 타입의 핸들러가 없어 포괄 `@ExceptionHandler(Exception.class)`로
떨어졌다. 본문 모양(`CustomApiResponse`)은 유지되지만 **상태가 500**이라, 클라이언트가 자기 요청을
고칠 수 있는 실수를 "서버가 깨졌다"로 읽는다.

이번 전환이 만든 것이 아니라 이 엔드포인트가 처음부터 갖고 있던 성질이다. 검증 작업자는 main을
고치지 않으므로 coordinator에게 보고했고, 구현자가 `handleMissingRequestHeaderException`(400
`MISSING_REQUIRED_PARAMETER`)을 추가했다. 단언은
`JwtAuthenticationFilterIT#refresh_헤더가_아예_없으면_400이다`로 뒤집혀 있다.

### 7-2. 픽스처가 다른 IT의 수치를 흔들었다 (테스트 쪽 수정)

`AdminTempStateConsumeIT`가 만료된 state를 일부러 심고 지우지 않아, 같은 컨테이너를 쓰는
`AuthTokenCleanupIT`의 "몇 행을 지웠는가" 단언이 1 대신 2를 봤다. `@SpringBootTest`가 기본 커밋이라
생기는 문제이고, 양쪽을 함께 고쳤다 — 만드는 쪽은 자기가 만든 state를 지우고, 세는 쪽은 어드민 임시
테이블 둘을 회차 전에 비운다.

**이 실패는 제품 결함이 아니라 스위트 설계의 문제다.** 다만 남겨 두는 이유가 있다 —
커밋되는 IT가 늘수록 같은 종류의 간섭이 다시 생기고, 그때 증상은 언제나 "관계없어 보이는 테스트가
가끔 깨진다"이다.

지우는 범위는 **이 회차가 만료로 셀 행**으로 좁혔다. 테이블을 통째로 비우면 지금은 옳게 돌지만
나중에 그 테이블을 쓰는 IT가 생겼을 때 그쪽이 조용히 깨진다.

### 7-3. 픽스처 닉네임이 컬럼 길이를 넘었다 (테스트 쪽 수정)

`AuthTransactionBoundaryIT`·`UserWithdrawRefreshRevocationIT`가 닉네임을 `접두어 + UUID`로 만드는데
`users.nickname`은 `VARCHAR(30)`이라 41~42자가 되어 여덟 테스트가 전부
`Data too long for column 'nickname'`으로 죽었다. UUID를 8자로 잘라 고쳤다.

**이것이 "다른 클래스도 통과할 것"이라는 추론이 왜 안 되는지의 두 번째 실례다.** 두 파일은 코드
품질이 높았고 논리도 옳았지만, 스키마의 제약 하나에 걸려 한 줄도 돌지 못했다 — 읽어서는 보이지
않고 돌려야만 보이는 종류다.

---

## 8. 하지 않은 것과 남은 한계

- **main 수정은 하지 않았다.** 7-1은 보고했고 구현자가 고쳤다.
- **어드민 카카오 콜백 전체 흐름(`processKakaoCallback`)은 돌리지 않았다.** 카카오 HTTP 호출 둘이
  들어 있어 이 스위트의 범위(실제 MySQL·실제 트랜잭션) 밖이다. 그 흐름에서 검증 대상이었던
  *일회 소비의 원자성*과 *권한 재검사 시점*은 저장소·발급 계층에서 각각 확인했고
  (`AdminTempStateConsumeIT`), **nonce 불일치에도 state가 소비된다**는 성질만 코드 읽기로 남는다.
- **겹치는 단언이 여러 곳에 있다.** `PlaceStatsFacadeTest` ↔ `PlaceStatsScheduleSplitTest`(cron 기본값,
  락 이름, 회차별 재시도 키, 회차 종료 로그), `RefreshTokenRotationConcurrencyIT` ↔
  `AuthTransactionBoundaryIT`(트랜잭션 경계), `RefreshTokenLifecycleIT` ↔
  `UserWithdrawRefreshRevocationIT`(탈퇴), `AdminTempStateConsumeIT` ↔ `AdminNonceCookieTest`(쿠키).
  네 쌍 모두 **작성자가 달라서 생긴 중복**이고, 겹치는 쪽을 지우면 어느 한 파일만 남았을 때 검증이
  줄어든다. 알고 남긴 것이다.
- **부하·시간에 기댄 단언은 없다.** 유예 3초 경계는 전부 `MutableClock`으로 찍었고
  (`support/MutableClock`), 경쟁은 latch/barrier로 결정적이다. `Thread.sleep`으로 기다리는 테스트는
  이 스위트에 없다.
- 비밀 값은 어디에도 출력하지 않는다. `JwtTokenProviderTest`는 **테스트 안에서 만든 키**를 쓰고
  설정 파일의 실제 키를 읽지 않는다.

---

## 9. 새로 생긴 테스트 지원 파일

| 파일 | 역할 |
|---|---|
| `support/AuthMySqlSupport` | 인증 IT 공통 — cron 전면 차단, **독립 커넥션**으로 커밋된 행을 읽는 길 |
| `support/MutableClock` · `support/MutableClockConfig` | 유예·만료 경계를 정각에 찍기 위한 시계. `@Primary`로 `ClockConfig`를 이긴다 |
| `support/ConnectionCountingDataSourceConfig` | 커넥션 획득 수를 세는 래퍼 — JPA 아래에서 "저장소 0회"를 재는 지점 |

**`MutableClock`에는 함정이 하나 있다.** 시계를 만료 뒤로 밀면 그 뒤의 `parseRefreshToken`이
전부 거절된다 — 토큰 문자열에서 jti·계열을 꺼내는 헬퍼가 그것을 부르므로, 시계를 민 뒤에는
**미리 꺼내 둔 값으로만** 행을 읽어야 한다. 이 함정에 한 번 걸려
`폐기가_만료보다_우선한다`가 엉뚱한 예외로 실패했고, 그 실패가 마치 REVOKED/EXPIRED 우선순위가
뒤집힌 것처럼 보였다.
