-- V41: 인증 저장소를 Redis에서 MySQL로 옮긴다. refresh 토큰 계열 + 어드민 임시 상태 두 종류다.
--
-- 옮기는 이유는 Redis가 느려서가 아니라 refresh 회전이 트랜잭션을 요구하기 때문이다. 회전은
-- "부모를 회전됨으로 표시하고 자식을 만든다"가 한 덩어리여야 하는데, Redis 쓰기는 DB 트랜잭션
-- 밖이라 자식 생성이 실패해도 부모는 이미 무효가 된다. 같은 DB 안으로 들어오면 그 둘이 한 번에
-- 커밋되거나 한 번에 없던 일이 된다. ShedLock을 MySQL에 둔 것과 같은 논리다
-- (SchedulerLockConfig: 락이 보호하는 대상의 유일한 의존성과 실패 도메인을 일치시킨다).
--
-- 이 마이그레이션은 기존 Redis 값을 옮기지 않는다. 옛 access/refresh에는 아래 계약이 요구하는
-- 필수 클레임(jti·계열·발급자)이 없어 어차피 거절되므로, 전환은 전원 재로그인을 동반한다.
-- 상세: docs/design/2026-09-12-auth-mysql-implementation.md

-- ── refresh 토큰 계열 ────────────────────────────────────────────────────────
--
-- 한 행 = 한 refresh 토큰. 로그인 한 번이 계열(family) 하나를 열고, 회전할 때마다 같은 계열에
-- 자식이 하나씩 붙는다. 기기별 로그인이 서로를 끊지 않는 것이 유저당 키 하나였던 Redis 구조와
-- 갈리는 지점이고, "재사용이 감지되면 그 사용자의 전부를 폐기한다"의 폭도 여기서 정해진다.
--
-- ⚠️ 원문 JWT를 저장하지 않는다. 유예(3초) 동안 같은 문자열을 돌려주는 일은 저장이 아니라
--    재구성으로 한다 — 아래 고정값(고정 클레임·발급 시각·만료 시각·형식 버전)과 같은 서명
--    알고리즘이면 같은 바이트가 나온다. 그래서 issued_at/expires_at의 단위가 계약이다:
--    JWT NumericDate와 같은 정수 초여야 DB를 왕복해도 문자열이 보존된다.
--    DATETIME으로 두면 시간대·소수점 자리에서 갈릴 여지가 생기므로 BIGINT로 못 박는다.
--
-- rotated_at·grace_expires_at·revoked_at은 판정용 시각이라 더 촘촘해야 한다(유예가 3초다).
-- 같은 이유로 DATETIME이 아니라 epoch 밀리초다 — 시간대가 개입하지 않고, 테스트가 Clock으로
-- 경계를 그대로 재현할 수 있다.
--
-- 상태는 컬럼 하나가 아니라 우선순위로 읽는다:
--   revoked_at 있음 → REVOKED, expires_at <= now → EXPIRED, rotated_at 없음 → ACTIVE,
--   now < grace_expires_at → GRACE, 나머지 → 유예 종료.
-- 상태 컬럼을 따로 두지 않는 이유는 그 값이 시각의 함수여서다 — 저장하면 시간이 흐를 때마다
-- 갱신해 줄 사람이 필요해지고, 갱신되지 않은 행이 곧 거짓말이 된다.
CREATE TABLE refresh_token
(
    id                   BIGINT      NOT NULL AUTO_INCREMENT,
    user_id              BIGINT      NOT NULL,
    family_id            CHAR(36)    NOT NULL COMMENT '로그인 1회 = 계열 1개. 회전해도 바뀌지 않는다',
    jwt_id               CHAR(36)    NOT NULL COMMENT 'refresh JWT의 jti. 토큰 문자열 대신 이 값이 행을 찾는다',
    parent_jwt_id        CHAR(36)    NULL COMMENT '이 토큰을 낳은 부모의 jti. 로그인 직후 첫 토큰만 NULL',
    platform             VARCHAR(20) NOT NULL,
    token_format_version INT         NOT NULL COMMENT '재구성이 어떤 클레임 집합을 뜻하는지. 형식이 바뀌면 옛 행은 재구성 대상에서 빠진다',
    issued_at            BIGINT      NOT NULL COMMENT 'JWT iat과 같은 정수 초 (epoch seconds, UTC)',
    expires_at           BIGINT      NOT NULL COMMENT 'JWT exp와 같은 정수 초 (epoch seconds, UTC)',
    rotated_at           BIGINT      NULL COMMENT '회전 시각 (epoch milliseconds, UTC)',
    grace_expires_at     BIGINT      NULL COMMENT '유예 종료 시각 (epoch milliseconds, UTC). rotated_at과 항상 함께 채워진다',
    revoked_at           BIGINT      NULL COMMENT '폐기 시각 (epoch milliseconds, UTC)',
    created_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '운영 관찰용. 판정에는 쓰지 않는다',

    PRIMARY KEY (id),

    -- jti가 토큰의 신원이다. 중복되면 서로 다른 토큰이 같은 행을 가리킨다.
    UNIQUE KEY ux_refresh_token_jwt_id (jwt_id),

    -- 한 부모는 자식을 하나만 갖는다. 회전의 조건부 UPDATE가 이미 승자를 하나로 좁히지만,
    -- 이 제약은 그 판정이 틀렸을 때 조용히 두 갈래가 자라는 대신 INSERT가 죽게 만든다.
    -- MySQL의 UNIQUE는 NULL을 중복으로 보지 않으므로 로그인 직후 행(parent 없음)은 자유롭다.
    UNIQUE KEY ux_refresh_token_parent_jwt_id (parent_jwt_id),

    -- 사용자 단위 전체 폐기(재사용 감지·탈퇴)가 읽는 길.
    KEY ix_refresh_token_user (user_id, revoked_at),

    -- 계열 로그아웃과 보존 정리가 읽는 길. 정리는 계열의 MAX(expires_at)로 판정한다.
    KEY ix_refresh_token_family (family_id, expires_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- users에 FK를 걸지 않는다. 탈퇴가 소프트 삭제라 users 행이 사라지는 일이 없어 FK가 막아 줄
-- 사건 자체가 없고, 반대로 이 테이블은 보존 기간이 지나면 사용자와 무관하게 비워진다.

-- ── 어드민 OAuth 임시 상태 ───────────────────────────────────────────────────
--
-- Redis에서는 GETDEL 한 번이 "읽기 + 일회 소비"였다. MySQL에는 그 문장이 없으므로 소비를
-- 조건부 UPDATE로 표현한다: consumed_at IS NULL AND expires_at > now인 행에 도장을 찍고,
-- 1행을 바꾼 쪽만 값을 읽어 간다. 두 요청이 동시에 와도 UPDATE가 승자를 하나로 정한다.
-- 행을 지우지 않고 도장을 찍는 이유는 승패 판정과 삭제를 한 문장에 담기 위해서다
-- (DELETE는 지운 행의 값을 돌려주지 않아 SELECT ... FOR UPDATE가 한 번 더 필요해진다).
-- 도장이 찍힌 행과 만료된 행은 매일 정리 배치가 걷어 간다.
CREATE TABLE admin_oauth_state
(
    state       CHAR(36)    NOT NULL,
    nonce       CHAR(36)    NOT NULL COMMENT '요청을 시작한 클라이언트의 HttpOnly 쿠키 값',
    expires_at  BIGINT      NOT NULL COMMENT 'epoch milliseconds (UTC)',
    consumed_at BIGINT      NULL COMMENT '소비 시각 (epoch milliseconds, UTC). NULL이면 아직 쓰이지 않았다',
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '운영 관찰용',

    PRIMARY KEY (state),
    KEY ix_admin_oauth_state_expires_at (expires_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 콜백이 발급하는 일회용 교환 코드. 소비 규칙은 admin_oauth_state와 같다.
-- role을 담지 않는다 — 권한은 교환 시점에 DB에서 다시 읽는다(외부로 왕복시키지 않는다).
CREATE TABLE admin_auth_code
(
    auth_code   CHAR(36)    NOT NULL,
    user_id     BIGINT      NOT NULL,
    platform    VARCHAR(20) NOT NULL,
    expires_at  BIGINT      NOT NULL COMMENT 'epoch milliseconds (UTC)',
    consumed_at BIGINT      NULL COMMENT '소비 시각 (epoch milliseconds, UTC). NULL이면 아직 쓰이지 않았다',
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '운영 관찰용',

    PRIMARY KEY (auth_code),
    KEY ix_admin_auth_code_expires_at (expires_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
