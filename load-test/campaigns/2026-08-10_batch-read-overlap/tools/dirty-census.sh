#!/usr/bin/env bash
# 버퍼 풀의 테이블·인덱스별 더티 페이지 census — 사전 등록 §5의 **1급 판정 지표**.
# 사용: dirty-census.sh <라벨>   → results/metrics/<라벨>.census.tsv
#
# ⚠️ 무겁다. INNODB_BUFFER_PAGE는 버퍼 풀 **전수 조회**다 (2GB 풀 = 약 131,000행, 실측 확인값).
#    그래서 **배치 커밋 직후에만** 부른다. 이 조회가 도는 동안의 레이턴시는 어느 창에도 넣지 않는다
#    (README §6). 라운드 중에 습관적으로 부르면 그 자체가 측정 대상을 흔든다.
#
# 더티 판정은 OLDEST_MODIFICATION > 0이다 — 아직 플러시되지 않은 변경이 그 페이지에 남아 있다는 뜻.
# TABLE_NAME은 백틱이 박힌 `db`.`table` 형식이라 스키마 필터도 백틱째로 건다(실물 확인).
# 마지막 행들은 GROUP BY ... WITH ROLLUP이 만든 테이블 소계와 전체 합계다 — WHERE가 NULL
# TABLE_NAME을 걸러내므로 (TOTAL)/(all)로 찍히는 NULL은 전부 ROLLUP이 만든 것이다.
set -euo pipefail
cd "$(dirname "$0")/../../.."          # -> load-test/

usage() {
  echo "usage: BENCH_CAMPAIGN=<캠페인 디렉터리명> $0 <label>" >&2
  exit 1
}
[ -n "${BENCH_CAMPAIGN:-}" ] || { echo "[census] BENCH_CAMPAIGN 환경변수 필요 (캠페인 디렉터리명)" >&2; usage; }
LABEL="${1:-}"
[ -n "$LABEL" ] || usage

OUT_DIR="campaigns/$BENCH_CAMPAIGN/results/metrics"
mkdir -p "$OUT_DIR"
OUT="$OUT_DIR/$LABEL.census.tsv"
MYSQL=(docker exec -i solply-bench-mysql mysql -uroot -prootpwd -N -B)

# 시각은 스캔 **직전**에 찍는다. 전수 조회가 수 초 걸리므로 이 값은 census의 시작 시각이고,
# 1초 샘플러(undo-sampler)와 정렬할 때 기준으로 쓴다. MySQL의 시계라 샘플러와 같은 시계다.
TAKEN=$("${MYSQL[@]}" -e "SELECT CONCAT(NOW(6), ' epoch_us=', ROUND(UNIX_TIMESTAMP(NOW(6)) * 1000000));" 2>/dev/null)

{
  printf '# dirty-census\tlabel=%s\ttaken_at=%s\n' "$LABEL" "$TAKEN"
  printf 'table_name\tindex_name\tpages\tdirty_pages\n'
  "${MYSQL[@]}" <<'SQL' 2>/dev/null
SELECT IFNULL(TABLE_NAME, '(TOTAL)')  AS table_name,
       IFNULL(INDEX_NAME, '(all)')    AS index_name,
       COUNT(*)                       AS pages,
       SUM(OLDEST_MODIFICATION > 0)   AS dirty_pages
  FROM information_schema.INNODB_BUFFER_PAGE
 WHERE TABLE_NAME LIKE '`solply_bench_db`.%'
 GROUP BY TABLE_NAME, INDEX_NAME WITH ROLLUP;
SQL
} > "$OUT"

echo "[census] 저장: $OUT ($TAKEN)"
awk -F'\t' '$1 ~ /places|place_stats|TOTAL/ && $4 != "" { printf "[census]   %-44s %-40s pages=%-8s dirty=%s\n", $1, $2, $3, $4 }' "$OUT"
