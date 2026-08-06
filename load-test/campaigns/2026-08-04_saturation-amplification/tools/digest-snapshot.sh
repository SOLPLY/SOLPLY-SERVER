#!/usr/bin/env bash
# performance_schema statement digest 스냅샷 — 이 캠페인의 1급 판정 지표 수집 도구.
#
# 왜 전역 카운터(SHOW GLOBAL STATUS) 대신 digest인가:
# 직전 캠페인의 "요청당 행 읽기 2배" 관찰은 전역 카운터를 '시도 요청 수'로 나눈 값이라
# ① 어느 SQL이 증폭됐는지 지목할 수 없고 ② 타임아웃 후에도 DB가 계속 일해서 분모가 흔들렸다.
# digest는 문장별 COUNT_STAR를 자체 분모로 주므로 두 결함을 동시에 없앤다 (등록서 §1, §5).
#
# 사용: digest-snapshot.sh truncate <라벨>   (라운드 시작 직전 — 구간을 0에서 시작시킨다)
#       digest-snapshot.sh dump     <라벨>   (라운드 종료 직후 — 전체 컬럼 TSV 덤프)
set -euo pipefail
cd "$(dirname "$0")/.."                      # -> campaigns/<캠페인>/
LABEL="${2:?라벨 필요 (예: amp300-r1)}"
OUT_DIR="results/digests"
mkdir -p "$OUT_DIR"

MYSQL=(docker exec -i solply-bench-mysql mysql -uroot -prootpwd)

case "${1:-}" in
  truncate)
    # TRUNCATE는 디스크 테이블이 아니라 perf_schema 집계를 0으로 리셋한다 (데이터 손실 없음).
    "${MYSQL[@]}" -e "TRUNCATE performance_schema.events_statements_summary_by_digest;" 2>/dev/null
    echo "[digest] 리셋 완료: $LABEL"
    ;;
  dump)
    # -B(batch): 탭 구분 + 값 안의 개행·탭을 \n \t로 이스케이프하므로 한 행이 한 줄로 유지된다.
    # 컬럼을 명시하지 않고 SELECT * 로 전부 뜬다 — 나중에 다른 지표가 필요해질 때
    # 측정을 다시 하지 않아도 되게 하려는 것이다.
    "${MYSQL[@]}" -B -e "
      SELECT * FROM performance_schema.events_statements_summary_by_digest
      ORDER BY SUM_TIMER_WAIT DESC;" 2>/dev/null > "$OUT_DIR/$LABEL.digest.tsv"
    echo "[digest] 덤프: $OUT_DIR/$LABEL.digest.tsv ($(( $(wc -l < "$OUT_DIR/$LABEL.digest.tsv") - 1 ))개 digest)"
    ;;
  *)
    echo "usage: $0 {truncate|dump} <label>" >&2; exit 1 ;;
esac
