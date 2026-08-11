-- InnoDB synch 대기 계측 활성화 — 사전 등록 §5의 1급 지표(래치 대기 창 diff)가 서는 전제.
--
-- 실행: docker exec -i solply-bench-mysql mysql -uroot -prootpwd < tools/enable-instrumentation.sql
-- root가 필요하다 — performance_schema 설정 변경과 SET GLOBAL 권한.
--
-- ⚠️ 셋 다 **휘발성이다. MySQL이 재기동하면 사라진다** (setup_instruments·setup_consumers·
--    innodb_monitor_enable 전부 my.cnf에 없다). 앱 모드 전환은 `up -d --force-recreate`로 하고
--    그 명령은 MySQL 컨테이너도 다시 만들 수 있다 — **force-recreate 뒤에는 라운드 전에 이 파일을
--    반드시 다시 실행하고, 아래 검증 SELECT 3개로 켜진 것을 눈으로 확인한다.**
--    계측이 꺼진 채로 돈 라운드의 Δ는 전부 0이고, 그 0은 "경합 없음"이 아니라 "측정 안 함"이다.
--
-- 되돌리기(캠페인 종료 시): setup_instruments를 ENABLED='NO'로,
--   SET GLOBAL innodb_monitor_disable='module_buffer_page';

-- ① wait/synch/*/innodb/* — MySQL 8.0.46 기준 105개(mutex 88 + sxlock 15 + cond 2). 기본 전부 OFF다.
--    TIMED='YES'까지 켜야 SUM_TIMER_WAIT가 채워진다. ENABLED만 켜면 COUNT_STAR만 늘고 시간은 0이다.
UPDATE performance_schema.setup_instruments
   SET ENABLED = 'YES', TIMED = 'YES'
 WHERE NAME LIKE 'wait/synch/%innodb%';

-- ② events_waits_current — 창 diff에 쓰는 요약표(events_waits_summary_global_by_event_name)는
--    global_instrumentation만으로도 채워지지만, 이 소비자가 꺼져 있으면 라운드 중에 "지금 어느
--    래치에서 막혀 있나"를 즉석 확인할 수단이 없다. 진단 경로를 열어 두려고 함께 켠다.
UPDATE performance_schema.setup_consumers
   SET ENABLED = 'YES'
 WHERE NAME = 'events_waits_current';

-- ③ buffer_page_read_undo_log(언두 페이지 읽기) 카운터는 module_buffer_page 모듈 전체를 켜야 산다.
--    이 모듈은 페이지 종류별 읽기/쓰기를 전부 세므로 오버헤드가 있다 — 모든 라운드에 동일하게
--    얹히므로 팔 간 비교에는 영향이 없고, 이 캠페인의 절대 레이턴시는 타 캠페인과 비교하지 않는다(§7).
SET GLOBAL innodb_monitor_enable = 'module_buffer_page';

-- ── 검증 ─────────────────────────────────────────────────────────────────────
-- (1) 활성 인스트루먼트 수 — enabled_timed가 105여야 한다.
SELECT 'innodb_synch_instruments'                       AS check_name,
       COUNT(*)                                          AS total,
       SUM(ENABLED = 'YES' AND TIMED = 'YES')            AS enabled_timed
  FROM performance_schema.setup_instruments
 WHERE NAME LIKE 'wait/synch/%innodb%';

-- (2) module_buffer_page 카운터 상태 — enabled_count가 0이면 언두 페이지 읽기 지표를 못 쓴다.
SELECT 'module_buffer_page'                              AS check_name,
       COUNT(*)                                          AS total,
       SUM(STATUS = 'enabled')                           AS enabled_count,
       MAX(IF(NAME = 'buffer_page_read_undo_log', STATUS, NULL)) AS undo_read_status
  FROM information_schema.INNODB_METRICS
 WHERE SUBSYSTEM = 'buffer_page_io';

-- (3) 요약표에 innodb 행이 잡히는지 — 105행이 보여야 창 diff의 좌변이 존재한다.
--     (counted_rows는 아직 0일 수 있다. 부하 전이므로 정상이다.)
SELECT 'waits_summary_rows'                              AS check_name,
       COUNT(*)                                          AS innodb_rows,
       SUM(COUNT_STAR > 0)                               AS counted_rows
  FROM performance_schema.events_waits_summary_global_by_event_name
 WHERE EVENT_NAME LIKE 'wait/synch/%innodb%';
