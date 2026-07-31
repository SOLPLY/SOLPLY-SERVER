#!/usr/bin/env bash
# 항목 3 — `places`에 북마크 카운터를 비정규화했다면 락 경합이 실제 피해였는가.
#
# 사용: ./denorm-contention.sh [세션수=16] [세션당 반복=50]
#
# ── 이건 "재현"이 아니라 "만들어서 재는 것"이다 ──────────────────────────────
# 카운터 컬럼은 채택된 적이 없어 구현체가 없다. 일어난 적 없는 현상은 재현할 수 없다.
# 그래서 실험용 모형을 만든다. 모형이 실제 구조를 대표해야 결과가 의미를 갖는다:
#
#   실험군(비정규화 가정) = INSERT INTO bookmarks + UPDATE places SET cnt=cnt+1  ← 한 트랜잭션
#   대조군(현행 채택안)   = INSERT INTO bookmarks                                 ← 한 트랜잭션
#
# 카운터 UPDATE만 때리는 모형은 쓰지 않는다 — 실제 구현이라면 북마크 삽입과 카운터 증가가
# 같은 트랜잭션에 들어가므로, 카운터만 재면 **락 보유 시간이 실제보다 짧게 나와** 기각 근거를
# 실제보다 약하게 만든다.
#
# ── 판정 목표가 "락 경합이 존재하는가"가 아니다 ────────────────────────────────
# 같은 행에 대한 동시 UPDATE가 X락으로 직렬화된다는 건 InnoDB의 문서화된 동작이라
# 재 봐야 "그렇다"가 나올 뿐이다. 이 측정이 답하는 질문은 그 다음이다:
#   **우리 규모(설계 §1.1 피크 22 req/s)에서 그 직렬화가 실제 피해인가?**
# 무시할 수준이면 설계 §2.3에 락 근거를 추가하지 않는다. §2.3의 현행 근거는 전부
# 설계 원칙(축이 스키마에 샌다 / 2급 데이터 / 정확도 요구 분리)이고 성능 근거가 아니다.
#
# 조건 2종:
#   HOT      — 전 세션이 1위 장소(10001) 한 행에 몰린다. 최악의 경우
#   SPREAD   — 상위 10곳에 분산. Zipf 상위권에서 현실적으로 일어날 만한 경우
#
# ── 경합 전용 유저를 새로 만드는 이유 ─────────────────────────────────────────
# 기존 시드 유저를 쓰면 uk_bookmark_user_target(user_id, target_type, target_id) 충돌이 난다.
# 1위 장소는 이미 유저 10만 중 69,784명이 북마크해 뒀고, 상위 10곳을 전부 피한 유저는
# 실측 142명뿐이라 세션 16 × 반복 50 = 800건을 댈 수 없다.
# duplicate key 오류가 섞이면 **락 대기와 구별할 수 없게 되므로** 충돌 가능성 자체를 없앤다.
# 만든 유저와 그 북마크는 종료 시 전부 지운다 — 시드 데이터는 건드리지 않는다.
#
# ⚠️ 임시 컬럼도 벤치 DB에만 만들고 끝나면 지운다. 마이그레이션·엔티티는 건드리지 않는다.
set -euo pipefail
cd "$(dirname "$0")"
SESSIONS="${1:-16}"
ITER="${2:-50}"
OUT=../results/metrics/denorm-contention.txt
HOT_PLACE=10001
NEED=$((SESSIONS * ITER))

ms_now() { python3 -c 'import time;print(int(time.time()*1000))'; }
status() { ./mysql-root.sh -N -e "SHOW GLOBAL STATUS LIKE '$1';" | awk '{print $2}'; }

echo "=== 비정규화 카운터 락 경합 / 세션 $SESSIONS × 반복 $ITER = ${NEED}tx / $(date +%T) ===" | tee "$OUT"

# ---------- 준비 ----------
./mysql-root.sh -e "ALTER TABLE places ADD COLUMN bench_bookmark_cnt INT NOT NULL DEFAULT 0;" 2>/dev/null || true

TOP10=$(./mysql-root.sh -N -e "SELECT place_id FROM place_stats ORDER BY bookmark_count DESC LIMIT 10;" | tr '\n' ' ')
echo "[준비] HOT=$HOT_PLACE / SPREAD=$TOP10" | tee -a "$OUT"

cleanup() {
  ./mysql-root.sh -e "
    DELETE b FROM bookmarks b JOIN users u ON u.id = b.user_id
      WHERE u.email LIKE 'contend\\_%@bench.local';
    DELETE FROM users WHERE email LIKE 'contend\\_%@bench.local';
    DROP TABLE IF EXISTS bench_contend_users;
    ALTER TABLE places DROP COLUMN bench_bookmark_cnt;" 2>/dev/null || true
}
trap cleanup EXIT

# 경합 전용 유저 생성. ROW_NUMBER는 LIMIT 없는 집합 위에서 매긴다 —
# `SELECT ROW_NUMBER() ... FROM users ... ORDER BY id DESC LIMIT N`으로 쓰면 윈도 함수가
# LIMIT **이전** 10만 행 전체에 번호를 매겨 rn이 1..N 범위를 벗어난다 (실측: 전 arm 성공 0건).
./mysql-root.sh -e "
  DELETE b FROM bookmarks b JOIN users u ON u.id = b.user_id
    WHERE u.email LIKE 'contend\\_%@bench.local';
  DELETE FROM users WHERE email LIKE 'contend\\_%@bench.local';
  SET @@cte_max_recursion_depth = 100000;
  INSERT INTO users (role, nickname, email, is_new_user, is_deleted)
  WITH RECURSIVE seq AS (SELECT 1 n UNION ALL SELECT n+1 FROM seq WHERE n < $NEED)
  SELECT 'USER', CONCAT('contend_', n), CONCAT('contend_', n, '@bench.local'), FALSE, FALSE FROM seq;
  DROP TABLE IF EXISTS bench_contend_users;
  CREATE TABLE bench_contend_users (rn INT PRIMARY KEY, user_id BIGINT);
  INSERT INTO bench_contend_users (rn, user_id)
  SELECT ROW_NUMBER() OVER (ORDER BY id), id FROM users WHERE email LIKE 'contend\\_%@bench.local';"

POOL=$(./mysql-root.sh -N -e "SELECT COUNT(*) FROM bench_contend_users;")
[ "$POOL" -eq "$NEED" ] || { echo "[준비] 유저 풀 $POOL != 필요 $NEED — 중단" >&2; exit 1; }
echo "[준비] 경합 전용 유저 ${POOL}명 생성" | tee -a "$OUT"

# ---------- 워커 ----------
# $1 arm(denorm|current)  $2 mode(hot|spread)  $3 세션 인덱스
worker() {
  local ARM="$1" MODE="$2" IDX="$3"
  local BASE=$(( (IDX - 1) * ITER + 1 ))
  local SQL=""
  for k in $(seq 0 $((ITER - 1))); do
    local RN=$((BASE + k)) PLACE
    if [ "$MODE" = "hot" ]; then
      PLACE=$HOT_PLACE
    else
      PLACE=$(echo $TOP10 | cut -d' ' -f$(( (IDX + k) % 10 + 1 )))
    fi
    SQL+="START TRANSACTION;"
    SQL+="INSERT INTO bookmarks (user_id, target_type, target_id, created_at)
          SELECT user_id, 'PLACE', $PLACE, NOW() FROM bench_contend_users WHERE rn=$RN;"
    [ "$ARM" = "denorm" ] && \
      SQL+="UPDATE places SET bench_bookmark_cnt = bench_bookmark_cnt + 1 WHERE id = $PLACE;"
    SQL+="COMMIT;"
  done
  echo "$SQL" | ./mysql.sh > /dev/null 2>&1
}

run_arm() {
  local ARM="$1" MODE="$2" LABEL="${3:-}"
  # 각 라운드는 깨끗한 상태에서 시작한다 — 앞 라운드가 남긴 북마크가 있으면
  # 다음 라운드가 duplicate key로 죽어 락이 아닌 이유로 실패한다.
  ./mysql-root.sh -e "DELETE b FROM bookmarks b JOIN users u ON u.id = b.user_id
                      WHERE u.email LIKE 'contend\\_%@bench.local';" 2>/dev/null

  # Innodb_row_lock_* 는 **전역 누적**이라 FLUSH STATUS로 리셋되지 않는다.
  # 절대값을 읽으면 앞선 락 덤프 실험의 잔재(최대 24,488ms)를 이 실험의 결과로 오해한다 (실측).
  # 반드시 구간 diff로 본다.
  local W0 T0S W1 T1S
  W0=$(status Innodb_row_lock_waits); T0S=$(status Innodb_row_lock_time)

  local T0 T1
  T0=$(ms_now)
  for i in $(seq 1 "$SESSIONS"); do worker "$ARM" "$MODE" "$i" & done
  wait
  T1=$(ms_now)

  W1=$(status Innodb_row_lock_waits); T1S=$(status Innodb_row_lock_time)
  local DW=$((W1 - W0)) DT=$((T1S - T0S)) ELAPSED=$((T1 - T0))
  local DONE
  DONE=$(./mysql-root.sh -N -e "SELECT COUNT(*) FROM bookmarks b JOIN users u ON u.id=b.user_id
                                WHERE u.email LIKE 'contend\\_%@bench.local';")
  [ -n "$LABEL" ] || printf '%-9s %-7s %7dms %9.1f tx/s  대기 %-6d 대기시간합 %-7dms 평균 %-7s  성공 %s/%s\n' \
    "$ARM" "$MODE" "$ELAPSED" \
    "$(python3 -c "print($DONE / ($ELAPSED/1000))")" \
    "$DW" "$DT" \
    "$(python3 -c "print(f'{$DT/$DW:.1f}ms' if $DW else '—')")" \
    "$DONE" "$NEED"
}

{
  echo
  printf '%-9s %-7s %9s %13s  %s\n' "구분" "조건" "총소요" "처리량" "락 대기 (구간 diff)"
  echo "--------------------------------------------------------------------------------------------------"
  run_arm current hot warmup    # 워밍업 — 커넥션·버퍼 준비가 섞인 첫 라운드는 버린다
  run_arm current hot
  run_arm denorm  hot
  run_arm current spread
  run_arm denorm  spread
} | tee -a "$OUT"

echo "[완료] $OUT"
