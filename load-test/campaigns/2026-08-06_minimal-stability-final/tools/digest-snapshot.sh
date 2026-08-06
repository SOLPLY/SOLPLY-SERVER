#!/usr/bin/env bash
# performance_schema statement digest 스냅샷 — 원인 판정의 정본.
# 2026-08-05 캠페인의 같은 이름 도구를 그대로 계승한다 (캠페인 디렉터리만 다르다).
#
# 왜 전역 카운터(SHOW GLOBAL STATUS)가 아니라 digest인가: 전역 카운터를 요청 수로 나눈 값은
# 요청 믹스가 부하에 따라 변할 때 허상을 만든다(가이드 §5 "원인 지표의 분모"). digest는
# 문장별 COUNT_STAR라는 자체 분모를 주므로 "실행당" 지표를 만들 수 있다.
#
# 사용: digest-snapshot.sh truncate <라벨>   (라운드 시작 직전 — 구간을 0에서 시작시킨다)
#       digest-snapshot.sh dump     <라벨>   (라운드 종료 직후 — 전체 컬럼 TSV 덤프)
set -euo pipefail
cd "$(dirname "$0")/.."                      # -> campaigns/<캠페인>/
LABEL="${2:?라벨 필요 (예: r-a-120)}"
OUT_DIR="results/digests"
mkdir -p "$OUT_DIR"

MYSQL=(docker exec -i solply-bench-mysql mysql -uroot -prootpwd)

case "${1:-}" in
  truncate)
    # perf_schema 집계를 0으로 리셋한다 (사용자 데이터와 무관).
    "${MYSQL[@]}" -e "TRUNCATE performance_schema.events_statements_summary_by_digest;" 2>/dev/null
    echo "[digest] 리셋 완료: $LABEL"
    ;;
  dump)
    # -B(batch): 탭 구분 + 값 안의 개행·탭 이스케이프. 컬럼은 SELECT *로 전부 뜬다 —
    # 나중에 다른 지표가 필요해질 때 측정을 다시 하지 않기 위함이다.
    "${MYSQL[@]}" -B -e "
      SELECT * FROM performance_schema.events_statements_summary_by_digest
      ORDER BY SUM_TIMER_WAIT DESC;" 2>/dev/null > "$OUT_DIR/$LABEL.digest.tsv"
    echo "[digest] 덤프: $OUT_DIR/$LABEL.digest.tsv ($(( $(wc -l < "$OUT_DIR/$LABEL.digest.tsv") - 1 ))개 digest)"
    ;;
  *)
    echo "usage: $0 {truncate|dump} <label>" >&2; exit 1 ;;
esac
