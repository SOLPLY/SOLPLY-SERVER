#!/usr/bin/env bash
# statement digest의 **창 스냅샷** — 대조 창(control)과 배치 창(batch)을 따로 뜬다.
# 사용: digest-window.sh truncate <라벨>   (창 시작 — 누계를 0으로)
#       digest-window.sh dump     <라벨>   (창 끝  → <라벨>.digest.tsv)
#
# 08-07/08-09의 digest-snapshot.sh와 다른 점은 **창 단위로 여러 번 비운다**는 것뿐이다. 라운드 하나
# 안에 창이 둘(C: t0+160~175, B: 배치 발화 구간) 들어가고, 각 창은 직전 TRUNCATE 이후의 누계다.
# 판정에 쓰는 값은 AVG_TIMER_WAIT(실행당)와 rows_per_exec다 — 창 길이가 서로 달라 COUNT_STAR·
# SUM_TIMER_WAIT의 절대 비교는 성립하지 않는다.
#
# 앱 스로틀과 무관한 DB 내부 측정이라는 점이 이 지표를 쓰는 이유다(사전 등록 §5, 가이드 A.1).
set -euo pipefail
cd "$(dirname "$0")/../../.."          # -> load-test/

usage() {
  echo "usage: BENCH_CAMPAIGN=<캠페인 디렉터리명> $0 {truncate|dump} <label>" >&2
  exit 1
}
[ -n "${BENCH_CAMPAIGN:-}" ] || { echo "[digest] BENCH_CAMPAIGN 환경변수 필요 (캠페인 디렉터리명)" >&2; usage; }
LABEL="${2:-}"
[ -n "$LABEL" ] || usage

OUT_DIR="campaigns/$BENCH_CAMPAIGN/results/metrics"
mkdir -p "$OUT_DIR"
MYSQL=(docker exec -i solply-bench-mysql mysql -uroot -prootpwd)

case "${1:-}" in
  truncate)
    "${MYSQL[@]}" -e "TRUNCATE performance_schema.events_statements_summary_by_digest;" 2>/dev/null
    echo "[digest] 창 열림(리셋): $LABEL $(date '+%H:%M:%S')"
    ;;
  dump)
    # 상위 30행이면 이 서비스의 읽기 경로 문장이 전부 들어온다. 타이머는 피코초 → ms.
    # DIGEST_TEXT의 개행·탭은 TSV를 깨므로 공백으로 접는다.
    "${MYSQL[@]}" -B <<'SQL' 2>/dev/null > "$OUT_DIR/$LABEL.digest.tsv"
SELECT COUNT_STAR                                                   AS count_star,
       ROUND(AVG_TIMER_WAIT / 1000000000, 3)                        AS avg_ms,
       SUM_ROWS_EXAMINED                                            AS sum_rows_examined,
       ROUND(SUM_ROWS_EXAMINED / GREATEST(COUNT_STAR, 1), 1)        AS rows_per_exec,
       LEFT(REPLACE(REPLACE(DIGEST_TEXT, '\n', ' '), '\t', ' '), 140) AS digest_head
  FROM performance_schema.events_statements_summary_by_digest
 WHERE SCHEMA_NAME = 'solply_bench_db'
 ORDER BY SUM_TIMER_WAIT DESC
 LIMIT 30;
SQL
    echo "[digest] 창 닫힘(덤프): $OUT_DIR/$LABEL.digest.tsv $(date '+%H:%M:%S')"
    ;;
  *)
    usage
    ;;
esac
