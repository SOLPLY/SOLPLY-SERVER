#!/usr/bin/env bash
# 정합성 게이트(등록서 ⓓ) — 같은 요청 세트를 A(패치 전)/B(패치 후) 이미지에 쏴서 응답을 비교한다.
#
# 규율: 요청·토큰·파라미터가 양쪽에서 완전히 동일해야 diff가 의미를 갖는다. 그래서
#   - 토큰은 고정 user id로 발급한다 (isBookmarked가 유저에 의존하므로 랜덤 토큰은 못 쓴다)
#   - 스크롤 2페이지는 1페이지의 nextCursor를 **그 실행에서** 이어 쓴다 (커서는 점수 세대에
#     묶이므로 하드코딩하면 배치 발화 시 무의미해진다)
#   - 응답 JSON을 그대로 저장하고 비교는 바깥에서 한다 (여기서 판정하지 않는다)
#
# town 312를 쓰는 이유: 인기순 상위 20 중 16개가 태그 2개 이상(최대 6개)인 동네다 —
# C3(selectDistinct 제거 + toMap 병합)이 다중 태그 장소의 태그를 빠뜨리거나 중복시키면
# 여기서 드러난다. 태그 1개짜리 페이지만 보면 병합 경로를 아예 지나가지 않는다.
#
# 사용: consistency-gate.sh <A|B>
set -uo pipefail
cd "$(dirname "$0")"                          # -> campaigns/<캠페인>/bench/
SIDE="${1:?A 또는 B}"
OUT="../results/gate/$SIDE"
mkdir -p "$OUT"
BASE="http://localhost:8082"

# 고정 유저: 정상 유저 / 소프트 삭제 대상 유저 (id는 gate-users.txt에 기록해 양쪽이 같게 쓴다)
USERS_FILE="gate-users.txt"
if [ ! -f "$USERS_FILE" ]; then
  docker exec solply-bench-mysql mysql -N -uroot -prootpwd solply_bench_db \
    -e "SELECT id FROM users WHERE email LIKE 'bench\\_%@bench.local' ORDER BY id LIMIT 2" 2>/dev/null \
    | tr '\n' ' ' | awk '{print "NORMAL="$1"\nDELETED="$2}' > "$USERS_FILE"
fi
# shellcheck disable=SC1090
. "$USERS_FILE"
echo "[gate:$SIDE] NORMAL=$NORMAL DELETED=$DELETED"

TOK=$(node make-token.mjs "$NORMAL")
TOK_DEL=$(node make-token.mjs "$DELETED")

# 응답 본문 + 상태코드를 함께 남긴다. 상태코드만 같고 바디가 다른 회귀를 놓치지 않기 위함이다.
hit() {  # hit <이름> <URL> <토큰>
  local name="$1" url="$2" tok="$3"
  local code
  code=$(curl -s -o "$OUT/$name.json" -w '%{http_code}' -H "Authorization: Bearer $tok" "$url")
  echo "$code" > "$OUT/$name.code"
  printf '  %-28s HTTP %s  (%s bytes)\n' "$name" "$code" "$(wc -c < "$OUT/$name.json" | tr -d ' ')"
}

Q='isBookmarkSearch=false&sort=POPULAR&size=20'
hit town-301        "$BASE/api/places?townId=301&$Q"              "$TOK"
hit city-201        "$BASE/api/places?townId=201&$Q"              "$TOK"
hit tag-1           "$BASE/api/places?townId=301&$Q&mainTagId=1"  "$TOK"
hit tag-2           "$BASE/api/places?townId=305&$Q&mainTagId=2"  "$TOK"
hit multitag-312    "$BASE/api/places?townId=312&$Q"              "$TOK"

# 스크롤: p1의 nextCursor를 이어 p2를 받는다
hit scroll-p1       "$BASE/api/places?townId=201&$Q"              "$TOK"
CURSOR=$(python3 -c "import json;print(json.load(open('$OUT/scroll-p1.json'))['data'].get('nextCursor') or '')")
echo "$CURSOR" > "$OUT/scroll-cursor.txt"
hit scroll-p2       "$BASE/api/places?townId=201&$Q&cursor=$CURSOR" "$TOK"

# 보안 의미 불변 검증 2종
hit deleted-user    "$BASE/api/places?townId=301&$Q"              "$TOK_DEL"
hit admin-forbidden "$BASE/api/admin/towns"                       "$TOK"

# 배치 발화 판별용 — 커서·점수는 세대에 묶이므로 diff 해석에 필요하다
docker exec solply-bench-mysql mysql -N -uroot -prootpwd solply_bench_db \
  -e "SELECT COUNT(*), MAX(calculated_at) FROM place_stats" 2>/dev/null > "$OUT/place-stats-state.txt"
cat "$OUT/place-stats-state.txt" | sed 's/^/  place_stats: /'
echo "[gate:$SIDE] 저장 완료 -> $OUT"
