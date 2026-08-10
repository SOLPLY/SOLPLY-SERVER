#!/usr/bin/env bash
# performance_schema statement digest 스냅샷 — 원인 판정의 정본.
# 2026-08-06_minimal-stability-final의 같은 도구를 그대로 계승한다(캠페인 디렉터리만 다르다).
# 전역 카운터 ÷ 요청 수는 요청 믹스 변화에 오염되므로 원인 판정에 쓰지 않는다(가이드 §5).
set -euo pipefail
cd "$(dirname "$0")/.."                      # -> campaigns/<캠페인>/
LABEL="${2:?라벨 필요}"
OUT_DIR="results/digests"
mkdir -p "$OUT_DIR"
MYSQL=(docker exec -i solply-bench-mysql mysql -uroot -prootpwd)

case "${1:-}" in
  truncate)
    "${MYSQL[@]}" -e "TRUNCATE performance_schema.events_statements_summary_by_digest;" 2>/dev/null
    echo "[digest] 리셋 완료: $LABEL" ;;
  dump)
    "${MYSQL[@]}" -B -e "
      SELECT * FROM performance_schema.events_statements_summary_by_digest
      ORDER BY SUM_TIMER_WAIT DESC;" 2>/dev/null > "$OUT_DIR/$LABEL.digest.tsv"
    echo "[digest] 덤프: $OUT_DIR/$LABEL.digest.tsv" ;;
  *)
    echo "usage: $0 {truncate|dump} <label>" >&2; exit 1 ;;
esac
