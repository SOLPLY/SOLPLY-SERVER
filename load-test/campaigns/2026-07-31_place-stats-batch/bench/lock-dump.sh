#!/usr/bin/env bash
# 항목 5·6·7 — 배치가 무엇을 잠그는가 (RR/RC 양쪽).
#
# 사용: ./lock-dump.sh {RR|RC}
#
# 방법: 홀더 세션이 배치 UPSERT를 실행한 뒤 **커밋하지 않고 붙잡고 있는 동안**
#   (a) 무슨 락이 잡혀 있는지 덤프하고            → 항목 5(place_reviews) · 6(places FK 부모 S락)
#   (b) 경합 세션 3종이 실제로 막히는지 잰다       → "누가 얼마나 막히는가"
#   (c) History list length를 함께 기록한다        → 항목 7 (purge 지연 baseline)
#
# 왜 앱이 아니라 SQL 세션인가: 앱은 RC로 고정돼 있어 RR 쪽을 잴 수 없다.
# 격리수준이 이 측정의 독립변수이므로 세션에서 직접 건다.
#
# ⚠️ 홀더는 항상 ROLLBACK으로 끝난다. place_stats를 갱신하지 않는다 —
#    항목 1이 만든 확정 스냅샷을 건드리면 안 되기 때문이다.
#
# ── 이 스크립트가 한 번 틀렸던 방식 (2026-07-31, 라운드 2건 폐기) ───────────────────
# 처음에는 "processlist에 SELECT SLEEP이 보이면 홀더가 락을 쥔 것"으로 판정했다. 두 가지가 깨졌다.
#   1) 앞선 실행이 남긴 홀더가 살아 있으면 **낡은 세션을 새 홀더로 오인**한다.
#      실제로 RC 덤프가 103초 전에 시작된 트랜잭션을 찍었고, RR 덤프는
#      INNODB STATUS(12,640건)와 data_locks(790,286건)가 서로 다른 트랜잭션을 가리키는
#      자기모순 결과를 냈다.
#   2) 정리 루프의 `INFO LIKE '%SLEEP%'`가 **자기 자신의 쿼리 문자열에도 매칭**해
#      엉뚱한 id를 죽이고 정작 홀더는 살려 뒀다.
# 그래서 지금은 (a) 시작 전에 열린 트랜잭션이 0인지 **단언**하고,
# (b) 접두 매칭(`LIKE 'SELECT SLEEP(%'`)으로 자기 매칭을 없애고,
# (c) 덤프한 락 합계가 innodb_trx.trx_rows_locked와 **일치하는지 검증**한다.
# 세 번째가 핵심이다 — 합이 맞으면 그 관측이 한 트랜잭션의 것임이 자체 증명된다.
set -euo pipefail
cd "$(dirname "$0")"
ISO="${1:?RR 또는 RC}"
case "$ISO" in
  RR) LEVEL="REPEATABLE READ" ;;
  RC) LEVEL="READ COMMITTED" ;;
  *)  echo "RR 또는 RC" >&2; exit 1 ;;
esac
OUT=../results/metrics/lock-dump-$ISO.txt
HOLDER_LOG=../results/metrics/lock-dump-$ISO.holder.log
CAT='2026-07-31 02:00:00'
FREE_USER=372145   # 10001을 북마크하지 않은 벤치 유저 — uk_bookmark_user_target 충돌 회피

# 접두 매칭. '%SLEEP%'는 이 쿼리 자신에게도 걸린다.
HOLDER_PRED="INFO LIKE 'SELECT SLEEP(%'"

kill_holder() {
  ./mysql-root.sh -N -e "SELECT ID FROM information_schema.processlist WHERE $HOLDER_PRED;" 2>/dev/null \
    | while read -r id; do [ -n "$id" ] && ./mysql-root.sh -e "KILL $id;" >/dev/null 2>&1 || true; done
  # ⚠️ `kill "${HOLDER:-0}"`로 쓰면 안 된다 — 프리플라이트에서는 HOLDER가 아직 없어 `kill 0`이 되고,
  # 그건 **프로세스 그룹 전체**에 시그널을 보내 스크립트가 자기 자신을 죽인다 (실측: exit 144).
  [ -n "${HOLDER:-}" ] && kill "$HOLDER" 2>/dev/null || true
}

# ---------- 0) 프리플라이트 ----------
echo "=== 락 덤프 / 격리수준 $LEVEL / $(date +%T) ===" | tee "$OUT"
kill_holder
for i in $(seq 1 20); do
  OPEN=$(./mysql-root.sh -N -e "SELECT COUNT(*) FROM information_schema.innodb_trx;")
  [ "$OPEN" -eq 0 ] && break
  sleep 1
done
[ "${OPEN:-1}" -eq 0 ] || {
  echo "[preflight] 열린 트랜잭션이 남아 있다 ($OPEN건). 앞선 실행의 홀더를 정리하고 다시 시작할 것" >&2
  ./mysql-root.sh -t -e "SELECT trx_id, trx_mysql_thread_id, trx_state, trx_rows_locked FROM information_schema.innodb_trx;" >&2
  exit 1
}
echo "[preflight] 열린 트랜잭션 0건 — 깨끗한 상태에서 시작" | tee -a "$OUT"

# ---------- 1) 홀더: UPSERT 후 커밋하지 않고 붙잡는다 ----------
{
  cat <<SQL
SET SESSION TRANSACTION ISOLATION LEVEL $LEVEL;
START TRANSACTION;
SET @t0 = NOW(6);
INSERT INTO place_stats (place_id, town_id, active, popular_score,
                         bookmark_count, review_count, avg_rating, calculated_at)
SELECT p.id, p.town_id, p.active,
       COALESCE(b.score,0) + COALESCE(r.score,0),
       COALESCE(b.cnt,0), COALESCE(r.cnt,0), r.avg_rating, '$CAT'
FROM places p
LEFT JOIN (SELECT bm.target_id AS place_id, COUNT(*) cnt,
                  SUM(1.0*POW(0.5, TIMESTAMPDIFF(SECOND, bm.created_at,'$CAT')/86400.0/90.0)) score
           FROM bookmarks bm WHERE bm.target_type='PLACE' AND bm.created_at <= '$CAT'
           GROUP BY bm.target_id) b ON b.place_id = p.id
LEFT JOIN (SELECT pr.place_id, COUNT(*) cnt, AVG(pr.rating) avg_rating,
                  SUM(3.0*(pr.rating-3)*POW(0.5, TIMESTAMPDIFF(SECOND, pr.created_at,'$CAT')/86400.0/90.0)) score
           FROM place_reviews pr WHERE pr.created_at <= '$CAT'
           GROUP BY pr.place_id) r ON r.place_id = p.id
ON DUPLICATE KEY UPDATE
  town_id=VALUES(town_id), active=VALUES(active), popular_score=VALUES(popular_score),
  bookmark_count=VALUES(bookmark_count), review_count=VALUES(review_count),
  avg_rating=VALUES(avg_rating), calculated_at=VALUES(calculated_at);
SELECT TIMESTAMPDIFF(MICROSECOND, @t0, NOW(6))/1000.0 AS upsert_ms;
SELECT SLEEP(300);
ROLLBACK;
SQL
} | ./mysql.sh > "$HOLDER_LOG" 2>&1 &
HOLDER=$!
trap kill_holder EXIT

# ---------- 2) UPSERT가 끝나 락을 쥔 시점까지 대기 ----------
# SLEEP은 UPSERT 다음 문장이다 — 그것이 보이면 락은 이미 전부 잡혀 있다.
# RR은 소스 전 행을 잠그느라 RC보다 오래 걸리므로 넉넉히 기다린다.
echo "[wait] UPSERT 완료 대기…"
WAITED=0
for i in $(seq 1 600); do
  N=$(./mysql-root.sh -N -e "SELECT COUNT(*) FROM information_schema.processlist WHERE $HOLDER_PRED;" 2>/dev/null || echo 0)
  if [ "${N:-0}" -ge 1 ]; then WAITED=$i; break; fi
  sleep 0.5
done
[ "$WAITED" -eq 0 ] && { echo "[wait] UPSERT가 끝나지 않았다 — 중단" >&2; exit 1; }
echo "[wait] ${WAITED}회 폴링(약 $((WAITED / 2))초) 만에 홀더 확보" | tee -a "$OUT"
# 홀더 로그는 다른 프로세스가 쓰는 중이라 이 시점에 아직 flush 안 됐을 수 있다.
# set -e + pipefail에서 grep이 빈 파일에 실패하면 스크립트째로 죽는다 (실측) — 관대하게 둔다.
UPSERT_MS=$( (grep -A 2 upsert_ms "$HOLDER_LOG" 2>/dev/null || true) | tail -1 )
echo "[holder] UPSERT 소요(ms): ${UPSERT_MS:-(로그 미도달 — 종료 후 $HOLDER_LOG 확인)}" | tee -a "$OUT"

# ---------- 3) 락 덤프 ----------
# 권위 있는 수치는 innodb_trx.trx_rows_locked다 — 엔진이 들고 있는 집계라 락이 몇 건이든 즉시 답한다.
# data_locks는 **어느 테이블인지**를 가르는 보조 자료다 (락 하나당 행 하나를 만들어 내므로 무겁다).
{
  echo
  echo "--- 3.1 홀더 트랜잭션 (권위 수치) ---"
  ./mysql-root.sh -t -e "
    SELECT trx_id, trx_state, trx_isolation_level AS iso, trx_rows_locked, trx_rows_modified,
           TIMESTAMPDIFF(SECOND, trx_started, NOW()) AS active_sec
    FROM information_schema.innodb_trx;"

  echo "--- 3.2 테이블별 락 분포 (data_locks) ---"
  ./mysql-root.sh -t -e "
    SELECT /*+ MAX_EXECUTION_TIME(120000) */
           OBJECT_NAME AS tbl, LOCK_TYPE, LOCK_MODE, COUNT(*) AS cnt
    FROM performance_schema.data_locks
    GROUP BY OBJECT_NAME, LOCK_TYPE, LOCK_MODE
    ORDER BY cnt DESC;"

  echo "--- 3.3 정합성 검증: RECORD 락 합계 == trx_rows_locked 인가 ---"
  # 합이 맞으면 위 두 관측이 같은 한 트랜잭션의 것임이 자체 증명된다.
  # 어긋나면 다른 트랜잭션이 섞였다는 뜻이므로 그 라운드는 폐기해야 한다.
  ./mysql-root.sh -t -e "
    SELECT /*+ MAX_EXECUTION_TIME(120000) */
           (SELECT COUNT(*) FROM performance_schema.data_locks WHERE LOCK_TYPE='RECORD') AS data_locks_sum,
           (SELECT trx_rows_locked FROM information_schema.innodb_trx LIMIT 1)            AS trx_rows_locked,
           IF((SELECT COUNT(*) FROM performance_schema.data_locks WHERE LOCK_TYPE='RECORD')
              = (SELECT trx_rows_locked FROM information_schema.innodb_trx LIMIT 1),
              '일치 — 단일 트랜잭션 확정', '⚠️ 불일치 — 이 라운드 폐기') AS verdict;"

  echo "--- 3.4 History list length (항목 7) ---"
  ./mysql-root.sh -N -e "SHOW ENGINE INNODB STATUS\G" | grep -E "History list length"
} | tee -a "$OUT"

# ---------- 4) 경합 세션 — 누가 실제로 막히는가 ----------
# lock_wait_timeout을 3초로 낮춘다. 기본 50초면 "막힌다"를 확인하는 데만 50초가 든다.
contend() {
  local NAME="$1" SQL="$2" T0 T1 RC MS ERR
  T0=$(python3 -c 'import time;print(time.time())')
  set +e
  ERR=$(./mysql.sh -e "SET SESSION innodb_lock_wait_timeout=3; START TRANSACTION; $SQL ROLLBACK;" 2>&1)
  RC=$?
  set -e
  T1=$(python3 -c 'import time;print(time.time())')
  MS=$(python3 -c "print(f'{($T1-$T0)*1000:.0f}')")
  if [ $RC -eq 0 ]; then
    echo "  $NAME: 즉시 성공 (${MS}ms)"
  else
    echo "  $NAME: 차단 (${MS}ms) — $(echo "$ERR" | grep -oE 'ERROR [0-9]+ .*' | head -1)"
  fi
}

{
  echo
  echo "--- 4. 경합 세션 (innodb_lock_wait_timeout=3s) ---"
  contend "INSERT bookmarks (신규 북마크)" \
    "INSERT INTO bookmarks (user_id, target_type, target_id, created_at) VALUES ($FREE_USER,'PLACE',10001,NOW());"
  contend "INSERT place_reviews (신규 리뷰)" \
    "INSERT INTO place_reviews (user_id, place_id, visited_at, visit_time_slot, content, rating, created_at, updated_at) VALUES ($FREE_USER,10001,CURDATE(),'EVENING','락 경합 테스트',5,NOW(),NOW());"
  contend "UPDATE places (어드민 동네 비활성화)" \
    "UPDATE places SET active = false WHERE town_id = 301;"
} | tee -a "$OUT"

# ---------- 5) 홀더 정리 + 누수 확인 ----------
echo "[cleanup] 홀더 종료…" | tee -a "$OUT"
kill_holder
wait $HOLDER 2>/dev/null || true
trap - EXIT
sleep 2

./mysql-root.sh -t -e "
  SELECT (SELECT COUNT(*) FROM information_schema.innodb_trx) AS open_trx,
         (SELECT COUNT(*) FROM bookmarks WHERE user_id=$FREE_USER AND target_id=10001) AS leaked_bookmark,
         (SELECT COUNT(*) FROM place_reviews WHERE user_id=$FREE_USER AND content='락 경합 테스트') AS leaked_review,
         (SELECT COUNT(*) FROM places WHERE town_id=301 AND active=false) AS deactivated_places,
         (SELECT COUNT(*) FROM place_stats) AS place_stats_rows;" | tee -a "$OUT"

echo "[완료] $OUT"
