# 인증 MySQL 전환 — 구현 기록

2026-09-12. 설계는 [2026-09-12-auth-mysql-and-stats-schedules.md](./2026-09-12-auth-mysql-and-stats-schedules.md),
계획의 Task 1. **테스트는 작성하지도 실행하지도 않았고 빌드도 돌리지 않았다** — 검증은 후속 작업자의 책임이다.

---

## 1. 무엇이 바뀌었나

인증이 쓰던 Redis 키 세 종류가 MySQL 테이블 세 개가 됐다. 추천 캐시(`recommend:place:*`)는 그대로라
Redis 인프라 자체는 남는다.

| 옛 저장소 | 새 테이블 | 무엇이 달라졌나 |
|---|---|---|
| `String.valueOf(userId)` → refresh 원문 (유저당 1개) | `refresh_token` | 계열(로그인 1회)당 행, 회전 이력이 남는다. 두 번째 기기 로그인이 첫 기기를 끊지 않는다. |
| `admin:oauth:state:{uuid}` → nonce | `admin_oauth_state` | `GETDEL` → 조건부 UPDATE |
| `admin:auth:code:{uuid}` → `"{userId}:{PLATFORM}"` | `admin_auth_code` | 같음 |

옮긴 이유는 속도가 아니라 트랜잭션이다. 회전은 "부모를 회전됨으로 표시"와 "자식을 만든다"가 한 덩어리여야
하는데, Redis 쓰기는 DB 트랜잭션 밖이라 자식 생성이 실패해도 부모는 이미 무효가 됐다.

---

## 2. 클라이언트가 보는 변화

### 바뀌지 않은 것

- 엔드포인트·요청 모양 전부. refresh는 여전히 `Refresh-Token` 헤더(Bearer 아님), 응답 DTO도 그대로다.
- 일반 인증은 `Authorization: Bearer <access>`.
- 어드민 state/nonce → 일회용 authCode 교환 흐름.
- Refresh 쿠키·익명 세션·Access 블랙리스트는 **추가하지 않았다.** `oauth_nonce` 쿠키만 그대로다.

### 바뀐 것

| 항목 | 전 | 후 |
|---|---|---|
| Access 수명 | 1시간 | **30분** |
| Refresh 수명 | 7일 | **14일** |
| 기존 토큰 | — | **전부 거절된다.** 새 필수 클레임이 없다. |
| 로그아웃 범위 | 사용자의 refresh 하나(=전부) | **그 access를 낸 계열만** 끊는다. 다만 그것은 이 호출이 끊는 범위이지 다른 기기가 그 뒤로도 안전하다는 보장이 아니다 — 아래 "폐기의 출처" 참고. |
| 인증 없는 로그아웃 | 200 | **401** (`AUTH-001`) |
| `Refresh-Token` 헤더 누락 | 500 (`COMMON-008`) | **400** (`COMMON-004`) |
| refresh 불일치 | `AUTH-005` 하나 | 아래 다섯 가지로 갈린다 |

헤더 누락이 500이던 것은 이 전환이 만든 게 아니라 이 엔드포인트가 처음부터 갖고 있던 성질이다.
`@RequestHeader("Refresh-Token")`이 필수라 스프링이 `MissingRequestHeaderException`을 던지는데
`GlobalExceptionHandler`에 그 타입의 핸들러가 없어 포괄 핸들러로 떨어졌다. 클라이언트가 요청을 잘못 만든
사건이 서버 장애로 보이면 재시도할지 고칠지 판단할 수 없으므로, 같은 성질의 `MissingServletRequestParameterException`과
같은 코드(`COMMON-004`)로 맞췄다. 헤더 **이름만** 로그에 남긴다 — 값은 토큰이다.
`Refresh-Token`을 필수로 두는 계약 자체는 그대로고, 값이 있는 요청의 동작도 바뀌지 않았다.

새 오류 코드:

| 코드 | 상태 | 언제 |
|---|---|---|
| `AUTH-013` `REFRESH_TOKEN_REUSE_DETECTED` | 401 | 유예가 끝난 회전 토큰 또는 이미 폐기된 토큰. **그 사용자의 refresh를 전부 폐기한 뒤** 내보낸다. |
| `AUTH-014` `REFRESH_TOKEN_NOT_FOUND` | 401 | 서명은 맞는데 행이 없다(보존 기간 경과·옛 배포). 폐기할 계열조차 특정할 수 없다. |
| `AUTH-015` `REFRESH_TOKEN_SUPERSEDED` | 401 | 유예 중인 부모로 왔는데 그 자식이 이미 회전·폐기·만료됐다. **이것만으로 전체 폐기하지 않는다.** |
| `AUTH-016` `REFRESH_TOKEN_STATE_INCONSISTENT` | 500 | 있을 수 없는 조합. 재사용으로 단정하지 않는다 — 공격의 증거가 아니라 우리가 깨졌다는 증거다. |
| `AUTH-017` `REFRESH_TOKEN_USER_INACTIVE` | 401 | 서명은 맞는데 그 주인이 탈퇴했거나 행이 없다. **재발급 경로 전용**이고, 조회 API의 `USER-001`(404)과 다른 사건이다. |

`AUTH-013`을 받은 클라이언트가 할 수 있는 일은 재로그인뿐이다. `AUTH-015`는 **최신 토큰으로 다시 시도**다.
`AUTH-017`도 재로그인이다.

**재발급 실패는 전부 401이다.** `AUTH-017`을 따로 둔 이유가 그것이다 — 탈퇴한 사용자의 재발급이 404
`USER-001`로 나가면 "401이면 저장된 토큰을 버리고 로그인 화면으로"라는 클라이언트 규칙에 걸리지 않아
죽은 토큰으로 재시도를 반복한다. 로그인·어드민 교환 경로는 그대로 404 `USER-001`이다.

### 폐기의 출처를 묻지 않는 것이 정책이다

`refresh_token.revoked_at`은 **누가 찍었는지를 남기지 않는다.** 로그아웃·탈퇴·재사용 감지가 같은 컬럼에
같은 도장을 찍고, 상태 판정은 그 컬럼만 본다. 그래서 **이미 폐기된 토큰으로 들어온 재발급은 출처와
무관하게 전체 폐기**다. 사유 컬럼이나 로그아웃 예외는 두지 않는다 — 폐기된 토큰이 다시 관찰됐다는 사실
자체를 신호로 삼고, 정상 지연에서도 그 신호가 나올 수 있다는 것을 비용으로 받는 선택이다.

세 가지 결과가 여기서 따라온다.

- **로그아웃한 계열의 refresh가 한 번 더 도착하면 그 사용자의 모든 계열이 끊긴다.** 계열 로그아웃의
  "그 계열만"은 로그아웃이라는 동작의 범위이지, 그 뒤에 오는 요청까지 다른 기기가 면제된다는 뜻이 아니다.
- **탈퇴 후 재가입으로 막 열린 새 계열도 같다.** 같은 user 행이 되살아나므로
  (`SocialUserService#reactivateIfDeleted`), 옛 계열의 폐기된 토큰이 한 번 도착하면 새 계열까지 간다.
- **"전체 폐기 커밋 뒤의 새 로그인은 허용된다"는 이 면역과 다른 말이다.** 그것은 잠금으로 직렬화된 뒤의
  *발급*이 막히지 않는다는 뜻일 뿐, 새 계열이 *미래의 재사용 관찰*에서 빠진다는 뜻이 아니다.

### 클라이언트가 지켜야 할 것

**refresh 요청은 하나씩.** 서버는 응답 도착 순서를 보장하지 않으므로, 늦게 도착한 옛 응답으로 최신 토큰을
덮어쓰면 다음 회차가 옛 토큰을 보내게 되고 유예(3초)가 지나 있으면 그것이 재사용 판정이다.
회전 응답을 받으면 **받은 쌍을 즉시, 원자적으로** 교체할 것.

**로그아웃을 부르는 순간 refresh를 멈춰라.** 대기 중인 refresh 요청을 취소하고, 재시도 큐를 비우고,
저장된 토큰 쌍을 버린다. 401 인터셉터가 자동으로 재발급을 한 번 더 시도하는 구성이라면 로그아웃 뒤에는
그 경로를 끄는 것까지 포함한다. **이미 네트워크로 나간 요청은 서버가 회수해 주지 못하므로**, 그 한 건이
남기는 전체 폐기는 이 정책이 감수하는 상한이다.

---

## 3. 전환 순서

> **저장소의 `application-dev.yml`·`application-prod.yml`을 고치는 것만으로는 아무것도 배포되지 않는다.**
> `.github/workflows/CI.yml:24-34`와 `CD.yml:25-34, 48-57`이 두 파일을 시크릿 본문으로 **덮어쓴다**
> (`p.write_text(os.environ["APPLICATION_DEV_YML"])` — 병합이 아니다). 배포되는 정본은
> `APPLICATION_DEV_YML`·`APPLICATION_PROD_YML` 시크릿이고, 작업 트리의 두 파일은 로컬에서 읽히는
> 사본일 뿐이다. 아래 1번이 그래서 맨 앞에 있다.

1. **시크릿 본문 갱신** — `APPLICATION_DEV_YML`·`APPLICATION_PROD_YML`에 아래 3-1의 키를 넣는다.
   넣지 않으면 `solply.auth` 블록이 통째로 없는 상태로 배포되고(코드 기본값으로 동작하므로 부팅은 된다),
   `jwt` 블록이 옛 본문 그대로면 **수명이 30분/14일로 바뀌지 않는다.**
2. **환경변수 갱신** — 시크릿의 `jwt` 블록이 `${JWT_ACCESS_TOKEN_EXPIRE_TIME:...}` 모양이면 배포 환경의
   `JWT_ACCESS_TOKEN_EXPIRE_TIME` / `JWT_REFRESH_TOKEN_EXPIRE_TIME`도 `1800000` / `1209600000`으로 갱신한다.
   **환경변수가 옛 값을 들고 있으면 그쪽이 이긴다** — yml의 기본값은 변수가 없을 때만 쓰인다.
   (EC2 compose 원본도 GitHub 시크릿이라 로컬만 고쳐서는 반영되지 않는다.)
   1번과 2번은 **둘 다** 해야 한다. 어느 한쪽만 하면 옛 수명이 남는다.
3. V41 마이그레이션 적용 (새 테이블 3개, 기존 데이터 변경 없음).
4. 앱 배포. **배포 즉시 모든 사용자가 로그아웃된다.**

### 3-1. 시크릿 본문에 넣을 키

비밀은 여기 적지 않는다 — 아래는 **시크릿의 기존 본문에 더할 부분만**이고, 키 이름과 값 모양뿐이다.
`access-secret-key`·`refresh-secret-key`를 비롯한 기존 줄은 건드리지 않는다.

`jwt:` 블록에 더할 것(이미 있으면 값만 확인):

```yaml
jwt:
  # 2026-09-12: 30분/14일이 새 기본값이다. 환경변수가 옛 값(3600000/604800000)을 들고 있으면
  # 그쪽이 이긴다 — 2번 단계를 함께 할 것.
  access-token-expire-time: ${JWT_ACCESS_TOKEN_EXPIRE_TIME:1800000}
  refresh-token-expire-time: ${JWT_REFRESH_TOKEN_EXPIRE_TIME:1209600000}
  # 새 필수 클레임. 환경마다 다르게 두면 그 환경의 토큰이 다른 환경에서 거절된다.
  issuer: ${JWT_ISSUER:solply-server}
  audience: ${JWT_AUDIENCE:solply-app}
  clock-skew-seconds: ${JWT_CLOCK_SKEW_SECONDS:30}
```

`solply:` 블록에 더할 것 — **시크릿 본문에 이미 `solply:`가 있으면 그 아래로 병합한다.**
YAML은 같은 최상위 키가 두 번 나오면 조용히 뒤엣것만 남긴다:

```yaml
solply:
  auth:
    rotation-grace: ${AUTH_ROTATION_GRACE:3s}
    refresh-retention: ${AUTH_REFRESH_RETENTION:30d}
    admin-state-ttl: ${AUTH_ADMIN_STATE_TTL:10m}
    admin-auth-code-ttl: ${AUTH_ADMIN_AUTH_CODE_TTL:5m}
    cleanup-cron: ${AUTH_CLEANUP_CRON:0 40 4 * * *}
    cleanup-batch-size: ${AUTH_CLEANUP_BATCH_SIZE:1000}
    cleanup-max-batches: ${AUTH_CLEANUP_MAX_BATCHES:50}
    # https로 서비스되는 환경은 켜 둔다. 끄는 자리는 http로 도는 로컬뿐이다.
    oauth-nonce-cookie-secure: ${AUTH_OAUTH_NONCE_COOKIE_SECURE:true}
```

**통계 쪽도 같은 시크릿에 있다.** 시크릿 본문의 `solply.place-stats`가 `count-cron`이나
`batch-max-attempts`·`batch-retry-delay`를 기본값과 다르게 고정해 두었다면, 2026-09-12 분리로 그
값들의 적용 범위가 좁아졌으므로 아래 네 키를 함께 넣어야 두 축이 의도대로 간다
(상세는 [stats 문서](./2026-09-12-stats-schedule-split.md) 5·6장):

```yaml
solply:
  place-stats:
    bookmark-delta-cron: "0 15 * * * *"   # 신규. 없으면 매시 :15로 돈다
    review-count-max-attempts: 3
    review-count-retry-delay: 5s
    bookmark-delta-max-attempts: 3
    bookmark-delta-retry-delay: 5s
```

시크릿 본문이 그 키들을 **고정하지 않았다면 아무것도 할 필요가 없다** — 코드 기본값이 같은 값이다.
시크릿 내용을 이 저장소에서 볼 수 없으므로, 확인은 배포 담당자가 시크릿 편집 화면에서 해야 한다.

**무효가 되는 것은 토큰이지 클라이언트가 아니다.** 둘을 섞으면 안 된다.

- **옛 토큰은 전부 거절된다.** `iss`·`aud`·`fid`·`ver`(refresh는 `jti`까지)가 없어 파싱 단계에서 걸린다.
  Redis에 남은 refresh도 이관하지 않았으므로, 배포 시점에 살아 있던 세션은 전부 끊긴다.
- **클라이언트 앱은 그대로 쓸 수 있다.** 엔드포인트·헤더·요청/응답 DTO가 하나도 바뀌지 않았으므로, 이미
  배포된 모바일·어드민 앱도 **다시 로그인하면** 정상 동작한다. 배포 직후 기존 토큰으로 보낸 요청이 401을
  받는 것이지, 앱 버전이 낮아서 막히는 것이 아니다.
- 그래서 "구버전·신버전 앱이 섞여 도는 기간"은 호환 문제가 아니라 **재로그인 유도 UX 문제**다. 클라이언트는
  401(`AUTH-013`~`AUTH-015`·`AUTH-017` 포함)을 받으면 저장된 토큰을 버리고 로그인 화면으로 보내야 한다.

**롤백은 대칭이 아니고, 인증 정책의 일관성을 보장하지도 않는다.** 서버를 되돌렸을 때 새 Access가 옛 서버에서
거절된다고 단정할 수 없다 — 서명 키가 같고 옛 파서는 `iss`·`aud`·`fid`·`ver`를 읽지 않으므로, 추가 클레임을
무시하고 그대로 받아들일 수 있다(확인하지 않았다). 그 경우 옛 서버는 회전·재사용 감지·계열 로그아웃이
없는 규칙으로 그 토큰을 계속 받는다. 즉 롤백은 "토큰이 자동으로 무효가 되는" 절차가 아니라 **별도의 운영
절차**이며, 정책을 확실히 끊으려면 **서명 키 교체 + 전원 재로그인**이 함께 가야 한다.
`refresh_token`·`admin_*` 테이블은 남겨 두어도 무해하다(옛 코드가 읽지 않는다).

---

## 4. JWT 계약

### 클레임 (순서가 계약이다)

```
access  : iss, aud, sub, type="access",  platform, role, fid, ver, iat, exp
refresh : iss, aud, sub, jti, type="refresh", platform, fid, ver, iat, exp
```

서명은 둘 다 **HS512**, 키는 여전히 access/refresh로 분리. `iat`/`exp`는 **정수 초**다.

검증(`JwtTokenProvider#parseAndValidate`)이 거는 것:

| 대상 | 규칙 |
|---|---|
| 서명 | 종류별 키로 검증 |
| 헤더 `alg` | **HS512와 정확히 일치.** 파서에 키를 주면 "서명 없음"은 막히지만 같은 키로 HS256이라 주장하는 토큰은 통과한다 |
| `iss` · `aud` · `type` | 설정값·종류와 정확히 일치 |
| `ver` | **access·refresh 모두 필수**, `TOKEN_FORMAT_VERSION`(현재 `1`)과 정확히 일치 |
| `iat` · `exp` | 정수 NumericDate여야 한다(소수·문자열 표현 거절), **`exp > iat`** |
| 만료 | **`exp <= now`면 거절. 스큐를 주지 않는다** |
| 미래 발급 | `iat <= now + clock-skew-seconds` |
| `sub` | long으로 파싱되고 **양수** |
| `platform` · `role` · `fid` | 비어 있지 않은 문자열이고 enum으로 해석 가능 (refresh는 `role` 없음, `jti` 필수) |

**만료에 스큐를 주지 않는 것이 계약이다.** JJWT 0.11.5의 만료 검사는 `now − skew > exp`일 때만 예외를
던져서 `exp == now`와 스큐 안의 만료 토큰을 통과시킨다. 그 관대함은 refresh 행의 상태 판정
(`expires_at <= now`면 EXPIRED)과 어긋나 같은 토큰이 JWT 단계는 통과하고 DB 단계는 만료로 읽히는 창을
만든다. `validateTimestamps`가 `exp <= now`를 스큐 없이 다시 걸어 두 판정을 같은 규칙에 세운다.
**따라서 `clock-skew-seconds`가 실제로 완화하는 것은 `iat`(과 쓰지는 않는 `nbf`)뿐이다.**

`ver`를 양쪽에서 보는 이유도 같은 성질이다 — 버전을 싣기만 하고 검사하지 않으면 판을 올려도 옛 토큰이
계속 통과한다. 값을 읽는 방식도 `Number#intValue()`를 쓰지 않는다: 그 메서드는 `1.9`를 1로 자르고
`4294967297`을 1로 감아서, 우리가 쓴 적 없는 표현이 우리 값과 같아 보이게 만든다(`requiredExactInt`).

### 재구성 — 원문을 저장하지 않는 근거

유예 중에 돌려주는 refresh는 **저장해 둔 문자열이 아니라 다시 만든 문자열**이다. HMAC은 결정적이므로
같은 값·같은 순서·같은 알고리즘이면 같은 바이트가 나온다. 그 "같은 값"을 담는 것이
`RefreshTokenMaterial`이고, 그 필드와 `refresh_token` 컬럼은 일대일이다.

발급과 재구성이 **같은 메서드**(`serializeRefreshToken`)를 지나는 것이 이 설계의 전부다. 두 경로가 각자
문자열을 만들면 클레임 순서 하나만 어긋나도 갈리고, 갈렸다는 사실은 클라이언트가 옛 토큰을 다시 보낼 때에야
드러난다.

고정값이 셋이다 — 클레임 순서(빌더 호출 순서 = JSON 키 순서), HS512, 정수 초 NumericDate.
셋 중 하나라도 바뀌면 그것은 새 형식이고 `JwtTokenProvider.TOKEN_FORMAT_VERSION`(현재 `1`)이 올라가야 한다.

**판을 올렸을 때 옛 토큰이 어디서 걸리는지는 두 경로로 갈린다. 섞어 적으면 안 된다.**

| 무엇 | 어디서 | 결과 |
|---|---|---|
| 토큰이 옛 `ver`를 실었다 | 파싱 단계 `JwtTokenProvider#validateFormatVersion` | `AUTH-009` `INVALID_TOKEN`. **DB 판정까지 내려가지 않는다** |
| 토큰의 `ver`와 DB 행의 `token_format_version`이 갈렸다 | `RefreshTokenService#loadAndVerify` | `AUTH-016` (정합성 오류) |
| 유예 재구성에서 자식 행의 판이 지금 판이 아니다 | `RefreshTokenService#reissueWithinGrace` | `AUTH-015` |

즉 **판을 올린 직후 옛 refresh를 들고 온 클라이언트가 받는 것은 `AUTH-015`가 아니라 `AUTH-009`다.**
아래 두 줄은 토큰이 주장한 판과 행에 적힌 판이 갈리는 경우를 위한 안전장치이고, 정상적으로는 둘이
항상 같아 실행되지 않는다 — 실행됐다면 그것 자체가 신호다.

---

## 5. 데이터와 상태

### `refresh_token`

`issued_at`·`expires_at`은 **epoch 초**(JWT NumericDate와 같은 값 — 재구성이 여기 걸려 있다),
`rotated_at`·`grace_expires_at`·`revoked_at`은 **epoch 밀리초**(유예가 3초라 판정이 촘촘해야 한다).
둘 다 BIGINT라 시간대가 개입할 자리가 없고, 테스트가 `Clock`으로 경계를 그대로 재현할 수 있다.

인덱스: `ux_refresh_token_jwt_id`(신원), `ux_refresh_token_parent_jwt_id`(한 부모에 자식 하나 —
CAS가 틀렸을 때 조용히 두 갈래가 자라는 대신 INSERT가 죽는다), `ix_refresh_token_user(user_id, revoked_at)`,
`ix_refresh_token_family(family_id, expires_at)`.

users에 FK를 걸지 않았다. 탈퇴가 소프트 삭제라 users 행이 사라지지 않아 FK가 막아 줄 사건이 없고,
이 테이블은 보존 기간이 지나면 사용자와 무관하게 비워진다.

### 상태 판정 (`RefreshTokenRow#state`)

```
revoked_at 있음          → REVOKED
expires_at <= now(초)     → EXPIRED
rotated_at 없음          → ACTIVE
now(ms) < grace_expires_at → GRACE
그 외                    → GRACE_ENDED
```

컬럼으로 두지 않은 이유는 이 값이 시각의 함수여서다. 저장하면 시간이 흐를 때마다 갱신해 줄 사람이
필요해지고, 갱신되지 않은 행이 곧 거짓말이 된다. 폐기가 만료보다 앞서는 것은 둘이 다른 사건이기
때문이다 — 전자는 재사용 판정의 근거, 후자는 그냥 재로그인.

`rotated_at`과 `grace_expires_at` 중 한쪽만 채워진 행은 **정합성 오류**이고 재사용으로 단정하지 않는다.

---

## 6. 트랜잭션과 경쟁

### 경계

`AuthService`·`AdminAuthService`는 **트랜잭션을 열지 않는다.** 둘 다 외부 OAuth HTTP 호출을 들고 있어,
열면 그 왕복이 통째로 트랜잭션(과 이제는 사용자 잠금) 안에 들어온다.
쓰기 경계는 `RefreshTokenService`와 각 저장소가 갖는다.

모두 `REQUIRES_NEW` + `READ_COMMITTED`다. 방어적인 선택이다 — 호출자가 이미 트랜잭션을 열고 있으면
스프링은 격리 수준 지정을 **조용히 무시하고** 참여한다. 그러면 회전 판정이 REPEATABLE READ에서 돌아
CAS에 진 요청이 다시 읽은 행에서 *자기가 처음 본 값*을 본다.

곁가지로 두 곳의 경계를 고쳤다.

- `KakaoOAuthServiceImpl`의 클래스 레벨 `@Transactional(readOnly = true)`를 제거했다. 카카오 HTTP
  호출이 트랜잭션 안에 있었고, 그 안에서 불린 `SocialUserService#createOrLoginSocialUser`(쓰기)가
  읽기 전용 트랜잭션에 참여해 재가입의 더티 체킹 변경이 플러시되지 않을 수 있는 모양이었다.
  Google·Apple 구현체가 원래 트랜잭션을 갖지 않는다.
- `TestService#createAndLogin`을 `NOT_SUPPORTED`로 바꿨다. 발급이 별도 트랜잭션이 되면서, 이 메서드가
  트랜잭션을 들고 있으면 방금 만든 사용자가 아직 커밋되지 않아 발급 쪽에서 보이지 않는다.

### 잠금

**사용자 행 → refresh 행.** 언제나 이 순서다. 잠금을 refresh가 아니라 users에 거는 이유는 회전이 부모를
바꾸고 자식을 *만들기* 때문이다 — 아직 없는 행은 잠글 수 없으므로 경쟁자가 만나는 지점은 그 위여야 한다.

잠금 쿼리는 네이티브로 내려가 `@Where(is_deleted = false)`를 **우회한다.** 소프트 삭제된 사용자를 잠그지
못하면 탈퇴 폐기와 재가입 발급이 서로를 보지 못한 채 지나간다. 삭제 여부는 값으로 돌려받아 호출자가 판정한다.

정리 배치는 users를 건드리지 않으므로 회전과 순환 대기가 생기지 않는다.

### 회전 — 두 문장

분기용 조회는 **잠금 밖**, 판정은 **잠금 안의 조건부 UPDATE**다.

```sql
UPDATE refresh_token SET rotated_at = :now, grace_expires_at = :graceExpiresAt
 WHERE id = :id AND rotated_at IS NULL AND revoked_at IS NULL AND expires_at > :nowSeconds
```

- **1행** → 새 jti 자식 INSERT. 같은 트랜잭션이라 INSERT가 실패하면 부모 UPDATE도 없던 일이 된다.
- **0행** → 영속성 컨텍스트 없이(JdbcTemplate) 최신 부모를 다시 읽어 재분류한다.

같은 부모로 동시에 들어온 두 요청은 **둘 다 ACTIVE를 보고 진입**하고, 잠금을 늦게 얻은 쪽이 실제로 0행을
받는다. 조회까지 잠금 안으로 넣으면 이 경로는 코드에만 있고 실행되지는 않는 분기가 된다.

> **한계로 보고한다.** 정상 경로는 사용자 잠금으로 직렬화되므로, 0행 경로가 관찰되는 조건은
> "두 요청이 잠금 획득 전에 같은 ACTIVE 부모를 읽었을 때"다. 실제 MySQL 테스트에서 이 경합은
> 두 커넥션의 분기용 SELECT를 먼저 각각 돌린 뒤 잠금 획득을 어긋나게 하면 재현된다 —
> 프로덕션 코드에 테스트용 우회 API나 latch를 넣지 않았다.

### 분기별 동작

| 상태 | 동작 |
|---|---|
| JWT 자체 만료 | `AUTH-004`. **DB 판정으로 내려가지 않는다** |
| **refresh 행** 없음 | `AUTH-014` |
| **users 행**이 없거나 탈퇴 | `AUTH-017`. **refresh 행의 상태보다 먼저 본다** — 아래 참고 |
| ACTIVE | CAS → 자식 INSERT → 새 access + 새 refresh |
| GRACE | **쓰기 없음.** 자식을 찾아 그 문자열을 재구성해 돌려주고 access만 새로 발급. 부모 유예도 자식 만료도 연장하지 않는다. |
| GRACE 자식이 ACTIVE가 아님 / 형식 버전 불일치 | `AUTH-015`. 전체 폐기 아님 |
| GRACE 자식 미존재 · 소유자/계열 불일치 | `AUTH-016` (정합성 오류) |
| EXPIRED (DB) | `AUTH-004` |
| REVOKED · GRACE_ENDED | 사용자 전체 폐기 후 `AUTH-013` |

**비활성 사용자 판정이 상태 판정보다 앞이다.** 사용자 잠금이 상태 분류보다 먼저 오므로
(`RefreshTokenService#rotate` 2단계 → 3·4단계), 탈퇴한 사용자의 refresh는 그 행이 폐기돼 있어도
`AUTH-013`이 아니라 `AUTH-017`로 나간다.

이 순서가 재사용 감지를 약화시키지 않는다. 탈퇴는 소프트 삭제와 전체 폐기를 **한 트랜잭션에서**
커밋하므로(`UserWithdrawService#withdraw`), 여기까지 내려온 시점에 그 사용자의 계열은 이미 전부 끊겨
있다. 다시 폐기할 것이 없는 상태에서 전체 폐기를 한 번 더 도는 대신 401 하나로 끝낸다.

반대로 **재가입한 사용자는 이 분기에 걸리지 않는다** — 같은 user 행이 되살아나 잠금이 성공하고,
옛 계열의 폐기된 행으로 들어온 요청은 그대로 `AUTH-013`이 되어 **재가입으로 막 열린 새 계열까지
폐기한다.** 위 "폐기의 출처" 항목과 같은 정책이다.

**전체 폐기는 값으로 돌아온다.** `RotationResult.ReuseDetected`를 받은 `AuthService`가 커밋 뒤에 401로
바꾼다. 트랜잭션 안에서 던지면 폐기가 롤백되고 경고만 나간다 — 탈취범의 토큰이 살아 있는 채로.

전체 폐기 대상은 `revoked_at IS NULL AND expires_at > now`인 모든 행이다. 회전된 부모도 포함한다
(유예 중인 부모를 남기면 방금 재사용을 일으킨 그 토큰이 몇 초 더 산다).

### 발급·폐기·탈퇴의 순서

- 전체 폐기가 커밋된 **뒤** 잠금을 얻는 새 로그인은 허용된다. 새 계열이 열릴 뿐이다.
- 탈퇴는 소프트 삭제와 refresh 전체 폐기를 **한 트랜잭션에서** 커밋한다. 따로 커밋하면 그 틈에 재가입한
  사용자가 **같은 user 행을 되살리고**(`SocialUserService#reactivateIfDeleted`) 탈퇴 전 계열이 유효해진다.
  `TestService#withdrawUser`에도 같은 불변식을 넣었다.
- 어드민 교환은 권한을 **발급 트랜잭션 안에서** 다시 본다. 교환 전에 따로 읽어 확인하면 그 확인과 발급
  사이에 권한이 내려간 사용자에게 ADMIN 토큰이 나간다.

---

## 7. 인증 경로

`JwtAuthenticationFilter`는 **저장소를 전혀 읽지 않는다.** role 클레임이 없는 옛 토큰을 위한 DB 폴백을
제거했다 — 그 토큰들은 새 필수 클레임이 없어 파싱에서 거절되므로 폴백이 받아 줄 대상이 없어졌고,
분기가 남아 있으면 "필수 클레임"이라는 계약에 구멍이 된다. `JwtTokenResolver`는 삭제했다.

`PrincipalDetailsService`는 남겼다(스프링 시큐리티가 요구하는 `UserDetailsService` 구현). **여기에 새
호출을 붙이면 위 불변식이 깨진다.**

`PrincipalDetails`에 `familyId`가 생겼고 `@CurrentTokenFamilyId`로 꺼낸다. 계열 로그아웃이 그 값을 쓴다.

대가는 그대로다. 탈퇴·권한 변경·로그아웃·refresh 폐기는 **이미 나간 access를 죽이지 않는다**(최대 30분).
그것들이 끊는 것은 재발급이다. 즉시 차단이 필요한 자리는 도메인 로직의 상태·소유권 검사다.

로그아웃의 401은 시큐리티 설정이 아니라 `AuthService#logout`에서 낸다. 저장소에 `AuthenticationEntryPoint`가
없어 필터 단계에서 거절하면 `CustomApiResponse`가 아닌 본문이 나가기 때문이다.

---

## 8. 어드민 일회 소비

`GETDEL`의 원자성을 조건부 UPDATE로 옮겼다.

```sql
UPDATE admin_oauth_state SET consumed_at = :now
 WHERE state = :state AND consumed_at IS NULL AND expires_at > :now
```

**1행을 바꾼 요청만 승자**이고 값은 그 뒤에 읽는다. 지우는 대신 도장을 찍는 이유는 DELETE가 지운 행의
값을 돌려주지 않아 승패 판정과 값 읽기를 한 문장에 담을 수 없기 때문이다.

없음·소비됨·만료됨을 구분하지 않고 전부 같은 오류를 낸다(`AUTH-011`/`AUTH-012`). 구분해서 알려 주면
어느 state가 존재했는지를 알려 주는 꼴이 된다.

**nonce가 틀려도 state는 소비된다.** 남겨 두면 같은 state로 계속 다시 시도할 수 있고, 그것이 곧 state가
막으려던 재생 공격이다.

`oauth_nonce` 쿠키: `HttpOnly`, `SameSite=Lax`, 경로는 콜백으로 한정, 수명은 state와 같은 값(설정),
**`Secure`는 설정값이고 기본이 켜짐**이다. 끄는 자리는 http로 도는 로컬뿐이다 — 설정을 빠뜨렸을 때
덜 안전한 쪽으로 기울지 않게 기본값을 안전한 쪽에 뒀다. 지우는 쿠키도 속성이 같아야 브라우저가 같은
쿠키로 알아본다.

---

## 9. 보존 정리

`AuthTokenCleanupFacade` — 매일 **04:40 KST**, ShedLock 이름 **`auth-token-cleanup`**,
`lockAtMostFor=PT15M`, `lockAtLeastFor=PT1M`. 다른 회차(01:00·01:45·03:00·04:00, 매시 :15·:30)와
시각을 가른다 — `@Scheduled` 기본 실행기는 단일 스레드다.

지우는 대상 셋:

1. **refresh 계열** — `MAX(expires_at) < now - 30일`인 계열.
2. **만료된 어드민 state**, 3. **만료된 어드민 교환 코드** — 소비된 행도 만료 시각이 지나면 함께 걸린다.

### 계열 정리의 계약

**고르는 단위도 지우는 단위도 계열 전체다.** 선택 조건 `MAX(expires_at) < cutoff`는 "이 계열에는 기준 시각
이후까지 사는 토큰이 하나도 없다"는 뜻이다. 따라서

- 살아 있는 토큰이 하나라도 있는 계열은 **애초에 선택되지 않는다.**
- 선택된 계열은 부모와 자식이 **함께** 사라진다. 부모만 지워지고 자식이 남는(또는 그 반대의) 반쪽 상태는
  생기지 않으므로, 부모-자식 관계는 보존되거나 통째로 없어지거나 둘 중 하나다.

계열 단위여야 하는 이유는 이력이 재사용 판정의 근거이기 때문이다. 회전된 부모만 먼저 지우면 그 부모로 들어온
재사용 요청이 "행이 없다"(`AUTH-014`)로 보여 전체 폐기가 일어나지 않는다.

조회와 삭제가 두 문장인 것은 MySQL의 다중 테이블 DELETE가 `LIMIT`을 받지 않기 때문이고, 두 문장이 같은
`cutoff` 술어를 쓰는 것이 그 분리를 상쇄한다 — **한 문장이 고른 것과 다른 집합을 다른 문장이 지우지 않게
하는 장치이지, "살아 있는 자식만 남긴다"는 예외 처리가 아니다.**

어드민 쪽은 소비가 `expires_at > now`만, 정리가 `expires_at < now`만 건드려 집합이 겹치지 않는다.

덩어리마다 자기 트랜잭션이라 한 덩어리가 실패해도 앞서 지운 것은 커밋돼 있고, 세 대상은 서로 독립이라
하나가 실패해도 나머지가 돈다. 예외를 스케줄러 스레드로 흘리지 않는다.

---

## 10. 설정

`application*.yml`은 `.gitignore`의 `*.yml`에 걸려 커밋되지 않는다. **저장소에 남는 선언은 코드 기본값뿐**이고
yml이 있으면 그쪽이 이긴다. 아래 값은 전부 yml 없이 동작한다.

**그리고 dev/prod에서 "yml이 있으면"의 그 yml은 작업 트리의 파일이 아니다.** CI/CD가
`application-dev.yml`·`application-prod.yml`을 `APPLICATION_DEV_YML`·`APPLICATION_PROD_YML` 시크릿
본문으로 덮어쓰므로(3장), 로컬 파일을 고쳐 두는 것은 **로컬에서 읽히는 사본을 고친 것**이지 배포
설정을 고친 것이 아니다. 아래 표는 "이 키가 없으면 무엇이 되는가"를 읽는 용도이고, 실제로 그 키를
넣는 자리는 3-1이다.

### `jwt` (`JwtProperties`, `@Validated`)

| 키 | 기본값 | 비고 |
|---|---|---|
| `access-secret-key` / `refresh-secret-key` | 없음 | `@NotBlank` — 없으면 부팅이 죽는다 |
| `access-token-expire-time` | `1800000` (30분) | `@Positive` + **1000ms 이상**(`isExpireTimeAtLeastOneSecond`) |
| `refresh-token-expire-time` | `1209600000` (14일) | 위와 같음 |
| `issuer` | `solply-server` | `@NotBlank`. **바꾸면 전원 재로그인** |
| `audience` | `solply-app` | `@NotBlank`. **바꾸면 전원 재로그인** |
| `clock-skew-seconds` | `30` | `@PositiveOrZero`. **만료에는 쓰이지 않는다** — `iat`/`nbf` 전용 |

TTL에 1초 하한이 따로 있는 이유는 NumericDate가 정수 초이기 때문이다. 500ms는 양수라 `@Positive`를
통과하지만 `exp`와 `iat`가 같은 초로 접혀 `exp > iat` 검증에 **우리가 발급한 토큰이 걸린다.**

### `solply.auth` (`AuthProperties`, `@Validated`)

| 키 | 기본값 | 비고 |
|---|---|---|
| `rotation-grace` | `3s` | **음수 금지.** 0이면 유예 분기가 사라지는 게 아니라 *즉시 재사용 판정*이 된다 |
| `refresh-retention` | `30d` | **음수 금지.** 짧게 잡으면 재사용 판정의 근거가 먼저 사라진다 |
| `admin-state-ttl` | `10m` | **0보다 커야 한다.** nonce 쿠키 수명도 이 값을 쓴다 |
| `admin-auth-code-ttl` | `5m` | **0보다 커야 한다** |
| `cleanup-cron` | `0 40 4 * * *` | **리터럴이 `AuthTokenCleanupFacade`의 `@Scheduled`에도 있다 — 함께 고칠 것** |
| `cleanup-batch-size` | `1000` | |
| `cleanup-max-batches` | `50` | 상한에 닿으면 경고를 남기고 다음 회차로 넘긴다 |
| `oauth-nonce-cookie-secure` | `true` | 로컬(http)에서만 `false` |

`Duration`에는 `@Positive`를 걸 수 없어 위 네 값은 `@AssertTrue` 메서드 둘
(`isGraceAndRetentionNonNegative` · `isAdminTtlPositive`)이 본다. 음수가 위험한 이유는 값이 작아지는 게
아니라 **뜻이 뒤집히기** 때문이다 — 음수 유예는 회전 직후의 부모를 이미 유예 종료로 만들어 정상 재전송을
전체 폐기로 바꾸고, 음수 보존은 정리 기준 시각을 미래로 밀어 살아 있는 계열을 삭제 대상에 넣는다.
어드민 TTL이 0이면 발급 즉시 만료라 소비 조건이 절대 참이 되지 않고, 증상은 "state가 유효하지 않다"로만 보인다.

`cleanup-cron`의 중복은 구조적으로 강제된 것이다 — `@ConfigurationProperties`의 필드 기본값은 `@Scheduled`
플레이스홀더가 해석될 때 보이지 않는다(`PlaceStatsProperties#countCron`이 같은 이유로 같은 중복을 안고 있다).

dev/prod는 전부 환경변수로 받고 위 값을 기본값으로 둔다: `JWT_ISSUER`, `JWT_AUDIENCE`,
`JWT_CLOCK_SKEW_SECONDS`, `AUTH_ROTATION_GRACE`, `AUTH_REFRESH_RETENTION`, `AUTH_ADMIN_STATE_TTL`,
`AUTH_ADMIN_AUTH_CODE_TTL`, `AUTH_CLEANUP_CRON`, `AUTH_CLEANUP_BATCH_SIZE`, `AUTH_CLEANUP_MAX_BATCHES`,
`AUTH_OAUTH_NONCE_COOKIE_SECURE`.

> **yml 편집 주의.** 작업 트리의 dev/prod에 `solply:` 최상위 키를 새로 넣었다(`auth:` 하위만).
> **같은 주의가 시크릿 본문에도 그대로 적용된다**(3-1). 통계 쪽 키를 추가할 때는
> **같은 `solply:` 블록 안에 병합**할 것 — YAML은 같은 최상위 키가 두 번 나오면 조용히 뒤엣것만 남긴다.
> (프로파일 파일 사이에서는 문제가 없다. 스프링이 각 문서를 `solply.auth.*` 같은 평평한 키로 풀어
> 키 단위로 합치므로, dev/prod의 `solply.auth`가 `application.yml`의 `solply.place-stats`를 가리지 않는다.)

### `solply.place-stats` — 분리된 매시 두 회차 (2026-09-12 확인·보완)

통계 회차가 둘로 갈리면서 생긴 키가 `application.yml`에 빠져 있어 채웠다. 값은 코드 기본값과 같다.

| 키 | 값 | 상태 |
|---|---|---|
| `count-cron` | `0 30 * * * *` | 이미 있었음 (이제 **리뷰 축만** 가리킨다 — 주석을 그에 맞게 고쳤다) |
| `bookmark-delta-cron` | `0 15 * * * *` | **추가** |
| `review-count-max-attempts` / `review-count-retry-delay` | `3` / `5s` | **추가** |
| `bookmark-delta-max-attempts` / `bookmark-delta-retry-delay` | `3` / `5s` | **추가** |

**dev/prod에는 넣지 않았다.** `solply.place-stats`는 처음부터 `application.yml`에만 선언되고 프로파일
파일은 그것을 물려받는 구조이며(place-stats 키 중 환경변수로 연결된 것이 하나도 없다), 새 키만 환경변수로
빼면 같은 블록 안에서 연결 방식이 둘로 갈린다. 지금 상태로 세 프로파일 모두 위 값을 쓴다.

---

## 11. 파일 목록

### 신규

| 파일 | 역할 |
|---|---|
| `db/migration/V41__auth_refresh_token_and_admin_temp_state.sql` | 테이블 3개 |
| `domain/auth/config/AuthProperties.java` | 회전 유예·보존·어드민 TTL·정리·쿠키 Secure |
| `domain/auth/entity/RefreshTokenRow.java` | 행 + `state(Instant)` · `isConsistent()` · `toMaterial()` |
| `domain/auth/entity/RefreshTokenState.java` | REVOKED·EXPIRED·ACTIVE·GRACE·GRACE_ENDED |
| `domain/auth/service/RefreshTokenService.java` | 발급·회전·계열 폐기 |
| `domain/auth/service/RotationResult.java` | sealed — `Rotated` / `ReuseDetected` |
| `domain/auth/service/AuthTokenCleanupProcessor.java` | 정리 한 덩어리 = 한 트랜잭션 |
| `domain/auth/service/facade/AuthTokenCleanupFacade.java` | `@Scheduled` + `@SchedulerLock` |
| `global/config/ClockConfig.java` | `Clock.systemUTC()` |
| `global/annotation/CurrentTokenFamilyId.java` | 계열 ID 주입 |
| `global/jwt/dto/RefreshTokenMaterial.java` | 재구성 재료 |
| `global/jwt/dto/AccessTokenPayload.java` | 검증 통과 access |
| `global/jwt/dto/RefreshTokenPayload.java` | 검증 통과 refresh |

### 수정

| 파일 | 무엇을 |
|---|---|
| `global/jwt/JwtTokenProvider.java` | 전면 재작성 — 고정 클레임·재구성·강한 검증 |
| `global/jwt/JwtProperties.java` | issuer·audience·clock-skew 추가, TTL 기본값, `@Validated` |
| `global/jwt/TokenType.java` | `claimValue()` |
| `global/jwt/JwtAuthenticationFilter.java` | DB 폴백 제거 |
| `global/security/PrincipalDetails.java` | `familyId`, `ofAccessToken` |
| `global/security/PrincipalDetailsService.java` | 주석만 — 인증 경로에서 빠졌다는 사실 |
| `global/exception/ErrorCode.java` | AUTH-013~017 |
| `domain/auth/repository/RefreshTokenRepository.java` | Redis → JdbcTemplate, 잠금·CAS·폐기·정리 |
| `domain/auth/service/AuthService.java` | 트랜잭션 제거, 회전 결과 변환, 계열 로그아웃 |
| `domain/auth/controller/AuthController.java` | 로그아웃에 계열 ID |
| `domain/auth/service/oauth/kakao/KakaoOAuthServiceImpl.java` | 클래스 레벨 readOnly 트랜잭션 제거 |
| `domain/admin/auth/repository/AdminOAuthStateRepository.java` | Redis → JdbcTemplate 조건부 소비 |
| `domain/admin/auth/repository/AdminAuthCodeRepository.java` | 같음 |
| `domain/admin/auth/service/AdminAuthService.java` | 소비 원자성, ADMIN 재검사를 발급 트랜잭션으로 |
| `domain/admin/auth/controller/AdminAuthController.java` | 쿠키 `Secure` 설정화, 수명을 state와 일치 |
| `domain/user/service/UserWithdrawService.java` | 사용자 잠금 + refresh 전체 폐기 동시 커밋 |
| `domain/test/service/TestService.java` | 새 발급 API, 탈퇴 시 폐기, 트랜잭션 경계 |
| `resources/application.yml` · `-dev.yml` · `-prod.yml` | jwt·solply.auth 블록 |

### 삭제

- `global/jwt/JwtTokenResolver.java` — 파싱이 타입 있는 payload를 돌려주면서 할 일이 없어졌다.

---

## 12. 주요 공개 메서드

```java
// RefreshTokenService — 전부 REQUIRES_NEW + READ_COMMITTED
TokenCollectionDto issue(Long userId, SocialPlatform platform);
TokenCollectionDto issueForAdmin(Long userId, SocialPlatform platform);  // 잠금 안에서 ADMIN 재검사
RotationResult     rotate(RefreshTokenPayload payload);
int                revokeFamily(Long userId, String familyId);

// JwtTokenProvider
String               createAccessToken(Long userId, SocialPlatform, UserRole, String familyId);
RefreshTokenMaterial newRefreshTokenMaterial(Long userId, SocialPlatform, String familyId, String jwtId);
String               serializeRefreshToken(RefreshTokenMaterial material);   // 발급·재구성 공용
AccessTokenPayload   parseAccessToken(String token);
RefreshTokenPayload  parseRefreshToken(String token);

// RefreshTokenRepository (JdbcTemplate)
Optional<LockedUser>      lockUser(Long userId);                    // users FOR UPDATE, 소프트 삭제 포함
Optional<RefreshTokenRow> findByJwtId(String jwtId);
Optional<RefreshTokenRow> findByParentJwtId(String parentJwtId);
void insert(RefreshTokenMaterial material, String parentJwtId);
int  markRotated(long id, long rotatedAtMs, long graceExpiresAtMs, long nowSeconds);   // CAS
int  revokeFamily(Long userId, String familyId, long revokedAtMs);
int  revokeAllByUserId(Long userId, long revokedAtMs, long nowSeconds);
List<String> findExpiredFamilyIds(long cutoffSeconds, int limit);
int  deleteExpiredTokensOfFamilies(List<String> familyIds, long cutoffSeconds);

// 어드민
Optional<String>           AdminOAuthStateRepository#consume(String state, long nowMs);
Optional<ConsumedAuthCode> AdminAuthCodeRepository#consume(String authCode, long nowMs);

// AuthService
SocialLoginResponse socialLogin(SocialPlatform, SocialLoginRequest);
RefreshResponse     refreshToken(String refreshToken);
void                logout(Long currentUserId, String familyId);   // 둘 중 하나라도 null이면 401
```

---

## 13. 검증에서 먼저 볼 것

테스트를 돌리지 않았으므로 아래는 **주장이 아니라 확인할 목록**이다.

1. 동시 회전에서 자식이 하나이고, 패자가 받은 refresh 문자열이 승자의 것과 **바이트까지 같은지**.
   정상 경쟁에서 전체 폐기가 일어나지 않아야 한다.
2. CAS 0행 경로가 실제 경쟁으로 재현되는지 — 두 커넥션의 분기용 SELECT를 먼저 각각 돌리고 잠금 획득을
   어긋나게 한다(§6의 한계 참고).
3. JWT ↔ DB 왕복 후 문자열 보존, 헤더·클레임 순서 고정, 유예·만료가 연장되지 않음.
4. 상태 우선순위와 경계(만료 직전/직후, 유예 3초 전후)를 `Clock`으로.
5. 연속 회전, 자식이 회전·폐기·만료된 경우, 정합성 오류 분류.
6. 유예 종료 재사용과 이미 폐기된 토큰의 재사용에서 **폐기가 실제로 커밋되는지**(같은 트랜잭션 밖에서
   다른 커넥션으로 읽어 확인).
7. 전체 폐기 ↔ 회전/발급의 양방향 순서. 폐기 커밋 뒤 새 로그인이 허용되는지.
8. 자식 INSERT 실패 시 부모 UPDATE가 롤백되는지.
9. 계열 로그아웃 **직후**에 다른 계열이 살아 있는지(로그아웃 호출 자체의 범위). 반복 로그아웃 200.
   탈퇴→재가입에서 옛 계열 차단.
9-1. **폐기의 출처를 묻지 않는 정책을 값으로 고정한다.** 로그아웃한 계열의 refresh를 한 번 더 보내면
   `AUTH-013`이 나가고 **다른 계열까지 폐기되는지.** 탈퇴→재가입으로 새 계열을 연 뒤 옛 계열의 폐기된
   토큰을 보내면 **새 계열까지 폐기되는지.** 둘 다 의도된 동작이므로, 테스트가 없으면 다음 사람이
   "버그"로 읽고 되돌린다.
9-2. **`AUTH-017` 경로.** 탈퇴한 사용자의 refresh 재발급이 404가 아니라 401 `AUTH-017`인지.
   같은 상황에서 `AUTH-013`이 나가지 않는지(비활성 사용자 판정이 상태 판정보다 앞이다).
   로그인·어드민 교환에서 없는 사용자는 **그대로 404 `USER-001`**인지.
10. 어드민 state/code의 만료와 동시 소비(승자 하나).
11. 정리 배치가 살아 있는 계열을 지우지 않는지, 소비와 겹치지 않는지.
12. access 검증 거절 목록(서명·만료·iss·aud·alg·type·필수 클레임)과 **일반 인증에서 SQL/Redis 0회**,
    ADMIN 보호 회귀.
13. `ver` 검증 — access·refresh 모두 `ver` 누락 시 거절, `ver=2` 거절, `ver=1.0`·`ver="1"`·
    `ver=4294967297` 거절(`Number#intValue()`였다면 통과했을 값들).
    **거절 코드가 `AUTH-009`인지도 함께 본다** — 판이 다른 토큰은 파싱에서 끊기므로 `AUTH-015`가
    아니다(4장 표).
14. 시각 검증 — `exp == now` 거절(스큐로 살아나지 않는다), `exp < iat` 거절,
    `iat = now + skew` 통과 / `now + skew + 1` 거절, `iat`·`exp`가 소수나 문자열이면 거절.
15. `sub` — `0`·음수·비숫자 거절.
16. 설정 검증 — `rotation-grace: -1s`, `refresh-retention: -1d`, `admin-state-ttl: 0`,
    `access-token-expire-time: 500`이 **부팅을 막는지**.

### 지금 컴파일이 깨지는 기존 테스트 (Task 3가 고칠 것)

- `global/jwt/JwtAuthenticationFilterIT` — `jwtTokenProvider.generateAccessToken(...)`이 사라졌고
  레거시 폴백 경로 검증이 무효가 됐다.
- `global/security/PrincipalDetailsServiceIT` — 클래스는 남았지만 인증 경로에서 빠졌다.

두 파일 모두 **손대지 않았다.**

---

## 14. 2차 보완 (2026-09-12, 검증 착수 전)

1차 구현 뒤 지적된 것들을 반영했다. 여기도 테스트·빌드는 돌리지 않았다.

| # | 무엇을 | 어디를 |
|---|---|---|
| 1 | `ver`를 access·refresh 모두 필수·정확 일치로. `Number#intValue()`의 절삭·오버플로 허용을 제거 | `JwtTokenProvider#validateFormatVersion` · `#requiredExactInt` · `#integralValue` (옛 `requiredInt` 대체) |
| 2 | `sub` 양수, `exp > iat`, 미래 `iat` 허용 오차, NumericDate 형식 검사 | `JwtTokenProvider#requiredUserId` · `#validateTimestamps` · `#requiredNumericDate` |
| 3 | 만료를 스큐 없이 `exp <= now`로 다시 거절. 스큐는 `iat`/`nbf` 전용임을 문서와 일치 | `JwtTokenProvider#validateTimestamps` · `#parseAndValidate` javadoc · `JwtProperties#clockSkewSeconds` · 본 문서 §4 |
| 4 | 유예·보존 음수 금지, 어드민 TTL 양수, JWT TTL 1초 하한 | `AuthProperties#isGraceAndRetentionNonNegative` · `#isAdminTtlPositive` · `JwtProperties#isExpireTimeAtLeastOneSecond` |
| 5 | `loadAndVerify`의 정합성 검사 유지 (변경 없음) | `RefreshTokenService#loadAndVerify` |
| 6 | 정리의 계약을 "전체가 만료된 계열만 고르므로 관계가 보존된다"로 정정. 방어 논리 서술 제거 | `RefreshTokenRepository#deleteExpiredTokensOfFamilies` · `AuthTokenCleanupProcessor#deleteExpiredRefreshFamilyBatch` · 본 문서 §9 |
| 7 | "3초는 탈취범이 쓰기에 짧다"는 근거 없는 주장 제거 | `AuthProperties#rotationGrace` javadoc |
| 8 | 분리된 통계 회차의 yml 키 보완 | `application.yml`의 `solply.place-stats` (§10) |

문서 쪽 정정 둘도 함께 넣었다 — §4의 "만료(스큐 30초)"는 틀린 설명이었고(만료에는 스큐가 없다),
§3의 전환·롤백 서술은 "구버전 앱이 막힌다"와 "옛 토큰이 거절된다"를 섞고 있었으며 롤백 시 옛 서버가
새 토큰을 거절한다고 확인 없이 단정하고 있었다.
