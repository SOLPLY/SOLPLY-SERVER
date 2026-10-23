# 인증 MySQL 전환·통계 스케줄 분리 — 검증 감정

2026-09-12. 별도 작업자(읽기 전용)가 [요구사항 정본](2026-09-12-auth-mysql-and-stats-schedules.md)의
검증 기준 1~14를 실제 테스트 코드와 대조한 기록이다. **main도 테스트도 고치지 않았고 빌드·테스트를
돌리지 않았다** — 실행은 검증 작업자의 몫이고 이 문서는 그 실행이 무엇을 증명하게 되는지를 묻는다.

## 이 문서가 답하려는 질문

"테스트가 있다"와 "테스트가 그것을 증명한다"는 다른 말이다. 이름과 주석이 정확한 테스트일수록
그 차이가 안 보이는데, 이 스위트는 이름과 주석이 대단히 정확하다. 그래서 감정의 방식을 하나로
고정했다 — **단언을 뒤집어 보고, 뒤집어도 초록불이면 그 단언은 증명이 아니다.** 아래 "공허한
단언" 항목들이 전부 그 방식에서 나왔다.

## 결론 요약

| | |
|---|---|
| 실제 MySQL 강제 | **성립.** H2 대체·환경변수 skip 없음(아래 §1) |
| 검증 기준 1~14 | **11개 성립, 3개에 실증 구멍**(§2 표) |
| 남은 조치 | **5건** — B·C(공허한 단언), E(경계 미검증), F(쿠키 무검증), G(탈퇴 경로) |
| 감정 중 고쳐진 것 | 2건 — H(실행 순서 오염), D(테스트가 main과 반대) |
| 최종 통과 판정 | **불가.** 새 실행 결과가 없다(§5) |

## 1. 실제 MySQL이 강제되는가 — 성립

요구사항이 가장 강하게 못 박은 조항("H2로 동시성을 대체하지 않는다", "환경변수 부재로
skip하지 않는다")부터 봤다. 이것이 깨져 있으면 아래 전부가 무의미하다.

- `@Disabled`·`Assumptions`·`assumeTrue`·`@EnabledIf*`가 **테스트 트리 전체에 0건**이다.
  조건부로 조용히 빠지는 테스트가 없다.
- `src/test/resources/application-test.yml`에 H2 설정이 남아 있지만, 인증·경쟁 IT는
  `@ActiveProfiles("test")`를 붙이지 않고 `MySqlContainerSupport`를 상속한다. 데이터소스는
  `@DynamicPropertySource`(최우선순위)가 Testcontainers MySQL 8.0으로 덮고 Flyway를 켠다.
  그 H2 설정을 쓰는 것은 `SolplyServerApplicationTests`(컨텍스트 적재) 한 클래스뿐이다.
- 독립 커넥션이 `DriverManager`로 열린다(`AuthMySqlSupport#openIndependentConnection`).
  스프링도 하이버네이트도 모르는 세션이므로 **커밋된 것만 보인다** — "폐기됐다"와 "폐기가
  커밋됐다"를 가르는 단언들이 이 위에 서 있고, 그 구분이 이 설계의 핵심이라
  (`RotationResult` javadoc) 관찰 도구의 선택이 맞다.
- 경쟁은 `CyclicBarrier`/`CountDownLatch` + 스레드 풀이고, 각 스레드가 자기 `REQUIRES_NEW`
  트랜잭션을 연다. 프로덕션 코드에는 테스트용 장치가 없고 만남 지점은 스파이의 배선으로만
  만든다 — 이 방식이 맞다. 회전 경로에 latch를 심으면 검증 대상이 프로덕션이 아니게 된다.

### 특히 잘 잡은 것 — CAS 0행이 실재한다는 증거

`동시_회전은_자식_하나를_만들고_진_쪽도_같은_refresh를_받는다`가 `markRotated`의 반환값을
모아 `containsExactlyInAnyOrder(1, 0)`을 단언한다. 이것이 이 스위트에서 가장 값어치 있는 한 줄이다 —
사용자 잠금으로 정상 경로가 직렬화되기 때문에 **그냥 동시에 쏘면 0행 분기는 실행되지 않는다.**
barrier를 `lockUser` 앞(= 분기용 SELECT 뒤)에 둬서 두 스레드가 같은 ACTIVE를 본 뒤 잠금
획득만 어긋나게 만든 것이 정확하고, 그래서 "0행 경로가 코드에만 있는 분기가 아니다"가 값이 된다.

`CAS에_진_요청은_최신_부모를_다시_읽는다`는 겉보기에 `verify(times(3))`라는 약한 단언처럼
보이지만 실제로는 **격리 수준을 가른다.** MySQL의 REPEATABLE READ에서는 잠금 대기를 지난
뒤에도 비잠금 SELECT가 원래 읽기 뷰를 보므로, 진 쪽의 재조회가 ACTIVE를 돌려받아
`inconsistent()` 500으로 떨어진다. 즉 `READ_COMMITTED`를 빼면 이 테스트가 깨진다. 검증 기준 2의
"JPA stale / REPEATABLE READ 함정 방지"가 말로 남지 않고 실측으로 걸려 있다.

## 2. 검증 기준별 증거 대조

| 기준 | 증거 | 판정 |
|---|---|---|
| 1 자식 하나·패자 동일 refresh·전체 폐기 없음 | `RotationConcurrencyIT#동시_회전은_자식_하나를_만들고_진_쪽도_같은_refresh를_받는다` (CAS 1/0 기록, 자식 1행, 바이트 동일, 전 행 `revoked_at IS NULL`) | 성립 |
| 2 CAS 실패 최신 재조회·stale 방지 | `RotationConcurrencyIT#CAS에_진_요청은_최신_부모를_다시_읽는다` (위 §1) | 성립 |
| 3 시각 정밀도 왕복·헤더/클레임 고정·연장 없음 | `LifecycleIT#DB_왕복_후_바이트까지_같은_refresh_문자열이_나온다`(독립 커넥션 값만으로 재구성), `#헤더와_클레임의_순서가_고정돼_있다`(키 순서 `containsExactly`), `#유예_재발급은_부모의_유예도_자식의_만료도_연장하지_않는다`(행 집합 전체 동일) | 성립 |
| 4 시각 경계와 상태 우선순위 | 유예 종료 1ms 전/정각, DB 만료, JWT 만료 모두 성립. **REVOKED vs EXPIRED 우선순위만 공허**(→ B) | **부분** |
| 5 연속 회전·자식 상태·정합성 오류 | `LifecycleIT.Chain` 7건. 자식 회전/폐기/만료 → SUPERSEDED, 자식 부재·유예 시각 반쪽 → 500. 각 단언이 **"새로 폐기된 행 없음"**을 함께 본다(`assertSupersededAndNoNewRevocation`) — 오류 코드만 보면 조용히 전체 폐기가 끼어도 그린이라 이 짝이 필요하다 | 성립 |
| 6 재사용의 전체 폐기가 실제 커밋 | `LifecycleIT#유예가_끝난_부모로_다시_오면_모든_계열이_실제로_폐기된다` — 401을 받은 **뒤** 독립 커넥션으로 3행 전부 `revoked_at IS NOT NULL` 확인. 다른 사용자 불침범도 별건 | 성립 |
| 7 폐기 대 회전/발급의 양방향 순서 | 폐기 선행(→ 재사용, 자식 미생성), 회전 선행(→ 뒤이은 계열 폐기가 새 자식까지) 둘 다 성립. **폐기 후 새 로그인 허용만 공허**(→ C) | **부분** |
| 8 자식 INSERT 실패 시 부모 롤백 | `RotationConcurrencyIT#자식_INSERT가_실패하면_부모의_회전_표시도_되돌아간다` — 실패를 주입하지 않고 `ux_refresh_token_parent_jwt_id`로 만든다. 미끼를 치우면 다시 정상 회전하는 것까지 확인해 "롤백이 온전했다"를 값으로 만든다 | 성립 |
| 9 로그아웃·반복 로그아웃·다른 계열·재가입 | `LifecycleIT.LogoutAndWithdrawal` 7건 + `JwtAuthenticationFilterIT#로그아웃은_그_access를_낸_계열만_끊고_200을_돌려준다`. 해석 각주 5의 두 방향(직후 보존 / 지연 요청 도착 시 함께 폐기)이 각각 별 테스트다. **다만 탈퇴는 프로덕션 경로가 아니다**(→ G) | 성립(단서) |
| 10 어드민 임시 상태·동시 일회 소비 | `AdminTempStateConsumeIT` 8건. state·authCode 각각 동시 소비 1승, 만료 정각 경계, 교환 직전 권한 재확인까지 | 성립 |
| 11 북마크 반영/삭제 실패 시 둘 다 롤백 | `BookmarkDeltaAtomicRollbackIT` 2건. 실패 시점에 **트랜잭션 안에서 카운트를 읽어** 이미 바뀌어 있었음을 증거로 남긴다 — 이 한 줄이 없으면 "UPDATE가 애초에 안 나갔다"와 구분되지 않아 아무것도 안 하는 프로세서도 그린이다. 다음 회차가 정확히 한 번 소비하는 반대 방향도 별건 | 성립 |
| 12 두 cron/락/독립 재시도·로그·트랜잭션 경계·기존 IT 오발화 | `PlaceStatsScheduleSplitTest` 12건 + `PlaceStatsSchedulerLockIT`(실 ShedLock 4개 이름, 두 번째 호출 건너뜀) + `PlaceStatsFacadeTest#파사드에는_트랜잭션_어노테이션이_붙어_있지_않다`. 오발화는 §3 | 성립 |
| 13 Access 거절 목록·인증 저장소 0회·ADMIN 회귀 | `JwtTokenProviderTest` 20여 건(서명·HS512·iss·aud·type·필수 클레임 전수 루프·형식 버전 느슨한 표현·만료 정각·스큐 경계·정수 아닌 NumericDate), `JwtAuthenticationFilterIT`(SQL 0·커넥션 0·Redis 0, ADMIN 통과 / USER 403). 소유권 검사는 §4 | 성립 |
| 14 마이그레이션·보존 정리 | V41이 모든 MySQL 컨텍스트에서 Flyway로 적용되고 `AuthTokenCleanupIT`가 계열 단위 보존을 네 방향으로 문다. **전체 빌드는 미실행**(§5) | 성립(실행 대기) |

## 3. 새 스케줄이 기존 테스트에서 깨어나는가 — 성립

기준 12의 뒷부분은 회귀가 아니라 **환경 오염**을 묻는 항목이라 따로 셌다. `@SpringBootTest`로
앱을 실제로 띄우는 클래스를 전수 조사해 새 키 두 개(`bookmark-delta-cron`,
`auth.cleanup-cron`)의 차단 여부를 봤다.

- 인증 스위트 4종은 `AuthMySqlSupport`가 다섯 키를 한 자리에서 끈다.
- 기존 place 계열 부팅 IT 9종은 각자 두 키가 추가돼 있다(`PrincipalDetailsServiceIT`,
  `PlaceListFlowIT`, `PlaceListSqlCountIT`, 스냅샷 4종, `AdminPlaceUpdateSnapshotIT`,
  `BookmarkCountEventPublishIT`).
- 키를 안 끈 나머지(`PlaceStatsBatchProcessorIT`·`BookmarkCountDeltaProcessorIT`·
  `PlaceStatsRepositoryIT`·`BookmarkRepositoryIT`·`PlaceListDbQueryRepositoryIT`·
  `TownHierarchyResolverIT`)는 **전부 `@DataJpaTest` 슬라이스**다. `@Scheduled`가 등록되지도
  파사드 빈이 만들어지지도 않아 발화할 대상 자체가 없다. 차단 누락이 아니다.
- 남는 한 곳이 `SolplyServerApplicationTests`(`@SpringBootTest` + `test` 프로파일, H2)다.
  스케줄은 등록되고 cron은 안 꺼져 있다. 이번 변경이 만든 문제는 아니지만(`count-cron`은
  전부터 그랬다) **매시 발화 기회가 1회에서 2회로 늘었다** — 컨텍스트 적재 테스트라 창이
  밀리초 단위여서 위험은 낮다. 고칠 거리라기보다 알고 있을 값이다.

## 4. 자원 소유권 검사 — 구조로 닫혀 있다(전용 테스트 없음)

요구사항이 "기존 ADMIN 검사·자원 소유권 검사를 유지한다"고 했고 ADMIN 쪽은 테스트가 있다.
소유권 쪽은 **전용 회귀 테스트가 없는데, 그래도 된다고 판정했다.** 근거는 테스트가 아니라 구조다.

`PrincipalDetails`는 토큰 경로에서 `user` 필드가 `null`이 된다. 만약 소유권 검사가
`principal.getUser()`를 읽고 있었다면 그 전부가 NPE였을 것이다. 프로덕션 전체를 찾아보니
**`PrincipalDetails.getUser()` 호출처가 0건**이다 — 도메인 계층은 예외 없이
`@CurrentUserId`로 받은 `userId`를 `entityLoader.getUser(userId)`로 다시 읽는다. 그
`userId`가 클레임에서 정확히 옮겨진다는 것은 `JwtAuthenticationFilterIT`가 문다. 그래서 이
변경이 소유권 검사에 닿는 표면이 없다.

## 5. 최종 통과를 선언할 수 없는 이유

`AdminTempStateConsumeIT` 8건 통과가 실제 MySQL에서 관측됐다는 보고를 받았고, 그 클래스의
테스트 수가 정확히 8개로 일치하는 것까지 확인했다. **그 한 클래스 말고는 아무 실행 결과도 없다.**
다른 클래스가 통과할 것이라고 추론하지 않는다 — 특히 이 스위트는 싱글턴 컨테이너를 공유하며
커밋하는 IT가 많아 **클래스 실행 순서에 걸리는 실패가 실재한다**(아래 A가 그 실례다).
전체 빌드와 새 실행 결과가 나오기 전까지 이 문서의 판정은 "작성 품질에 대한 판정"이다.

## 6. 조치 목록

> **수정 진행 상태 (2026-09-12 16:47 실측).** 이 문서를 쓰는 동안 다른 작업자가 같은 파일을
> 고치고 있었다. 그래서 각 항목은 **그 시각의 파일을 다시 열어** 확인한 결과다.
>
> | 항목 | 16:47 상태 | 확인한 근거 |
> |---|---|---|
> | D 헤더 누락 응답 | **수정됨** | `isBadRequest()`로 뒤집혔다 |
> | H 실행 순서 오염 | **수정됨** | 양쪽 IT가 자기 픽스처를 지운다 |
> | B 폐기/만료 우선순위 | **미수정** | `RefreshTokenLifecycleIT:351`이 여전히 `expiresAtEpochSecond() - 1` |
> | C 전체 폐기 대 발급 | **미수정** | `RotationConcurrencyIT:307`이 여전히 `revokeFamily(...)` |
> | E 회전 트랜잭션 경계 | **보강 작성됨(미실행)** | §7 |
> | F `oauth_nonce` 쿠키 | **보강 작성됨(미실행)** | §7 |
> | G 탈퇴 프로덕션 경로 | **보강 작성됨(미실행)** | §7 |
>
> B·C는 coordinator가 high로 수정 지시했다고 통보받았으나 위 시각에는 아직 반영되지 않았다.
> **"수정 중"과 "수정됨"은 다르므로 이 표의 판정은 그 시각의 파일 내용뿐이다.**
>
> E·F·G는 같은 감정 작업자가 §7의 신규 파일 셋으로 보강했다. **아직 한 번도 실행되지 않았다** —
> 작성됨과 통과함은 다른 말이고, 실행은 검증 작업자의 몫이다.

### B. REVOKED가 EXPIRED보다 앞선다는 우선순위가 검증되지 않는다

`RefreshTokenLifecycleIT#폐기가_만료보다_우선한다`는 폐기 뒤 시계를
`expiresAtEpochSecond() - 1`에 둔다. **만료 조건이 아예 성립하지 않는 시점**이라 두 판정의
순서를 뒤집어도 그대로 통과한다. 주석은 "만료 시각까지 시계를 밀어도"라고 적혀 코드와 어긋난다.

시계를 `exp` 이상으로 밀면 두 조건이 동시에 참이 되어 순서가 실제로 갈린다. 그 시점
`revokeAllByUserId`는 `expires_at > now`라 0행을 바꾸지만 `ReuseDetected` 자체는 그대로이므로
단언은 성립한다(`revokedCount`가 아니라 타입을 보면 된다). `authService`가 아니라
`refreshTokenService.rotate`를 직접 불러야 한다 — JWT가 먼저 만료로 걸리기 때문이고,
그 방식은 같은 클래스의 `DB_만료는_폐기_없이_만료_오류다`가 이미 쓰고 있다.

### C. 전체 폐기 대 발급 경쟁이 계열 폐기로 대체돼 단언이 공허하다

`RotationConcurrencyIT#전체_폐기가_커밋된_뒤_잠금을_얻는_새_로그인은_살아남는다`는 이름과
주석이 "전체 폐기"라고 하지만 실제로 부르는 것은 `revokeFamily(옛 계열)`다. 새 로그인은
**다른 `family_id`**이므로 잠금 순서가 어떻게 뒤바뀌어도 그 행은 `revoked_at IS NULL`이다.
검증 기준 7이 요구한 "전체 폐기 대 발급의 직렬화"가 증명되지 않는다.

`revokeAllByUserId` 경로(재사용 감지를 일으키거나 저장소를 직접 호출)로 바꾸면 단언에 내용이
생긴다 — 폐기가 먼저 커밋됐기 때문에 살아남았다는 말이 되고, 잠금 순서가 뒤집히면 깨진다.
`revokeAllByUserId`와 `revokeFamily`는 SQL도 술어도 다른 문장이라 한쪽으로 다른 쪽을 대신할 수 없다.

### E. 회전 트랜잭션의 경계 선택이 검증되지 않는다

요구사항에 "외부 서비스의 기존 트랜잭션에 조용히 참여해 격리 수준이 무시되지 않게 경계를
분리한다"가 있고, 코드는 `REQUIRES_NEW` + `READ_COMMITTED`로 그것을 지킨다.
**그런데 `REQUIRED`로 바꿔도 지금 테스트는 전부 통과한다** — 바깥 트랜잭션 안에서 회전을
부르는 테스트가 하나도 없기 때문이다. 경쟁 IT가 격리 수준을 가르는 것(§1)은 바깥
트랜잭션이 없을 때의 이야기이고, 조용히 참여하는 회귀는 그 단언에 걸리지 않는다.

이 저장소에 방법이 이미 있다. `PlaceStatsBatchProcessorIT`의 `IsolationProbeConfig`가
`TransactionExecutionListener#afterBegin`에서 실제 격리 수준을 읽고, "프로세서를
`REQUIRES_NEW`로 바꾸면 이 단언이 깨진다"고 못 박아 뒀다. 인증 쪽에 같은 틀을 쓰면 된다.
그것이 무겁다면 `RefreshTokenService`의 메서드 애노테이션을 읽어 전파·격리를 단언하는
것만으로도 지금보다 낫다 — `PlaceStatsFacadeTest#파사드에는_트랜잭션_어노테이션이_붙어_있지_않다`가
같은 값의 단언이다.

### F. `oauth_nonce` 쿠키 속성에 테스트가 없다

요구사항이 "HttpOnly, Secure 설정, SameSite=Lax, 경로와 CORS 설정을 확인한다"고 적었다.
`AdminAuthController`의 구현은 정확하다 — `httpOnly(true)`, `secure`는 설정값(기본 켜짐),
`sameSite("Lax")`, 경로는 콜백으로 좁히고, 지우는 쿠키까지 속성을 맞췄다. **테스트는 0건이다**
(`oauth_nonce`·`HttpOnly`·`SameSite`를 테스트 트리에서 찾으면 아무것도 없다).

`AuthProperties#oauthNonceCookieSecure`의 javadoc은 "기본이 `true`인 것이 계약이다"라고
선언하는데, 계약이라면 누군가 그것을 물어야 한다. MockMvc로 `/api/admin/auth/kakao`를 불러
`Set-Cookie` 한 줄을 단언하면 네 속성이 한 번에 고정된다. 비용이 가장 싸고 지키는 것이 가장
큰 항목이다.

### G. 탈퇴는 프로덕션 경로가 아니라 재현된 불변식으로 검증된다

`LifecycleIT#withdrawInOneTransaction`은 `UserWithdrawService.withdraw`를 부르지 않고
같은 불변식(사용자 잠금 + 전체 폐기를 한 트랜잭션)을 테스트 안에서 다시 짠다. 주석이 이유를
밝혀 뒀고(리뷰·소셜정보 픽스처가 이 IT의 범위 밖) 그 판단은 합리적이다.

대가는 명확하다 — **`UserWithdrawService.withdraw`에서 `revokeAllByUserId` 한 줄을 지워도
어떤 테스트도 깨지지 않는다.** 그 한 줄이 "재가입으로 옛 계열이 살아나지 않는다"의 전부이므로
(같은 `user` 행이 되살아나는 구조다) 무방비로 두기에는 아깝다. 픽스처를 갖춘 탈퇴 IT를
따로 두거나, 최소한 `withdraw`가 그 저장소 메서드를 부른다는 것만이라도 한 줄 물어 두면
지금의 구멍이 닫힌다.

### 감정 중에 고쳐진 것 둘 — 기록으로만 남긴다

두 건 모두 감정 도중 coordinator에 보고했고, 현재 트리에서 수정된 것을 다시 확인했다.

#### D. 테스트가 main과 정반대를 단언하고 있었다

`JwtAuthenticationFilterIT`의 헤더 누락 테스트가 `isInternalServerError()`를 단언하고
javadoc이 "`GlobalExceptionHandler`에 `MissingRequestHeaderException` 핸들러가 없어 포괄
핸들러로 떨어진다"고 적은 시점에, `GlobalExceptionHandler:125`에는 이미 그 핸들러가
있었다(`MISSING_REQUIRED_PARAMETER`, 400 `COMMON-004`). 파일 수정 시각이
테스트(16:40:42) → 핸들러(16:41:46) 순이라, **두 작업자의 변경이 1분 차이로 엇갈리고
테스트가 갱신되지 않은 상태**였다. 지금은 `isBadRequest()`로 뒤집혀 있다.

지나가는 김에 남길 것 — 이 건은 "계약 구멍을 발견해 값으로 못 박는다"는 옳은 판단이
**한 걸음 늦게 반영된** 사례다. 구멍을 테스트로 고정하기 전에 그것이 아직 구멍인지
다시 보는 순서가 이 병렬 작업에서는 한 번 더 필요하다.

#### H. 실행 순서에 걸리던 오염

`AdminTempStateConsumeIT`가 `admin_oauth_state`를 지우지 않아 `expires_at = START-1`인 행이
남았고, `AdminOAuthStateRepository#deleteExpired`의 술어가 전역이라
`AuthTokenCleanupIT#어드민_임시_데이터는_만료된_것만_지운다`의 `isEqualTo(1)`이 2가 되어 깨졌다.
두 IT가 같은 고정 시계와 같은 컨테이너를 쓰기 때문에 생긴 일이다. 지금은 소비 쪽이 만든
state를 추적해 지우고, 정리 쪽도 두 임시 테이블을 회차마다 비운다.

**이 건이 §5의 근거다.** "다른 클래스도 통과할 것"이라는 추론이 왜 안 되는지를 실제로 보여 준
사건이고, 남은 클래스에 같은 모양이 또 있는지는 실행만이 답한다.

정리 쪽 수정이 두 임시 테이블을 회차마다 통째로 비우는 방식인 것은 짚어 둔다. 임시
데이터라 값을 기대하는 다른 IT가 없다는 주석의 판단이 맞고 지금은 옳게 돈다. 다만 이
방식은 **뒤에 그 테이블을 쓰는 IT가 생기면 그때 깨진다** — 소비 쪽이 자기 픽스처를
추적해 지우게 된 지금은 전역 삭제가 없어도 성립하므로, 둘 중 하나만 남길 자리가 있다.

### 사소한 것들

- `JwtAuthenticationFilterIT#ADMIN_클레임_토큰은...`이 `isNotEqualTo(403)`을 단언한다.
  500도 통과하므로 `isEqualTo(404)`가 정확하다(매핑 없는 경로를 고른 이유가 그 404다).
- 같은 IT의 커넥션 카운터·SQL 관찰자에 **자기 점검이 없다.** 관찰자가 배선되지 않으면
  0이 언제나 참이라 단언이 조용히 공허해진다. `startObserving()` 전에 일부러 DB를 한 번
  건드려 카운터가 0이 아님을 확인하는 한 줄이면 관찰자 자체가 살아 있음을 고정한다.
- `AuthTokenCleanupIT#덩어리_크기를_넘는_계열도_한_회차에서_모두_지운다`가 도는 것은
  테스트가 직접 짠 `do/while`이고, 파사드의 `runInBatches`(종료 조건 `deleted < batchSize`,
  상한 `cleanup-max-batches`와 그 경고)는 지나가지 않는다. 회차 전체 테스트는 덩어리가
  1,000이라 한 번에 끝나 반복이 일어나지 않는다. 이름이 "한 회차에서"라 실제보다 넓게 읽힌다.
- `JwtTokenProviderTest#asDate`는 `@SuppressWarnings("unused")`가 붙은 죽은 메서드인데
  javadoc이 "확인하는 보조"라고 적어 하는 일이 있는 것처럼 읽힌다. 지우는 쪽이 맞다.
- `AdminTempStateConsumeIT`의 동시 소비는 시작 게이트(latch) 방식이라 회전 IT처럼
  **0행 분기가 실제로 돌았다는 증거를 남기지 않는다.** 순차로 실행돼도 "한쪽만 성공"은
  성립하므로 결과 단언 자체는 옳지만, 회전 IT가 `casResults`로 한 것만큼 강하지는 않다.
  `consume`의 반환 행 수를 모아 `containsExactlyInAnyOrder(1, 0)`을 보면 같은 급이 된다.

## 7. E·F·G 보강 위치 (신규 파일 3개, 미실행)

기존 테스트·공유 픽스처·main·`build.gradle`은 건드리지 않았다. `AuthMySqlSupport`·
`MySqlContainerSupport`·`MutableClockConfig`를 그대로 상속/재사용한다.

| 항목 | 파일 | 무엇이 깨지면 잡히는가 |
|---|---|---|
| E | `domain/auth/service/AuthTransactionBoundaryIT` | `REQUIRES_NEW`→`REQUIRED`, `READ_COMMITTED` 제거, 폐기가 호출자 롤백에 휩쓸림 |
| F | `domain/admin/auth/AdminNonceCookieTest` | `HttpOnly`·`Secure`·`SameSite`·`Path` 누락, Secure 기본값 뒤집힘, 삭제 쿠키 속성 불일치 |
| G | `domain/user/service/UserWithdrawRefreshRevocationIT` | `withdraw`에서 `revokeAllByUserId` 제거, 소프트 삭제와 폐기가 다른 트랜잭션으로 갈림 |

### E — 애노테이션을 읽지 않는다

바깥에 트랜잭션이 **없을 때**를 보는 경쟁 IT로는 이 계약을 검증할 수 없다. 참여는 바깥이
있을 때만 일어나므로, 바깥 없는 테스트는 `REQUIRED`로 바꿔도 전부 통과한다. 그래서 네 테스트
모두 `TransactionTemplate`으로 **바깥 트랜잭션을 열고** 그 안에서 회전을 부른다.

중심은 `외부_REPEATABLE_READ_안에서도_회전은_자기_READ_COMMITTED_트랜잭션을_연다`다.
`TransactionExecutionListener.afterBegin`에서 실제 `@@transaction_isolation`을 읽어 단언이
`[바깥 : REPEATABLE-READ, 회전 : READ-COMMITTED]`가 된다 — 한 단언이 세 가지를 가른다:
회전이 **begin했다**(참여가 아니다), 그 트랜잭션이 **RC다**, 그리고 **같은 장치가 바깥을 RR로
읽었으므로 관측이 실제로 구분한다**. 내가 §"사소한 것들"에서 지적한 "관찰자 자기 점검 없음"을
이 파일에서는 구조로 막았다.

나머지 셋은 커밋 경계를 본다 — 바깥 롤백 뒤에도 회전이 남는가, 바깥이 살아 있는 동안 제3의
커넥션이 자식을 보는가(= 다른 트랜잭션·다른 커넥션이다), 그리고 **재사용 폐기가 바깥 롤백을
타지 않는가.** 마지막 것이 보안상 핵심이다: 호출자가 트랜잭션을 열고 있다가 롤백하면
`RotationResult`가 막으려던 실패("폐기는 롤백되고 401만 나간다")가 그대로 재현된다.

> **겹침 알림.** 이 파일을 쓰는 동안 다른 작업자가 `RefreshTokenRotationConcurrencyIT`에
> `회전은_바깥_트랜잭션이_롤백돼도_자기_커밋을_지킨다`와
> `상태를_바꾸는_메서드는_전부_REQUIRES_NEW_READ_COMMITTED다`를 추가했다. 앞쪽은 이 파일의
> 롤백 테스트와 겹치고, 뒤쪽은 애노테이션 리플렉션이라 **참여 시 격리가 버려지는 문제는 잡지
> 못한다**(전파 변경은 잡는다). 어느 쪽을 남길지는 coordinator가 정할 일이라 그 파일은 건드리지
> 않았다.

### F — 외부 OAuth 호출 없이 헤더 한 줄만 본다

`AdminAuthService`를 목으로 두고 컨트롤러를 직접 불러 `Set-Cookie`를 파싱한다. 카카오에
나가지 않고 스프링 컨텍스트도 필요 없다.

삭제 쿠키는 리터럴을 다시 적지 않고 **발급 쿠키와 맞대어** 본다 — 수명(`Max-Age`·`Expires`)과
값을 뺀 속성 집합이 같아야 한다는 것이 실제 계약이기 때문이다(하나라도 다르면 브라우저가
덮어쓰지 못하고 옛 nonce가 남는다). 리터럴을 두 번 적으면 둘이 함께 틀려도 초록불이다.

### G — 저장소 메서드로 재현하지 않는다

실제 `UserWithdrawService.withdraw`를 부른다. 소프트 삭제와 전체 폐기가 같은 커밋에 있다는
것을 독립 커넥션으로 확인하고, 다른 사용자 불침범과 **재활성 후에도 옛 계열이 죽은 채임**을
본다(`authService.refreshToken` → `REFRESH_TOKEN_REUSE_DETECTED`).

반대 방향이 같은 트랜잭션의 증거다 — `revokeAllByUserId`를 스파이로 터뜨리면 **소프트 삭제와
`user_withdraws` 행까지 함께 되돌아간다.** 따로 커밋하는 구조로 바뀌면 "삭제는 됐는데 계열은
살아 있다"가 남아 이 단언이 깨진다.

탈퇴가 nickname을 `탈퇴회원_<id>`로 바꾸므로 뒷정리는 접두어가 아니라 **id**로 한다.
`user_withdraws`가 `users`를 FK로 참조하므로 삭제 순서가 계약이다.

### 실행이 필요하다

세 파일 모두 **작성만 됐고 한 번도 돌지 않았다.** 실행해야 할 클래스:

```
org.sopt.solply_server.domain.auth.service.AuthTransactionBoundaryIT
org.sopt.solply_server.domain.admin.auth.AdminNonceCookieTest
org.sopt.solply_server.domain.user.service.UserWithdrawRefreshRevocationIT
```

앞의 둘째를 뺀 두 개는 Testcontainers MySQL을 쓰고 커밋하므로, §5의 이유 그대로
**전체 스위트와 함께 한 번 더** 돌려 실행 순서 오염이 없는지 봐야 한다. 특히
`AuthTransactionBoundaryIT`가 컨텍스트를 하나 더 만들므로(리스너를 붙인 `@TestConfiguration`)
커넥션 풀 총량이 늘어난다 — `MySqlContainerSupport`가 풀을 4로 묶어 둔 이유가 그것이다.

## 8. 감정하지 않은 것

- **테스트·빌드 실행.** 이 작업자는 Gradle을 한 번도 돌리지 않았다. §7의 신규 파일 셋은
  **컴파일조차 확인되지 않았다** — 실행 소유는 검증 작업자에게 있다.
- **main 수정.** 감정에서도 보강에서도 프로덕션 코드는 건드리지 않았다. 기존 테스트·공유
  픽스처·`build.gradle`도 그대로다.
- **`AdminTempStateConsumeIT` 8건 외의 통과 여부.** §5.
- 추천 캐시·Redis 인프라, 기존 집계 SQL과 스냅샷 리빌딩(요구사항이 "바꾸지 않는다"로 둔 범위).
