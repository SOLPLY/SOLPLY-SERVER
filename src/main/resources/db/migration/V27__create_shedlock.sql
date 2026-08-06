-- V27: ShedLock 락 테이블
--
-- @Scheduled는 인스턴스별로 독립 등록되므로 앱 2대가 매시 30분 배치를 각각 실행한다.
-- "동시 실행 안전(완결 스냅샷 + 마지막 커밋 승리)"은 사고로 겹쳤을 때 깨지지 않는다는
-- 성질이지 계획적 중복의 정당화가 아니다 — 매시 배치 전환으로 중복 비용이 하루 48회
-- 스캔이 됐고 인스턴스 수에 비례해 커진다 (2026-08-02 결정).
--
-- 동작: 회차마다 각 인스턴스가 "lock_until이 지났으면 now+lockAtMostFor로 갱신"을 시도한다.
-- UPDATE는 원자적이라 정확히 한 쪽만 성공하고, 성공한 쪽만 배치를 실행한다.
-- 락 보유 인스턴스가 죽어도 lock_until 경과 후 다음 회차를 남은 쪽이 잡는다 (자동 페일오버).
-- 시각 기준은 usingDbTime() — DB 서버 시각이라 인스턴스 시계 오차와 무관.
CREATE TABLE shedlock
(
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
