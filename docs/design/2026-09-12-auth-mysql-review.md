# 인증 MySQL 전환과 통계 스케줄 분리 — 코드 검토

2026-09-12 · 이슈 #404 · 대상은 `feat/#404-auth-mysql-stats-schedules`의 작업 트리
(기준 `8b84d05`, 미추적 파일 포함).

> **처리 기록 (2026-09-12, 같은 날 후속 작업).** 1차 검토는 읽기 전용이었고, 그 뒤 별도 작업으로
> 아래 셋을 처리했다. 각 항목의 본문에 결과를 적어 두었다.
> - **2-1은 결함이 아니라 사용자가 명시적으로 고른 정책으로 확정됐다.** `revoked_reason`·상태 컬럼도
>   로그아웃 예외도 추가하지 않는다. 코드는 그대로 두고 **문서와 주석의 무조건적 서술만 정정**했다.
> - **2-2는 고쳤다.** 재발급 경로 전용 401 `AUTH-017` `REFRESH_TOKEN_USER_INACTIVE`를 새로 뒀다.
> - **2-3은 전환 순서 문서를 고쳤다.** 시크릿 본문 갱신을 1번 단계로 올리고 비밀 없는 키 예시를 붙였다.
>   실제 시크릿·배포는 건드리지 않았다.

**빌드도 테스트도 돌리지 않았다.** 아래의 "확인했다"는 전부 코드(필요한 곳은 라이브러리
바이트코드)를 읽은 결과이지 실행 결과가 아니다. 정당성을 실행으로 뒷받침하는 일은 검증 작업자의
몫이고, 7장이 그쪽에 넘기는 목록이다.

읽은 것: 설계 합의문(`2026-09-12-auth-mysql-and-stats-schedules.md`), 구현 기록 둘,
V41, 인증·어드민·통계·시큐리티의 main 변경 전부, 그리고 배포 경로를 보기 위해 CI/CD 워크플로.

---

## 1. 한눈에

수용 기준 대부분이 코드에 실제로 서 있다. 회전의 두 문장 구조, 잠금 순서, 커밋 후 401,
재구성의 결정성, 어드민 일회 소비, 스케줄 분리는 문서가 주장하는 대로 구현돼 있고 근거를
6장에 남겼다.

1차 검토가 짚은 것은 셋이었다. 하나는 **수용 기준 두 조항이 실제로 충돌하는 지점**이고(2-1),
하나는 **클라이언트 계약에 구멍**이며(2-2), 하나는 **이번 변경이 배포에 닿지 않는 경로**다(2-3).
셋 다 코드가 틀렸다기보다 "어느 쪽으로 할지 정해지지 않은 채로 굳었다"에 가까웠고,
후속 작업에서 2-1은 정책으로 확정하고 문서를 맞췄으며 2-2는 코드를, 2-3은 전환 순서를 고쳤다.

---

## 2. 짚은 것과 처리

### 2-1. 폐기의 출처를 묻지 않는 것이 정책이다 — 문서만 정정

> **처리: 정책 확정 + 문서 보완.** 사용자가 "이미 폐기된 토큰의 재제시는 사용자 전체 폐기"를
> 명시적으로 유지하기로 정했다. 사유 컬럼(`revoked_reason`)도 상태 컬럼도 로그아웃 예외도
> 추가하지 않는다. 아래 서술은 **결함 보고가 아니라 이 정책의 상한**을 적어 둔 것이고,
> 같은 내용이 `RefreshTokenService` 클래스 javadoc, `AuthService#logout`,
> `AuthController` 로그아웃 주석, 구현 기록 §2 "폐기의 출처를 묻지 않는 것이 정책이다"에 들어갔다.

`refresh_token.revoked_at`은 **누가 찍었는지를 남기지 않는다.** 로그아웃이 찍은 도장과
재사용 감지가 찍은 도장이 같은 컬럼이고, 상태 판정은 그 컬럼만 본다.

- `RefreshTokenRow#state` (`domain/auth/entity/RefreshTokenRow.java`) — `revoked_at`이 있으면 REVOKED
- `RefreshTokenService#classify` (`domain/auth/service/RefreshTokenService.java`) — `REVOKED, GRACE_ENDED -> revokeEverything`
- `RefreshTokenService#revokeEverything` — `revokeAllByUserId`로 그 사용자의 **모든 계열**을 끊는다

**시나리오 A — 로그아웃이 다른 기기를 끈다.**
사용자가 폰(계열 F1)과 태블릿(계열 F2)에 로그인해 있다. 폰에서 `DELETE /api/auth/logout`을
부르면 F1만 끊긴다(의도대로). 그런데 폰의 HTTP 클라이언트에 흔한 "401이면 refresh 한 번 재시도"
인터셉터가 있거나 로그아웃 직전에 보낸 refresh 요청이 아직 날아가는 중이면, 그 요청이 F1의
refresh를 들고 서버에 도착한다. 행은 REVOKED → `revokeEverything` → **F2까지 폐기**.
태블릿은 다음 재발급에서 401 `AUTH-013`을 받고 재로그인 화면으로 튕긴다.

**시나리오 B — 재가입 직후 새 세션이 죽는다.**
탈퇴하면 `UserWithdrawService#withdraw`(`domain/user/service/UserWithdrawService.java`)가
모든 계열을 폐기한다. 같은 소셜 계정으로 다시 가입하면 `SocialUserService#reactivateIfDeleted`가
**같은 user 행을 되살리고** 새 계열 F3가 열린다. 이때 옛 앱 인스턴스나 재시도 큐에 남아 있던
탈퇴 전 refresh가 한 번 도착하면, 그 행은 REVOKED이므로 방금 만들어진 F3까지 폐기된다 —
막 로그인한 사용자가 그 자리에서 로그아웃된다.

**두 조항이 만나는 자리였다.** 합의문이 두 가지를 동시에 말한다.

> 기존 DELETE /api/auth/logout은 유효 Access의 family ID로 **그 계열의** Refresh를 폐기한다.
> 유예가 끝난 회전 토큰 또는 **이미 폐기된 토큰**: 사용자의 모든 미만료·미폐기 Refresh를 폐기한다.

로그아웃이 계열 단위가 되기 전에는 "이미 폐기된 토큰"이 사실상 재사용·탈퇴뿐이라 두 조항이
만나지 않았다. 계열 로그아웃이 생기면서 **정상적인 로그아웃이 "이미 폐기된 토큰"을 만들어 내는
출처**가 됐고, 그 순간 두 조항이 같은 행 위에서 만난다.

**결정: 두 번째 조항이 이긴다.** 폐기된 토큰이 다시 관찰됐다는 사실 자체를 신호로 삼고, 정상
지연에서도 그 신호가 나올 수 있다는 것을 비용으로 받는다. 그래서 코드는 그대로다.
바뀐 것은 서술이고, 세 가지를 못 박았다.

1. **"그 계열만"은 로그아웃이라는 동작의 범위**이지, 그 뒤에 오는 요청까지 다른 기기가 면제된다는
   뜻이 아니다. 구현 기록 §2 표에서 "다른 기기는 산다"를 걷어냈다.
2. **"전체 폐기 커밋 뒤의 새 로그인은 허용된다"는 발급이 막히지 않는다는 뜻**이지, 그렇게 만든
   계열이 미래의 재사용 관찰에서 빠진다는 뜻이 아니다. 시나리오 B가 바로 그 오해가 깨지는 자리다.
3. **클라이언트 계약이 하나 늘었다.** 로그아웃을 부르는 순간 대기 중인 refresh 요청을 중단하고
   재시도 큐를 비우고 토큰을 버린다. 401 인터셉터가 자동 재발급을 한 번 더 시도하는 구성이면
   로그아웃 뒤에는 그 경로도 끈다. **이미 나간 요청은 서버가 회수하지 못하므로**, 그 한 건이
   남기는 전체 폐기가 이 정책이 감수하는 상한이다.

검토 시점에 적었던 대안(`revoked_reason` 컬럼으로 출처를 갈라 로그아웃만 예외 처리)은
**채택하지 않기로 확정됐다.** 다시 꺼낼 때는 이 문단이 그 결정을 뒤집는 자리다.

### 2-2. 탈퇴한 사용자의 재발급이 401이 아니라 404였다 — 고침

`RefreshTokenService#rotate`는 DB 상태를 분류하기 **전에** 사용자를 잠그고 삭제 여부를 본다.
검토 시점에는 그 분기가 발급 경로와 같은 메서드를 써서 404 `USER-001`을 내보냈다.
탈퇴한 사용자의 앱이 남은 refresh로 재발급을 시도하면 `AUTH-013`도 `AUTH-014`도 아니고 404였고,
클라이언트가 "401이면 저장된 토큰을 버리고 로그인 화면"을 구현하면 **404는 그 분기에 걸리지 않아
죽은 토큰을 든 채로 재시도를 반복한다.** 탈퇴한 사용자의 **모든** 재발급 시도가 이 길을 지난다.

> **처리: 재발급 경로 전용 401을 새로 뒀다.**
> - `ErrorCode.REFRESH_TOKEN_USER_INACTIVE` = **401 `AUTH-017`** (`global/exception/ErrorCode.java`,
>   AUTH-016 다음 줄)
> - `RefreshTokenService#rotate`가 부르는 사용자 확인을 `requireActiveUserForRotation`으로 갈랐다.
>   행이 없어도, 탈퇴했어도 `JwtTokenException(REFRESH_TOKEN_USER_INACTIVE)`다.
> - **발급 경로는 그대로 404다.** `requireActiveUser`(소셜 로그인·어드민 교환)는
>   `EntityNotFoundException(NOT_FOUND_USER)`를 유지한다 — 방금 외부 인증을 통과한 주체를 우리 쪽에서
>   찾지 못했다는 말이라 "사용자를 찾을 수 없다"가 그대로 맞다.
> - **판정 우선순위를 문서화했다.** 사용자 잠금이 상태 분류보다 앞이므로 탈퇴한 사용자의 refresh는
>   그 행이 폐기돼 있어도 `AUTH-013`이 아니라 `AUTH-017`이다. 탈퇴가 소프트 삭제와 전체 폐기를 한
>   트랜잭션에서 커밋하므로 그 시점에 다시 폐기할 것이 없고, 재사용 감지가 약해지지 않는다.
>   **재가입한 사용자는 이 분기에 걸리지 않는다** — 잠금이 성공하고 옛 계열의 폐기된 행은 그대로
>   `AUTH-013`이 되어 새 계열까지 폐기한다(2-1의 정책).
> - 사용자 잠금과 "재사용 판정은 값으로 돌려주고 커밋 뒤에 401" 구조는 손대지 않았다.

### 2-3. dev·prod의 yml은 GitHub Secret이 정본이라 이번 변경이 배포에 닿지 않았다 — 문서 보완

`.github/workflows/CI.yml:24-34`와 `CD.yml:25-34, 48-57`이 하는 일은 병합이 아니라 덮어쓰기다.

```python
p = pathlib.Path("src/main/resources/application-dev.yml")
p.write_text(os.environ["APPLICATION_DEV_YML"], encoding="utf-8")
```

작업 트리의 `application-dev.yml`·`application-prod.yml`에 새로 넣은 `jwt.issuer`·`audience`·
`clock-skew-seconds`와 `solply.auth.*` 블록은 **빌드 시점에 통째로 버려진다.** 실제로 배포되는
본문은 `APPLICATION_DEV_YML`·`APPLICATION_PROD_YML` 시크릿이고, 그 안을 이 저장소에서는 볼 수 없다.

구체적인 대가는 수명이다. 시크릿의 옛 본문이 예전 기본값을 들고 있으면
(`access-token-expire-time: ${JWT_ACCESS_TOKEN_EXPIRE_TIME:3600000}` 같은 모양),
환경변수를 갱신하지 않는 한 **Access 수명이 30분이 아니라 1시간, Refresh가 14일이 아니라 7일로
남는다.** 합의문의 "Access TTL 30분, Refresh TTL 14일"이 배포에서 성립하지 않는다.

구현 기록 §3은 환경변수 두 개와 EC2 compose 시크릿을 짚었지만 **yml 자체가 시크릿이라는 사실은
말하지 않는다.** 그 문장만 읽은 사람은 저장소의 yml을 고쳤으니 끝났다고 믿게 된다.

같은 이유로 통계 쪽 문서 §6의 "다른 환경의 yml은 이 저장소에서 볼 수 없으므로 배포 전에 각
환경에서 확인해야 한다"의 **확인 대상도 이 두 시크릿이다.** 시크릿 본문이 `count-cron`이나
`batch-max-attempts`·`batch-retry-delay`를 기본값과 다르게 고정해 두었다면,
`bookmark-delta-cron`·`review-count-*`·`bookmark-delta-*`를 시크릿에 함께 넣어야 두 축의 주기와
재시도가 의도대로 간다. 넣지 않으면 북마크 축만 조용히 기본값(:15, 3회·5초)으로 갈린다.

> **처리: 전환 순서를 다시 썼다.** 구현 기록 §3이 이제 다음과 같다.
> - 맨 앞에 **"저장소의 yml을 고치는 것만으로는 아무것도 배포되지 않는다"**를 워크플로 줄 번호와 함께
>   못 박았다.
> - 1번 단계가 **시크릿 본문 갱신**, 2번이 **환경변수 갱신**이고 "둘 다 해야 한다"를 붙였다.
>   V41 적용과 앱 배포가 그 뒤로 밀렸다.
> - **§3-1에 비밀 없는 키 예시**를 넣었다 — `jwt`의 수명·issuer·audience·clock-skew와
>   `solply.auth` 일곱 키, 그리고 통계 쪽 다섯 키. 서명 키 같은 비밀은 적지 않았고 "기존 줄은
>   건드리지 않는다"를 명시했다. 같은 `solply:` 최상위 키를 두 번 쓰면 뒤엣것만 남는다는 경고도
>   시크릿 본문에 그대로 적용된다고 적었다.
> - §10에도 "dev/prod에서 'yml이 있으면'의 그 yml은 작업 트리의 파일이 아니다"를 붙였다.
>
> **실제 시크릿 값을 읽거나 고치지 않았고 배포도 하지 않았다.** 시크릿 본문은 이 저장소에서 볼 수
> 없으므로 "무엇이 이미 들어 있는지"의 확인은 배포 담당자의 몫으로 남는다.

---

## 3. 경미한 것

- **형식 버전 분기가 도달할 수 없다.** — **문서 정정함.** 구현 기록 §4가 "옛 버전으로 저장된 행은
  재구성 대상에서 빠지고 `AUTH-015`가 나간다"고 적었지만, `JwtTokenProvider#validateFormatVersion`이
  파싱 단계에서 옛 `ver` 토큰을 `AUTH-009` `INVALID_TOKEN`으로 거절해 DB 판정까지 내려가지 않는다.
  §4에 **경로를 셋으로 가른 표**(파싱의 `AUTH-009` / `loadAndVerify`의 `AUTH-016` /
  `reissueWithinGrace`의 `AUTH-015`)를 넣고, 같은 내용을 `JwtTokenProvider` 클래스 javadoc과
  `reissueWithinGrace`의 버전 비교 주석에 달았다. 분기 자체는 "토큰이 주장한 판과 행에 적힌 판이
  갈리는 경우"의 안전장치로 그대로 둔다.
- **콜백이 실패하면 nonce 쿠키가 남는다.** `AdminAuthController#kakaoCallback`에서 예외가 나면
  쿠키를 지우는 줄에 닿지 않는다. state는 이미 소비돼 재생은 막히므로 위험은 아니고,
  다음 `/kakao` 호출이 같은 이름·경로로 덮어쓴다. 수명(state TTL)까지 죽은 값이 남을 뿐이다.
- **정리 배치가 빈 회차를 한 번 더 돈다.** `AuthTokenCleanupFacade#runInBatches`가
  `deleted < batchSize`로 끝내는데, refresh 쪽에서 `deleted`는 **행 수**이고 `batchSize`는
  **계열 수**다. 계열마다 최소 한 행이라 조기 종료는 생기지 않지만(확인함), 계열당 토큰이 많으면
  더 지울 것이 없어도 한 번 더 조회한다.
- **만료 토큰이라도 `ver`가 틀리면 만료로 보이지 않는다.** `parseAndValidate`가 버전을
  시각보다 먼저 본다. 클라이언트가 받는 코드가 `AUTH-004`가 아니라 `AUTH-009`가 되는데,
  둘 다 401이고 대응도 같아 실익은 없다.
- `TestService`의 `UserRole` import가 쓰이지 않았다 — **지웠다.**

---

## 4. 수용 기준 대조

| 합의 항목 | 판정 | 근거 |
|---|---|---|
| CAS 부모 표시 + 자식 INSERT가 한 트랜잭션, INSERT 실패 시 부모 롤백 | 충족 | `#rotate`의 `markRotated`와 `#issueChild`의 `insert`가 같은 `REQUIRES_NEW` 안 |
| CAS 0행에서 영속성 컨텍스트 없이 최신 행 재조회 | 충족 | JdbcTemplate 전용 `RefreshTokenRepository`, `#rotate` 4단계의 `classify(loadAndVerify(...))` |
| 발급·회전·계열 로그아웃·전체 폐기·탈퇴에 사용자 잠금 | 충족 | `#rotate`·`#revokeFamily`·`#issueNewFamily` · `UserWithdrawService#withdraw` · `TestService#withdrawUser` |
| 잠금 순서 사용자 → refresh 행 | 충족 | 위 다섯 곳 전부. 정리 배치는 users를 잡지 않아 순환 없음 |
| 탈퇴와 refresh 폐기를 같은 트랜잭션에서 커밋 | 충족 | `UserWithdrawService#withdraw` 하나의 `@Transactional` |
| 재사용 판정 커밋 후 401 | 충족 | `RotationResult` 값 반환 → `AuthService#refreshToken` |
| 유예 재구성에 쓰기 없음, 유예·만료 연장 없음 | 충족 | `#reissueWithinGrace` 본문에 UPDATE/INSERT 없음 |
| 유예 자식이 ACTIVE가 아니면 전용 401, 전체 폐기 아님 | 충족 | `#reissueWithinGrace` → `REFRESH_TOKEN_SUPERSEDED` |
| 자식 미존재·소유자/계열 불일치는 정합성 오류 | 충족 | `#reissueWithinGrace`의 `inconsistent(...)` → 500 `AUTH-016` |
| JWT 만료는 DB 재사용 처리로 내려가지 않음 | 충족 | `AuthService#refreshToken`이 파싱 먼저, 만료는 거기서 끝 |
| 폐기가 만료보다 우선 | 충족 | `RefreshTokenRow#state`의 판정 순서 |
| 계열 로그아웃 반복 호출 200, 인증 없으면 401 | 충족 | `revokeFamily`는 0행도 정상, `AuthService#logout` |
| 계열 로그아웃이 그 계열만 끊는다 | 충족 | `revokeFamily`가 `user_id + family_id`로 좁힌다 |
| 그 뒤에 오는 요청까지 다른 계열이 안전하다 | **정책상 아니다** | 2-1. 폐기의 출처를 묻지 않는 것이 확정된 정책이고, 문서·주석이 그렇게 적혀 있다 |
| 어드민 state/code 원자적 일회 소비와 만료 검사 | 충족 | 조건부 UPDATE 1행 승자, `AdminOAuthStateRepository#consume` · `AdminAuthCodeRepository#consume` |
| 어드민 권한을 발급 트랜잭션 안에서 재확인 | 충족 | `issueForAdmin` → `issueNewFamily`의 `requireAdmin` 분기 |
| oauth_nonce 쿠키 속성 유지 | 충족 | `AdminAuthController:50-56`, 지우는 쿠키도 같은 속성 |
| Refresh 쿠키·익명 세션·Access 블랙리스트 미추가 | 충족 | 추가된 쿠키 없음, `Refresh-Token` 헤더 그대로 |
| 일반 인증에서 DB·Redis 0회 | 충족 | 6-4 |
| 계열 단위 보존 정리, 살아 있는 계열 미삭제 | 충족 | 6-5 |
| Flyway 신규 마이그레이션 | 충족 | V41 (V40이 직전, 충돌 없음) |
| 환경별 설정과 전환 순서 문서화 | 충족 | 2-3의 처리 — 시크릿 갱신이 1번 단계, 키 예시는 §3-1 |
| 리뷰 :30 / 북마크 :15, 각자 cron·락·재시도·로그 | 충족 | 6-6 |
| 점수 01:00·안전망 01:45·집계 SQL·스냅샷 불변 | 충족 | 프로세서 파일 무변경, 기동 백필 `PlaceStatsFacade#backfillCounts` 그대로 |
| 파사드 트랜잭션 없음, 북마크 소비 한 트랜잭션 | 충족 | 파사드에 `@Transactional` 없음, 프로세서 무변경 |

---

## 5. 문서가 사실과 달랐던 곳 — 전부 정정함

| 어디 | 무엇이 틀렸나 | 지금 |
|---|---|---|
| 구현 기록 §2 표 | "로그아웃 범위: 다른 기기는 산다" | "이 호출이 끊는 범위"로 좁히고, §2에 "폐기의 출처를 묻지 않는 것이 정책이다" 절을 새로 넣었다 |
| 구현 기록 §2 오류 표 | 재발급 실패를 전부 401로 적었는데 404 `USER-001` 경로가 있었다 | 코드를 401 `AUTH-017`로 고쳐 표와 맞췄다. 표에도 `AUTH-017` 줄을 넣었다 |
| 구현 기록 §3 | 전환 순서에 시크릿 본문 갱신 단계가 없었다 | 1번 단계로 올리고 §3-1에 키 예시를 붙였다 |
| 구현 기록 §4 | "옛 버전 행은 `AUTH-015`" | 경로 셋을 가른 표로 바꿨다 — 옛 `ver` 토큰은 파싱에서 `AUTH-009` |
| 구현 기록 §6 분기표 | 비활성 사용자 분기가 없었다 | `AUTH-017` 줄과 판정 우선순위 설명을 넣었다 |
| `AuthService#logout`·`AuthController` 주석 | "다른 기기는 살아 있다" | 정책 상한과 클라이언트 계약을 적었다 |
| `RefreshTokenService` 클래스 javadoc | 재사용 관찰 정책이 적혀 있지 않았다 | "폐기의 출처를 묻지 않는 것이 정책이다" 절을 넣었다 |
| `JwtTokenProvider` 클래스 javadoc·`reissueWithinGrace` 주석 | 판 올림 시 옛 토큰이 걸리는 자리를 섞어 적었다 | 파싱 경로와 DB 경로를 갈라 적었다 |

---

## 6. 확인했고 문제를 찾지 못한 것

여기부터는 "괜찮아 보였다"가 아니라 **무엇을 근거로 괜찮다고 판단했는지**다. 뒤집을 근거가
나오면 그 근거를 반박하면 된다.

### 6-1. 재구성이 바이트까지 같을 조건이 실제로 서 있다

이 설계 전체가 "같은 값이면 같은 문자열"에 걸려 있으므로 라이브러리까지 열어 봤다.

- 발급·유예 재구성이 **같은 메서드** `serializeRefreshToken`을 지난다
  (`#issueNewFamily`·`#issueChild`·`#reissueWithinGrace`). 두 번째 경로가 따로 문자열을 만들지 않는다.
- 클레임 순서 = 빌더 호출 순서. jjwt 0.11.5의 `io.jsonwebtoken.impl.JwtMap`이 내부 맵을
  `LinkedHashMap`으로 만든다(바이트코드 확인). 키마다 한 번씩만 넣으므로 삽입 순서가 그대로
  JSON 키 순서가 된다.
- 시각은 정수 초. `JwtMap#setDate`가 `Date.getTime()/1000`을 **Long으로 raw 맵에 직접** 넣는다
  (바이트코드 확인). 밀리초가 끼어들 자리가 없다.
- 헤더는 `{"alg":"HS512"}` 하나다. 0.11.5는 `typ`을 자동으로 붙이지 않으므로 두 경로의 헤더가
  같다.
- DB 왕복이 값을 바꾸지 않는다. `issued_at`·`expires_at`은 BIGINT 정수 초,
  `family_id`·`jwt_id`는 길이 36의 UUID라 CHAR(36)의 트레일링 공백 절삭에 걸리지 않는다.

### 6-2. 정수 NumericDate 검증이 실제로 작동한다

`JwtTokenProvider#validateTimestamps`의 javadoc이 "JJWT는 문자열 날짜도 파싱하므로 여기서
거절한다"고 주장한다. 이 주장이 성립하려면 `claims.get("exp")`가 **파싱된 원값**을 돌려줘야 하는데,
`DefaultClaims#put`이 시각 클레임을 정규화할 가능성이 있어 확인했다.

`DefaultClaims#put`은 **값이 `Date`일 때만** 초로 접고, 그 외에는 그대로 상위 맵에 넣는다
(바이트코드 확인). 파싱 경로에서 오는 값은 Jackson이 만든 Integer·Long·String·Double이므로
`Date`가 아니고, 따라서 `JwtTokenProvider#integralValue`가 문자열·소수 표현을 실제로
거절한다. 주장이 사실이다.

같은 확인으로 반대쪽도 안전하다 — 정상 토큰의 `iat`/`exp`는 Integer 또는 Long으로 들어오고
`integralValue`가 둘 다 받는다. (2038년 이후 epoch 초가 Integer 범위를 넘어도 Long 분기가 받는다.)

### 6-3. CAS에 진 요청이 stale을 보지 않는다

세 가지가 함께 있어야 성립하고, 셋 다 있다.

1. `RefreshTokenService`의 모든 메서드가 `REQUIRES_NEW` + `READ_COMMITTED`라 바깥 트랜잭션에
   조용히 참여하지 않는다(`#issue`·`#issueForAdmin`·`#rotate`·`#revokeFamily`).
2. 행을 읽는 길이 JPA가 아니라 JdbcTemplate이라 1차 캐시가 없다(`RefreshTokenRepository` 전체).
3. 진 쪽은 `SELECT ... FOR UPDATE users`에서 이긴 쪽의 커밋까지 블록되므로, 잠금을 얻은 뒤의
   재조회는 이미 커밋된 자식을 본다(`#rotate` 2단계의 잠금 → 4단계의 재조회).

`classify`의 `case ACTIVE -> inconsistent`가 도달 가능한지도 따져 봤다. CAS 조건
(`rotated_at IS NULL AND revoked_at IS NULL AND expires_at > now`)과 상태 판정이 같은 술어를
쓰므로, CAS가 0행을 냈는데 재조회에서 ACTIVE가 나오려면 시계가 거꾸로 가야 한다. 주석의 서술이
맞다.

### 6-4. 인증 경로가 저장소를 읽지 않는다

`JwtAuthenticationFilter`는 `jwtTokenProvider`만 부른다. 확인한 것은 그 옆이다 —
main 전체에서 `PrincipalDetails#getUser()` 호출처가 **0건**이라(토큰 경로에서 `user`가 null이다)
숨은 NPE가 없고, `PrincipalDetailsService`를 부르는 곳도 없다.
`@CurrentUserId`·`@CurrentTokenFamilyId`·`@CurrentSocialLoginPlatform`은 전부 principal의
게터를 SpEL로 읽을 뿐이라 조회를 만들지 않는다.

`/api/auth/**`가 여전히 permitAll이므로 로그아웃의 401은 시큐리티가 아니라 `AuthService#logout`이
낸다. 미인증 요청에서는 두 애너테이션이 null을 주고, 만료·위조 access는 필터가 먼저 401로 끊는다.

### 6-5. 보존 정리가 살아 있는 계열을 지우지 않는다

고르는 조건이 `HAVING MAX(expires_at) < cutoff`(`RefreshTokenRepository#findExpiredFamilyIds`)라 선택된 계열에는
기준 시각 이후까지 사는 토큰이 하나도 없다. 삭제도 같은 술어를 다시 걸어(`#deleteExpiredTokensOfFamilies`) 두 문장이 같은
집합을 본다. 그 사이에 새 자식이 끼어들 수 없는 이유는 회전이 `expires_at > now`인 부모에서만
일어나기 때문이고, 선택된 계열의 모든 행은 `now - 30일`보다 먼저 만료됐다.

어드민 쪽은 소비가 `expires_at > now`, 정리가 `expires_at < now`라 집합이 겹치지 않는다.

### 6-6. 스케줄이 실제로 갈렸다

| 회차 | cron | ShedLock | 재시도 키 |
|---|---|---|---|
| 리뷰 카운트 | `count-cron` (:30) | `place-stats-count` | `review-count-*` |
| 북마크 델타 | `bookmark-delta-cron` (:15) | `place-stats-bookmark-delta` | `bookmark-delta-*` |
| 카운트 안전망 | `count-safety-cron` (01:45) | `place-stats-count-safety` | `batch-*` |
| 인기 점수 | `score-cron` (01:00) | `place-stats-score` | `batch-*` |
| 인증 정리 | `solply.auth.cleanup-cron` (04:40) | `auth-token-cleanup` | 없음(덩어리 반복) |

다섯 회차의 cron·락 이름이 전부 다르고 `zone`은 넷 모두 `Asia/Seoul`이다. 회차 전체 소요를
남기는 종료 로그(`PlaceStatsFacade#logRoundFinished`)는 매시 두 축에만 있고, 실패해도
`info`로 한 줄 남는다 — 합의문이 요구한 "시작/성공/실패/소요시간"을 매시 두 축에서 만족한다.
집계 SQL과 두 프로세서, 기동 백필(`recalculateCountsIfEmpty`)은 손대지 않았다.

`application.yml`·`-dev.yml`·`-prod.yml` 셋 다 최상위 `solply:` 키가 한 번씩만 나온다
(구현 기록 §10이 경고한 YAML 중복 덮어쓰기는 없다).

---

## 7. 검증 작업자에게 — 반드시 덮어야 할 것

합의문 "검증 기준"의 14항목 위에, 이번 검토에서 나온 것만 적는다.

1. **2-1의 정책을 값으로 고정하라.** 결정이 났으므로 테스트가 단언할 내용도 정해졌다 —
   로그아웃한 계열의 refresh를 한 번 더 보내면 `AUTH-013`이 나가고 **다른 계열까지 폐기된다.**
   탈퇴 → 재가입으로 새 계열을 연 뒤 옛 계열의 폐기된 토큰을 보내면 **새 계열까지 폐기된다.**
   둘 다 의도된 동작이므로 테스트가 없으면 다음 사람이 버그로 읽고 되돌린다.
   로그아웃 **직후**(후속 요청 없이) 다른 계열이 살아 있다는 것도 같은 자리에서 함께 단언한다 —
   그 둘이 갈린다는 것이 이 정책의 전부다.
2. **`AUTH-017` 경로를 단언하라.** 탈퇴한 사용자의 재발급이 401 `AUTH-017`인지, 같은 상황에서
   `AUTH-013`이 아닌지(비활성 사용자 판정이 상태 판정보다 앞이다), 그리고 **로그인·어드민 교환의
   없는 사용자는 그대로 404 `USER-001`인지.** 세 번째를 빠뜨리면 갈라 둔 의미가 없다.
3. **재구성 동일성은 문자열 비교가 아니라 바이트 비교로.** 6-1의 근거는 라이브러리 구현에
   기대고 있으므로, 라이브러리를 올리면 조용히 깨질 수 있는 종류다. 그 회귀를 잡는 것이 이
   테스트의 값어치다. DB 왕복(저장 → 다른 커넥션으로 읽기 → 재구성)을 반드시 사이에 넣을 것.
4. **CAS 0행 경로는 저절로 재현되지 않는다.** 두 커넥션의 분기용 SELECT를 먼저 각각 돌린 뒤
   잠금 획득을 어긋나게 해야 한다(구현 기록 §6의 한계 그대로). latch만 걸고 동시에 쏘면 사용자
   잠금이 직렬화해 1행 경로만 두 번 돈다.
5. **H2로는 이 경로를 못 본다.** V41의 `ENGINE=InnoDB`·`DEFAULT CHARSET=utf8mb4`와 저장소의
   `DELETE ... LIMIT :limit`·`SELECT ... FOR UPDATE`는 MySQL 전용이고,
   `application-test.yml`은 H2(MODE=PostgreSQL) + `flyway.enabled: false`다. refresh·어드민
   경로는 전부 MySQL 컨테이너 쪽이어야 한다.
6. **새 스케줄이 남의 테스트에서 발화하지 않게.** 통계 문서 9-3이 `bookmark-delta-cron`을 꺼야
   할 IT 11개를 짚었는데, **`solply.auth.cleanup-cron`은 그 목록에 없다.** 현재 이 키를 `"-"`로
   끄는 곳은 `support/AuthMySqlSupport.java` 하나뿐이다. 04:40을 지나는 스위트에서 정리
   배치가 깨어나면 다른 테스트가 깔아 둔 refresh·어드민 행을 지울 수 있다. 매시 도는
   `bookmark-delta-cron`보다 확률은 낮지만 실패했을 때 원인을 찾기는 더 어렵다.
7. **`PlaceStatsFacadeTest#logsStartBeforeInvokingProcessor`(:549)는 진입점 치환만으로 깨진다.**
   회차 종료 로그가 생겨 INFO가 두 줄이 되므로 `singleElement()`(:557)를 시작 줄을 고르는 단언으로
   바꿔야 한다. 통계 문서 9-1의 서술이 맞다(원본 확인함).
8. **전체 폐기가 실제로 커밋되는지는 다른 커넥션으로 읽어 확인해야 한다.** 같은 트랜잭션 안에서
   읽으면 롤백된 폐기도 보인다 — `RotationResult`를 값으로 돌려주는 설계가 막으려던 실패가
   바로 그것이므로, 테스트가 그 실패를 재현할 수 있는 모양이어야 한다.

---

## 8. 하지 않은 것

- **빌드·테스트 실행.** 1차 검토에서도, 후속 수정에서도 돌리지 않았다. 따라서 **어떤 것도
  "동작한다"로 주장하지 않는다.** `AUTH-017` 경로가 실제로 401을 내보내는지는 검증 작업자가
  실행으로 확인해야 한다.
- **`src/test/**`·`build.gradle` 수정.** 검증 작업자의 소유라 손대지 않았다.
- **시크릿 열람·수정, 배포, 커밋·푸시·PR.** 2-3은 문서 보완까지다.
- 현재 상태에서 컴파일되지 않는 기존 테스트의 확인은 호출처 조회까지다 —
  사라진 `recalculatePlaceCounts()`를 `PlaceStatsFacadeTest` 22줄·`PlaceStatsSchedulerLockIT` 3줄이
  참조하고, 사라진 `generateAccessToken(...)`을 `JwtAuthenticationFilterIT`가 4곳에서 부른다.
  두 구현 기록이 예고한 그대로다.

### 후속 수정에서 실제로 손댄 파일

| 파일 | 무엇을 |
|---|---|
| `global/exception/ErrorCode.java` | `REFRESH_TOKEN_USER_INACTIVE` (401 `AUTH-017`) 추가 |
| `domain/auth/service/RefreshTokenService.java` | `requireActiveUserForRotation` 분리, 클래스 javadoc에 재사용 관찰 정책, `rotate` javadoc에 판정 우선순위, 버전 분기 주석 |
| `domain/auth/service/AuthService.java` | `logout` javadoc — 정책 상한과 클라이언트 계약 |
| `domain/auth/controller/AuthController.java` | 로그아웃 주석 |
| `global/jwt/JwtTokenProvider.java` | 클래스 javadoc — 판 올림 시 옛 토큰이 걸리는 자리 |
| `domain/test/service/TestService.java` | 안 쓰는 `UserRole` import 제거 |
| `docs/design/2026-09-12-auth-mysql-implementation.md` | §2·§3·§3-1·§4·§6 분기표·§10·§11·§13 |
| `docs/design/2026-09-12-auth-mysql-and-stats-schedules.md` | 합의 해석 각주(로그아웃 범위·새 로그인 허용의 뜻) |
| `docs/design/2026-09-12-auth-mysql-review.md` | 이 파일 |
