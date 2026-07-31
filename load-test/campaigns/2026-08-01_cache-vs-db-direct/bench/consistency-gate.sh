#!/usr/bin/env bash
# 정합성 게이트 — 캐시(A)와 DB 직행(B)의 응답 body가 같은가 (브리프 §1)
#
# 이 게이트가 판정 이전의 전제인 이유: 두 모드의 랭킹 소스가 place_stats로 통일됐으므로
# 응답이 같아야 한다. 다르면 B 구현 버그이고, 그 상태의 지연·처리량 비교는 의미가 없다
# (다른 일을 하는 두 구현을 비교하게 된다).
#
# ⚠️ 이 게이트가 "무언가를 본다"는 것 자체에 전제가 있다 — 모든 active 장소가 place_stats
# 행을 가져야 한다. db 모드 POPULAR는 place_stats를 기준 테이블로 잡아 ps 행이 없는 장소를
# 누락하는데(PlaceListDbQueryRepository#findPopularRows javadoc), 그 상태로 diff가 비면
# 게이트가 통과한 게 아니라 아무것도 보지 않은 것이다. 아래 0단계가 그것을 먼저 센다.
#
# 사용법:
#   ./consistency-gate.sh capture cache    # 캐시 모드에서 응답 저장
#   ./consistency-gate.sh capture db       # (모드 전환 후) db 모드에서 응답 저장
#   ./consistency-gate.sh diff             # 둘을 대조
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
OUT="$(cd "$(dirname "$0")/.." && pwd)/results/gate"
LB="http://localhost:8082"
mkdir -p "$OUT"

TOKEN="$(sed -n '2p' "$ROOT/load-test/data/users.csv")"
AUTH=(-H "Authorization: Bearer $TOKEN")

# jq가 없으면 python으로 정규화한다. 응답 키 순서는 보장되지 않으므로 정렬해 비교한다.
norm() { python3 -c 'import sys,json;print(json.dumps(json.load(sys.stdin),sort_keys=True,ensure_ascii=False,indent=1))'; }

precondition() {
  echo "── 0. 게이트의 전제: active 장소 중 place_stats 행이 없는 것"
  docker exec solply-bench-mysql mysql -uroot -prootpwd solply_bench_db -N -e "
    SELECT CONCAT('  active 장소 ', COUNT(*), ' / ps 행 없는 장소 ',
      SUM(CASE WHEN ps.place_id IS NULL THEN 1 ELSE 0 END))
    FROM places p LEFT JOIN place_stats ps ON ps.place_id = p.id
    WHERE p.active = 1;
    SELECT CONCAT('  ps.active=0 인데 places.active=1 (재활성화 미반영) ', COUNT(*))
    FROM places p JOIN place_stats ps ON ps.place_id = p.id
    WHERE p.active = 1 AND ps.active = 0;" 2>/dev/null
  echo "  → 위 두 값이 0이 아니면 diff가 비어도 등가의 증거가 아니다"
}

capture() {
  local mode="$1"
  echo "── $mode 모드 응답 저장"

  # ① 시 단위 무필터 1페이지 (leaf 18개 합집합 — filesort 경로)
  curl -sS "${AUTH[@]}" "$LB/api/places?townId=201&isBookmarkSearch=false&sort=POPULAR&size=20" \
    | norm > "$OUT/$mode-1-city.json"

  # ② 동네 + 메인 태그 필터
  curl -sS "${AUTH[@]}" "$LB/api/places?townId=301&isBookmarkSearch=false&sort=POPULAR&mainTagId=1&size=20" \
    | norm > "$OUT/$mode-2-tag.json"

  # ③ ①의 nextCursor로 2페이지 — 커서가 모드 간 호환되는지가 여기서 드러난다
  local cursor
  cursor="$(python3 -c "import json;print(json.load(open('$OUT/$mode-1-city.json'))['data']['nextCursor'] or '')")"
  if [ -z "$cursor" ]; then
    echo "  ⚠️ nextCursor가 없다 — ③을 건너뛴다 (페이지가 1장뿐이라는 뜻이므로 확인 필요)"
    return
  fi
  echo "  cursor=$cursor"
  curl -sS "${AUTH[@]}" \
    "$LB/api/places?townId=201&isBookmarkSearch=false&sort=POPULAR&size=20&cursor=$cursor" \
    | norm > "$OUT/$mode-3-page2.json"

  # ④ 커서 교차 검증용으로 커서 자체도 남긴다 — 두 모드가 같은 토큰을 발급해야 한다
  echo "$cursor" > "$OUT/$mode-cursor.txt"
}

do_diff() {
  local fail=0
  for n in 1-city 2-tag 3-page2; do
    if [ ! -f "$OUT/cache-$n.json" ] || [ ! -f "$OUT/db-$n.json" ]; then
      echo "⚠️  $n: 한쪽 응답이 없다 — capture를 두 모드 모두 돌렸는가"; fail=1; continue
    fi
    if diff -q "$OUT/cache-$n.json" "$OUT/db-$n.json" >/dev/null; then
      echo "✅ $n: 동일"
    else
      echo "❌ $n: 다르다"; diff "$OUT/cache-$n.json" "$OUT/db-$n.json" | head -40; fail=1
    fi
  done
  if [ -f "$OUT/cache-cursor.txt" ] && [ -f "$OUT/db-cursor.txt" ]; then
    if diff -q "$OUT/cache-cursor.txt" "$OUT/db-cursor.txt" >/dev/null; then
      echo "✅ cursor: 두 모드가 같은 토큰을 발급"
    else
      echo "❌ cursor: 발급 토큰이 다르다"
      echo "   cache=$(cat "$OUT/cache-cursor.txt")"; echo "   db=$(cat "$OUT/db-cursor.txt")"; fail=1
    fi
  fi
  [ $fail -eq 0 ] && echo && echo "게이트 통과 — 측정 진행 가능" \
                  || { echo; echo "게이트 실패 — 측정하지 말고 사용자 보고 (브리프 §1)"; exit 1; }
}

case "${1:-}" in
  capture) precondition; capture "${2:?mode(cache|db)}" ;;
  diff)    do_diff ;;
  *)       echo "usage: $0 {capture cache|capture db|diff}"; exit 2 ;;
esac
