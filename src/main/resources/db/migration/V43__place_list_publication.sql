-- V43: 목록 스냅샷의 "내용"을 공유 저장소로 옮긴다.
--
-- V39는 번호만 공유하고 사진은 각 인스턴스의 힙에 뒀다("사진의 내용은 어디에도 저장되지 않는다").
-- 그래서 배포마다 새 인스턴스가 전량을 다시 읽어 짓고 새 번호를 받아, 같은 데이터인데 진행
-- 중이던 커서가 전부 만료됐다. 여기서는 사진 자체를 행에 싣는다 — 올라온 인스턴스는 짓지 않고
-- 그 행을 복원한다.
-- 상세: docs/design/2026-09-12-stats-commit-snapshot-and-db-delta.md §4~9
--
-- ⚠️ place_list_snapshot_versions(V39)를 여기서 DROP하지 말 것. 롤아웃 중에는 옛 바이너리가
--    아직 그 테이블에 발급하며 돌고, 롤백하면 다시 쓴다. 새 코드는 읽지도 쓰지도 않으며,
--    롤백 창이 닫힌 뒤 별도 마이그레이션으로 지운다.

-- 발행물. 신원은 이 행의 AUTO_INCREMENT id다 — 내용을 저장하는 곳과 신원을 내주는 곳이 하나라
-- "번호는 받았는데 내용이 없다"가 성립하지 않는다.
--
-- ⚠️ cursor_version과 id는 다른 것을 뜻한다. id는 "새 내용이 있나"(각 인스턴스의 폴이 보는 값),
--    cursor_version은 "정렬 순서가 갈렸나"(커서가 싣는 값)다. 구조가 바뀐 발행은 자기 id를
--    cursor_version으로 쓰고, 표시값만 바뀐 발행은 직전 값을 그대로 이어받는다. 둘을 하나로
--    합치면 이름 하나 고친 어드민 수정이 스크롤 세션을 전부 끊는다.
CREATE TABLE place_list_publications
(
    id             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '이 발행의 신원. 폴이 보는 값',
    cursor_version BIGINT          NULL COMMENT '커서가 싣는 회차. 구조 발행은 INSERT 직후 같은 트랜잭션에서 id로 채운다',
    format_version INT         NOT NULL COMMENT 'payload 형식. 지원 목록 밖이면 읽지 않는다',
    entry_count    INT         NOT NULL COMMENT '엔트리 수. 디코딩 뒤 대조하는 값이다',
    payload_bytes  INT         NOT NULL COMMENT '저장된 바이트 수. max_allowed_packet을 눈으로 볼 자리',
    payload_sha256 CHAR(64)    NOT NULL COMMENT '저장된(=압축된) 바이트의 SHA-256',
    payload        LONGBLOB    NOT NULL COMMENT 'gzip(JSON). 엔트리 전량 + 표시값 + 태그',
    published_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 현재가 무엇인지는 이 한 행이 명시로 말한다.
--
-- ⚠️ MAX(id)로 대신하지 말 것. AUTO_INCREMENT는 발급 순서일 뿐 커밋 순서가 아니다 — 나중에
--    받은 id가 먼저 커밋될 수 있고, 커밋되지 않은 INSERT가 진행 중인 순간에도 MAX는 움직인다.
--    "가장 큰 id"와 "채택된 현재"는 같은 것이 아니다. 아웃박스에서 id 범위 삭제를 금지한 것
--    (V38)과 같은 이유다.
--
-- FK는 가리켜지고 있는 발행물이 지워지지 않게 한다 — 정리가 현재를 지우는 경로를 DB가 막는다.
CREATE TABLE place_list_publication_pointer
(
    id             TINYINT     NOT NULL COMMENT '항상 1',
    publication_id BIGINT          NULL COMMENT 'NULL이면 아직 아무도 발행하지 않았다',
    updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    CONSTRAINT fk_place_list_publication_pointer_publication
        FOREIGN KEY (publication_id) REFERENCES place_list_publications (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

INSERT INTO place_list_publication_pointer (id, publication_id) VALUES (1, NULL);

-- 재빌드 요청. 통계·어드민 트랜잭션이 자기 트랜잭션 안에서 올리고, 발행에 성공한 트랜잭션만
-- 처리 표시를 올린다.
--
-- ⚠️ 이 카운터를 불리언 플래그로 바꾸지 말 것. 발행자는 짓기 전에 읽은 requested_seq 까지만
--    processed_seq 로 올리고, 짓는 동안 올라간 몫은 다음 회차가 가져간다. 플래그였다면 그 몫이
--    지워진다.
--
-- ⚠️ 이 테이블을 발행물·포인터와 합치지 말 것. 여기는 통계 트랜잭션이 커밋 직전에 잠그는
--    뜨거운 행이고, 포인터는 모든 인스턴스가 몇 초마다 읽는 행이다. 합치면 통계가 커밋할
--    때까지 발행 경로가 그 락을 기다린다.
CREATE TABLE place_list_rebuild_requests
(
    id            TINYINT     NOT NULL COMMENT '항상 1',
    requested_seq BIGINT      NOT NULL DEFAULT 0 COMMENT '통계·어드민 트랜잭션이 같은 트랜잭션에서 올린다',
    processed_seq BIGINT      NOT NULL DEFAULT 0 COMMENT '발행에 성공한 트랜잭션만 여기까지 올린다',
    updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

INSERT INTO place_list_rebuild_requests (id) VALUES (1);
