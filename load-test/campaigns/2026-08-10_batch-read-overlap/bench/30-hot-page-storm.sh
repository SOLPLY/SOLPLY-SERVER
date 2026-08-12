#!/usr/bin/env bash
# P-양성 대조: place_stats의 **한 줌의 인접 페이지**에 동시 20 커넥션으로 증분 UPDATE를 퍼붓는다.
# 재는 대상이 아니라 계측의 검출력 증명 전용이다 — 여기서 래치 대기·언두 카운터·히스토리 리스트가
# 안 움직이면 본 라운드의 0은 "충돌이 없다"가 아니라 "안 보고 있다"는 뜻이므로 진입 금지(사전 등록 §5-2).
#
# 왜 인접 place_id 200개인가: place_stats는 행폭이 약 50B라 200행이 16KB 페이지 한두 장에 모인다.
# 같은 페이지를 20 커넥션이 두들겨야 페이지 래치와 언두 체인이 실제로 길어진다 — 전 범위에 흩뿌리면
# 같은 UPDATE 수로도 경합이 생기지 않는다.
#
# ⚠️ bookmark_count가 원본과 어긋난다(오염). 20-batch-separated.sql 1회 발화가 원본에서 전량
#    재계산하므로 복구는 그 한 줄이다. 그래서 P 라운드를 캠페인 맨 마지막에 둔다.
#
# 사용: ./30-hot-page-storm.sh <duration_s>
set -euo pipefail

DURATION="${1:?duration_s 필요 (예: 15)}"
CONNS=20
WINDOW=200

MYSQL=(docker exec -i solply-bench-mysql mysql -uroot -prootpwd solply_bench_db)
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# PK 오름차순 앞 200행 = 클러스터드 인덱스에서 물리적으로 붙어 있는 200행. id가 듬성해도 성립한다.
PIDS=()
while read -r pid; do PIDS+=("$pid"); done < <(
  "${MYSQL[@]}" -N -B -e "SELECT place_id FROM place_stats ORDER BY place_id LIMIT $WINDOW;" 2>/dev/null)
N="${#PIDS[@]}"
[ "$N" -gt 0 ] || { echo "[storm] place_stats가 비어 있다" >&2; exit 2; }
echo "[storm] 대상 place_id ${PIDS[0]}..${PIDS[$((N-1))]} (${N}행) · 동시 $CONNS · ${DURATION}s"

# 실제 적용 횟수는 DB에서 뽑는다. 클라이언트가 센 발화 수는 파이프에 선행 적재된 몫까지 포함해
# 과대집계된다 — bookmark_count 총합의 차이가 성공한 UPDATE 수와 정확히 같다.
# (전제: 이 창에 place_stats를 건드리는 다른 쓰기가 없다 — 배치 cron은 비활성이다.)
BEFORE="$("${MYSQL[@]}" -N -B -e \
  "SELECT COALESCE(SUM(bookmark_count),0) FROM place_stats WHERE place_id BETWEEN ${PIDS[0]} AND ${PIDS[$((N-1))]};" 2>/dev/null)"

for w in $(seq 1 "$CONNS"); do
  (
    issued=0
    SECONDS=0
    {
      while [ "$SECONDS" -lt "$DURATION" ]; do
        printf 'UPDATE place_stats SET bookmark_count = bookmark_count + 1 WHERE place_id = %s;\n' \
          "${PIDS[$((RANDOM % N))]}"
        issued=$((issued + 1))
      done
      echo "$issued" > "$TMP/w$w"
    } | "${MYSQL[@]}" >/dev/null 2>&1
  ) &
done
wait || true

AFTER="$("${MYSQL[@]}" -N -B -e \
  "SELECT COALESCE(SUM(bookmark_count),0) FROM place_stats WHERE place_id BETWEEN ${PIDS[0]} AND ${PIDS[$((N-1))]};" 2>/dev/null)"

ISSUED=0
for w in $(seq 1 "$CONNS"); do
  [ -f "$TMP/w$w" ] && ISSUED=$((ISSUED + $(cat "$TMP/w$w")))
done

APPLIED=$((AFTER - BEFORE))
echo "[storm] 적용된 UPDATE 총 ${APPLIED}회 (초당 약 $((APPLIED / DURATION))회) · 클라이언트 발화 ${ISSUED}회"
echo "[storm] bookmark_count 오염분은 20-batch-separated.sql 1회 발화로 재계산·복구한다"
