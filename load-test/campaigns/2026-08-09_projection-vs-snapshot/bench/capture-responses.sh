#!/usr/bin/env bash
# 정합성 게이트 — 세 모드(entity/projection/snapshot)의 응답을 같은 토큰·같은 요청으로 채취한다.
# 2026-08-07_place-skeleton-cache/bench/capture-responses.sh의 계승판. 바뀐 것 둘:
#   ① 모드가 on/off 둘이 아니라 셋이다
#   ② size가 20이 아니라 **10**이다 — 이 캠페인의 페이지 크기가 운영 기본값 10이고(사전 등록 §7),
#      골격 5필드가 페이지의 모든 행에 실리므로 게이트도 같은 크기에서 봐야 한다
#
# 같은 토큰을 쓰는 것이 계약이다 — isBookmarked가 사용자별이라 토큰이 다르면 응답이 달라지고,
# 그 차이가 모드 탓인지 사용자 탓인지 갈리지 않는다.
#
# 사용: ./capture-responses.sh <mode>   (mode = entity | projection | snapshot)
# 산출: ../results/metrics/gate-<mode>.{1,2,3,4,5}.json
set -euo pipefail

MODE="${1:?mode 를 지정하라 (entity|projection|snapshot)}"
BASE="http://localhost:8082/api/places"
OUT="$(cd "$(dirname "$0")/../results/metrics" && pwd)"
TOKEN="$(sed -n 2p "$(dirname "$0")/../../../data/users.csv" | tr -d '\r')"

req() { curl -sS -H "Authorization: Bearer $TOKEN" "$1"; }

# ① 시 단위 무필터 1페이지 (인기순)
req "$BASE?townId=201&isBookmarkSearch=false&sort=POPULAR&size=10" > "$OUT/gate-$MODE.1.json"
# ② 동네 + 메인 태그 (인기순)
req "$BASE?townId=301&isBookmarkSearch=false&sort=POPULAR&mainTagId=1&size=10" > "$OUT/gate-$MODE.2.json"
# ③ ①의 nextCursor로 2페이지 — 커서 좌표계까지 같은지 본다
CUR="$(python3 -c "import json;print(json.load(open('$OUT/gate-$MODE.1.json'))['data']['nextCursor'] or '')")"
req "$BASE?townId=201&isBookmarkSearch=false&sort=POPULAR&size=10&cursor=$CUR" > "$OUT/gate-$MODE.3.json"
# ④ 시 단위 무필터 1페이지 (최신순) — 기준 테이블이 places라 인기순과 경로가 다르다
req "$BASE?townId=201&isBookmarkSearch=false&sort=LATEST&size=10" > "$OUT/gate-$MODE.4.json"
# ⑤ 시 단위 + 태그 3개 (인기순) — 부하 시나리오 비중이 가장 큰 조합
req "$BASE?townId=201&isBookmarkSearch=false&sort=POPULAR&mainTagId=1&subTagAIdList=7&subTagBIdList=11&size=10" \
  > "$OUT/gate-$MODE.5.json"

echo "채취 완료: $OUT/gate-$MODE.{1..5}.json"
