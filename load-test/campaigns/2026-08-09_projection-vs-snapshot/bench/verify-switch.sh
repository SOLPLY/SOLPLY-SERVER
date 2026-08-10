#!/usr/bin/env bash
# 모드 전환을 **실행된 SQL로** 검증한다 (사전 등록 §7).
#
# 설정값 확인으로는 부족하다 — 프로퍼티가 바인딩됐는데 분기가 다른 곳을 타거나, 스냅샷이 비어
# 전량 미스로 흐르면 설정은 맞고 SQL은 틀린다. 그래서 digest를 비우고 요청 1건을 보낸 뒤
# 골격 계열 문장의 실행 횟수를 센다.
#
#   entity     ent_places ≥1 · ent_images ≥1 · prj_* = 0
#   projection prj_places ≥1 · prj_images ≥1 · ent_* = 0
#   snapshot   넷 전부 0  +  기동 로그의 스냅샷 크기 > 0
#
# ── 문장 판별 지문 (alias로 가른다. 테이블 이름만으로는 갈리지 않는다) ─────────────────
#   ent_places  `FROM places p1_0`        하이버네이트 엔티티 셀렉트 = findPlacesWithTagsByIds
#   ent_images  `FROM place_images pii1_0` 컬렉션 배치 페치(LAZY 지연로딩)
#   prj_places  `m . tag_active` + `p . id IN (`  PlaceSkeletonLoader.loadByIds의 places 문장
#   prj_images  `FROM place_images pi` + `IN (`   같은 로더의 썸네일 문장
#   bld_places  `m . tag_active` (IN 없음)        rebuild() 전량판 — 스냅샷 빌드가 남긴 흔적
#   bld_images  `FROM place_images pi` (IN 없음)  같은 것의 썸네일 판
#
# bld_*를 따로 세는 이유: rebuild()와 loadByIds가 **같은 SQL 템플릿**을 공유하므로(그것이 값
# 동치의 근거다) 테이블·SELECT 목록만 보면 둘이 구별되지 않는다. WHERE 조각(`IN (`)이 유일한
# 구분점이고, 이걸 섞으면 스냅샷 모드에서 배치가 돌기만 해도 "프로젝션 문장이 실행됐다"가 된다.
# LATEST + 태그 목록 쿼리도 places와 place_tag를 함께 참조하므로 테이블 기준 판별은 오검출한다.
#
# 사용: ./verify-switch.sh <mode> <label>
# 산출: ../results/digests/<label>.digest.tsv (전체 덤프) + 표준출력에 판정
set -euo pipefail
MODE="${1:?mode 필요}"
LABEL="${2:?label 필요}"
HERE="$(cd "$(dirname "$0")" && pwd)"
CAMP="$(cd "$HERE/.." && pwd)"
LT="$(cd "$CAMP/../.." && pwd)"
mkdir -p "$CAMP/results/digests"

MYSQL=(docker exec -i solply-bench-mysql mysql -uroot -prootpwd)
TOKEN="$(sed -n 2p "$LT/data/users.csv" | tr -d '\r')"

"${MYSQL[@]}" -e "TRUNCATE performance_schema.events_statements_summary_by_digest;" 2>/dev/null

# 요청 1건. 인기순 시 단위 무필터 — 골격 10건이 확실히 잡히는 조합이다.
CODE="$(curl -sS -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8082/api/places?townId=201&isBookmarkSearch=false&sort=POPULAR&size=10")"
echo "[verify:$MODE] 요청 1건 http=$CODE"

"${MYSQL[@]}" -B -e "
  SELECT * FROM performance_schema.events_statements_summary_by_digest
  ORDER BY COUNT_STAR DESC;" 2>/dev/null > "$CAMP/results/digests/$LABEL.digest.tsv"

echo "[verify:$MODE] 실행된 문장 (COUNT_STAR · DIGEST_TEXT 앞 120자)"
"${MYSQL[@]}" -B -e "
  SELECT COUNT_STAR, LEFT(DIGEST_TEXT, 120)
  FROM performance_schema.events_statements_summary_by_digest
  WHERE SCHEMA_NAME = 'solply_bench_db' ORDER BY COUNT_STAR DESC;" 2>/dev/null | sed 's/^/    /'

read -r ENT_P ENT_I PRJ_P PRJ_I BLD_P BLD_I <<<"$(
  "${MYSQL[@]}" -N -B -e "
    SELECT
      SUM(IF(DIGEST_TEXT LIKE '%FROM \`places\` \`p1_0\`%', COUNT_STAR, 0)),
      SUM(IF(DIGEST_TEXT LIKE '%FROM \`place_images\` \`pii1_0\`%', COUNT_STAR, 0)),
      SUM(IF(DIGEST_TEXT LIKE '%\`m\` . \`tag_active\`%' AND DIGEST_TEXT LIKE '%\`p\` . \`id\` IN (%', COUNT_STAR, 0)),
      SUM(IF(DIGEST_TEXT LIKE '%FROM \`place_images\` \`pi\`%' AND DIGEST_TEXT LIKE '%IN (%', COUNT_STAR, 0)),
      SUM(IF(DIGEST_TEXT LIKE '%\`m\` . \`tag_active\`%' AND DIGEST_TEXT NOT LIKE '%\`p\` . \`id\` IN (%', COUNT_STAR, 0)),
      SUM(IF(DIGEST_TEXT LIKE '%FROM \`place_images\` \`pi\`%' AND DIGEST_TEXT NOT LIKE '%IN (%', COUNT_STAR, 0))
    FROM performance_schema.events_statements_summary_by_digest
    WHERE SCHEMA_NAME = 'solply_bench_db';" 2>/dev/null | tr '\t' ' ' | sed 's/NULL/0/g')"

echo "[verify:$MODE] 골격 계열 실행수: ent_places=$ENT_P ent_images=$ENT_I · prj_places=$PRJ_P prj_images=$PRJ_I · bld_places=$BLD_P bld_images=$BLD_I"

ok=1
case "$MODE" in
  entity)
    { [ "$ENT_P" -ge 1 ] && [ "$ENT_I" -ge 1 ] && [ "$PRJ_P" -eq 0 ] && [ "$PRJ_I" -eq 0 ]; } || ok=0 ;;
  projection)
    { [ "$PRJ_P" -ge 1 ] && [ "$PRJ_I" -ge 1 ] && [ "$ENT_P" -eq 0 ] && [ "$ENT_I" -eq 0 ]; } || ok=0 ;;
  snapshot)
    { [ "$ENT_P" -eq 0 ] && [ "$ENT_I" -eq 0 ] && [ "$PRJ_P" -eq 0 ] && [ "$PRJ_I" -eq 0 ]; } || ok=0
    # 스냅샷이 비면 전량 미스로 흘러 위 넷이 0이 아니게 되지만, 크기 자체도 로그로 못박는다.
    SZ="$(docker logs solply-bench-app 2>&1 | grep -oE '골격 스냅샷 교체 완료 - places=[0-9]+' | tail -1 | grep -oE '[0-9]+$' || echo 0)"
    echo "[verify:$MODE] 기동 로그 스냅샷 크기 = ${SZ:-0}"
    [ "${SZ:-0}" -gt 0 ] || ok=0 ;;
esac

if [ "$ok" -eq 1 ]; then echo "[verify:$MODE] 전환 검증 통과"; else echo "[verify:$MODE] 전환 검증 실패" >&2; exit 2; fi
