#!/usr/bin/env bash
# 정합성 게이트 — 캐시 on/off 두 모드의 응답을 같은 토큰·같은 요청으로 채취한다.
#
# 사용: ./capture-responses.sh <mode>   (mode = on | off)
# 산출: ../results/metrics/gate-<mode>.{1,2,3,4,5}.json
#
# 같은 토큰을 쓰는 것이 계약이다 — isBookmarked가 사용자별이라 토큰이 다르면
# 응답이 달라지고, 그 차이가 캐시 탓인지 사용자 탓인지 갈리지 않는다.
set -euo pipefail

MODE="${1:?mode 를 지정하라 (on|off)}"
BASE="http://localhost:8082/api/places"
OUT="$(cd "$(dirname "$0")/../results/metrics" && pwd)"
TOKEN="$(sed -n 2p "$(dirname "$0")/../../../data/users.csv" | tr -d '\r')"

req() { curl -sS -H "Authorization: Bearer $TOKEN" "$1"; }

# ① 시 단위 무필터 1페이지 (인기순)
req "$BASE?townId=201&isBookmarkSearch=false&sort=POPULAR&size=20" > "$OUT/gate-$MODE.1.json"
# ② 동네 + 메인 태그 (인기순)
req "$BASE?townId=301&isBookmarkSearch=false&sort=POPULAR&mainTagId=1&size=20" > "$OUT/gate-$MODE.2.json"
# ③ ①의 nextCursor로 2페이지 — 커서 좌표계까지 같은지 본다
CUR="$(python3 -c "import json,sys;print(json.load(open('$OUT/gate-$MODE.1.json'))['data']['nextCursor'] or '')")"
req "$BASE?townId=201&isBookmarkSearch=false&sort=POPULAR&size=20&cursor=$CUR" > "$OUT/gate-$MODE.3.json"
# ④ 시 단위 무필터 1페이지 (최신순) — 기준 테이블이 places라 인기순과 경로가 다르다
req "$BASE?townId=201&isBookmarkSearch=false&sort=LATEST&size=20" > "$OUT/gate-$MODE.4.json"
# ⑤ 시 단위 + 태그 3개 (인기순) — 부하 시나리오 비중이 가장 큰 조합
req "$BASE?townId=201&isBookmarkSearch=false&sort=POPULAR&mainTagId=1&subTagAIdList=7&subTagBIdList=11&size=20" \
  > "$OUT/gate-$MODE.5.json"

echo "채취 완료: $OUT/gate-$MODE.{1..5}.json"
