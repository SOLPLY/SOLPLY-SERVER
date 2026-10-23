-- V44: 정기 통계 회차를 "타이머가 깨우면 돈다"에서 "요청이 DB에 남고 그것을 잡은 트랜잭션이 돈다"로 바꾼다.
--
-- 지금까지 회차의 존재는 @Scheduled 타이머의 발화 그 자체였다. 그래서 발화 순간에 인스턴스가
-- 죽거나 ShedLock을 쥔 인스턴스가 사라지면 그 회차는 흔적 없이 사라지고, 다음 발화까지(매시
-- 한 시간, 일 단위 하루) 값이 낡은 채로 남았다. 여기서는 발화가 먼저 이 테이블에 요청을 남기고,
-- 그 요청을 잡은 트랜잭션이 집계를 돌린다 — 실패하면 요청이 그대로 남아 다음 폴이 다시 잡는다.
-- 상세: docs/design/2026-09-13-durable-stats-jobs.md
--
-- ⚠️ 종류당 한 행이고 그 수는 넷으로 고정이다. 회차 이력을 쌓는 테이블이 아니다 — 이력이
--    필요하면 그 자리는 로그이고, 여기에 행을 쌓기 시작하면 정리 배치가 따라온다.
--
-- ⚠️ registered_through 와 pending_due_at 을 하나로 합치지 말 것. 앞은 "어디까지 등록했나"(등록자만
--    올린다), 뒤는 "무엇이 아직 안 돌았나"(실행자가 지운다)다. 합치면 실행자가 워터마크를 건드리게
--    되고, 실패한 회차가 "등록된 적 없는" 상태로 되돌아가 같은 발화가 무한히 재등록된다.
--
-- ⚠️ pending_expires_at 은 등록 때 한 번 정해지고 그 뒤로 움직이지 않는다. 기준이 "요청이 등록된
--    시각"이나 "마지막 시도 시각"이면 실패를 반복하는 회차가 스스로 기한을 계속 늘려, 다음 발화가
--    코앞인데도 낡은 발화를 계속 붙잡는다. 기준은 발화 그 자체다 — due + (다음 발화 - due)/2,
--    곧 매시 회차는 발화 30분 뒤, 일 회차는 12시간 뒤다. 간격의 절반이라 주기를 바꾸면 기한도
--    따라 움직이고, 분 단위 cron을 넣어도 "태어나자마자 만료"가 성립하지 않는다.
--
-- ⚠️ 완료 칸과 만료 칸을 합치지 말 것. 만료는 "다음 회차가 코앞이라 이번 것은 돌리지 않는다"이지
--    "돌았다"가 아니다. 한 칸에 적으면 "마지막으로 실제로 집계가 돈 시각"을 물을 곳이 없어진다.
--
-- 상태값(PROCESSING)도 lease 만료 시각도 두지 않는다. 실행 중이라는 사실은 이 행에 걸린
-- 트랜잭션의 X 락 그 자체이고, 그 락은 커밋·롤백·연결 종료 어느 쪽으로도 반드시 풀린다.
-- 칸으로 적으면 "적어 놓고 죽은" 상태를 회수하는 배치가 따라온다 (V42 주석과 같은 이유).
CREATE TABLE place_stats_job_schedules
(
    job_kind              VARCHAR(32) NOT NULL COMMENT '회차 종류. 코드의 PlaceStatsJobKind 이름과 같다',
    registered_through    DATETIME(3) NOT NULL COMMENT '이 시각까지의 발화는 등록을 마쳤다. 등록자만 올린다',
    pending_due_at        DATETIME(3)     NULL COMMENT '대기 중인 발화. 밀린 것이 여럿이면 가장 최신 하나로 접힌다',
    pending_expires_at    DATETIME(3)     NULL COMMENT '이 시각을 넘기면 새 시도를 시작하지 않는다. 발화에 매인 값이라 재시도로 밀리지 않는다',
    last_completed_due_at DATETIME(3)     NULL COMMENT '실제로 집계가 돈 마지막 발화',
    last_completed_at     DATETIME(3)     NULL COMMENT '완료로 표시한 시각. 커밋 시각은 이보다 조금 뒤다',
    last_expired_due_at   DATETIME(3)     NULL COMMENT '돌리지 않고 버린 마지막 발화',
    last_expired_at       DATETIME(3)     NULL COMMENT '버린 시각',
    updated_at            DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (job_kind)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 워터마크의 출발점은 "이 마이그레이션이 도는 지금"이다. 0이나 먼 과거를 넣으면 롤아웃 첫 폴이
-- 몇 년 전 발화를 "밀린 회차"로 보고 곧바로 네 배치를 다 돌린다. 지금으로 두면 그다음 발화부터
-- 등록되고 과거 이력은 만들어지지 않는다.
--
-- 시각 칸은 전부 KST 벽시계다. 애플리케이션이 LocalDateTime으로 읽고 쓰며 발화 계산도
-- Asia/Seoul로 하기 때문이다. 그래서 여기서 NOW(3)을 쓰지 않는다 — NOW()는 DB 세션의 시간대를
-- 따르고, 그 세션이 UTC면 시드만 9시간 과거가 되어 첫 폴이 하루치를 밀린 발화로 본다.
-- JVM의 TimezoneConfig는 DB 세션의 시간대를 정하지 않는다.
INSERT INTO place_stats_job_schedules (job_kind, registered_through)
VALUES ('REVIEW_COUNT', DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 9 HOUR)),
       ('BOOKMARK_DELTA', DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 9 HOUR)),
       ('POPULAR_SCORE', DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 9 HOUR)),
       ('COUNT_SAFETY', DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 9 HOUR));
