#!/usr/bin/env bash
# 경계 의심 칸만 7회씩 다시 잰다. 읽기 전용.
#
# 왜: 3회 중앙값으로 자연 대 강제의 대소를 말하려면 격차가 산포보다 커야 한다. 격차가
# 작은 칸은 회차만 늘려 대소가 유지되는지 본다. 형상·통계·SQL 모두 1차 스윕과 같고
# 바꾼 것은 반복 횟수뿐이다.
#
# 대상 선정(자동, results/summary.csv에서 유도):
#   (i) 중앙값 비 |forced/natural − 1| < 0.15  또는
#   (ii) 3회를 회차별로 짝지었을 때 자연·강제의 우열이 갈리는 칸
#
# ⚠️ 1차 3회 기록(summary.csv, results/explain/*_{natural,forced}.txt)은 건드리지 않는다.
#    7회 결과는 results/probe_repeat7.csv와 *_r7.txt로 따로 남긴다.
#
# 사용: tools/probe-repeat7.sh          (합성 태그가 깔려 있어야 한다)
set -euo pipefail
cd "$(dirname "$0")/.."

CONTAINER="${BENCH_MYSQL_CONTAINER:-solply-bench-mysql}"
DB="solply_bench_db"
OUTDIR="results/explain"
SRC="results/summary.csv"
CSV="results/probe_repeat7.csv"
RUNS=7
SYNTH_MIN_ID=9001
mkdir -p "$OUTDIR"

q() { docker exec -i "$CONTAINER" mysql -usolplyuser -psolplyuserpwd \
        --default-character-set=utf8mb4 -N --raw "$DB" -e "$1" 2>/dev/null; }

REGIONS=(300 600 1000 1800 3600 6000)
RATIOS_BASE=(0.15 0.22 0.30 0.40 0.52 0.64 0.80)
RATIO_EXTRA=1.00

grid_rows() {
  local r ratio ratios
  for r in "${REGIONS[@]}"; do
    ratios=("${RATIOS_BASE[@]}")
    [ "$r" -le 1000 ] && ratios+=("$RATIO_EXTRA")
    for ratio in "${ratios[@]}"; do
      awk -v r="$r" -v x="$ratio" 'BEGIN{printf "%d %s %d\n", r, x, int(r*x+0.5)}'
    done
  done
}
uniq_sizes() { grid_rows | awk '{print $3}' | sort -n -u; }

declare -A TAGID
_tid=$SYNTH_MIN_ID
while read -r s; do TAGID["$s"]=$_tid; _tid=$((_tid + 1)); done < <(uniq_sizes)

towns_of() { seq 301 $((300 + $1 / 100)) | paste -sd, -; }

ex() { echo "  AND EXISTS (SELECT 1 FROM place_tag pt
               WHERE pt.place_id = $1 AND pt.tag_id $2)"; }

pop() { echo "SELECT $3 ps.place_id, ps.popular_score, ps.bookmark_count,
       ps.review_count, ps.avg_rating
FROM place_stats ps
WHERE ps.town_id IN ($1)
  AND ps.score_calculated_at IS NOT NULL
$2
ORDER BY ps.popular_score DESC, ps.place_id ASC LIMIT 11"; }
lat() { echo "SELECT $3 p.id, p.created_at, COALESCE(ps.bookmark_count, 0),
       COALESCE(ps.review_count, 0), ps.avg_rating
FROM places p
LEFT JOIN place_stats ps
       ON ps.place_id = p.id
WHERE p.town_id IN ($1)
  AND p.active = 1
$2
ORDER BY p.created_at DESC, p.id DESC LIMIT 11"; }

driving_of() { printf '%s\n' "$1" | grep -E '^[[:space:]]*-> ' \
                 | sed -n 's/.* on \([^ ]*\).*/\1/p' | head -1; }
has_sort()  { printf '%s\n' "$1" | grep -qE '^[[:space:]]*-> Sort' && echo y || echo n; }
uniq_or_mix() { printf '%s\n' "$@" | sort -u | paste -sd/ - ; }

# ---------- 대상 칸 유도 ----------
# summary.csv에서 (sort, R, ratio) 별로 natural/forced 두 줄을 짝지어 위 (i)(ii)를 적용한다.
TARGETS=()
while read -r line; do [ -n "$line" ] && TARGETS+=("$line"); done < <(
  awk -F, 'NR>1 {
      k=$1 SUBSEP $2 SUBSEP $3
      size[k]=$4
      med[k,$5]=$11
      for(i=8;i<=10;i++) run[k,$5,i]=$i
      seen[k]=1
    }
    END{
      for (k in seen) {
        split(k, a, SUBSEP)
        n=med[k,"natural"]+0; f=med[k,"forced"]+0
        if (n<=0) continue
        ratio=f/n; near=(ratio>0.85 && ratio<1.15)
        flip=0; base=0
        for(i=8;i<=10;i++){
          s=(run[k,"forced",i]+0 < run[k,"natural",i]+0) ? -1 : 1
          if(base==0) base=s; else if(s!=base) flip=1
        }
        if (near || flip) printf "%s %s %s %s %s\n", a[1], a[2], a[3], size[k], (near?"near":"") (flip?"flip":"")
      }
    }' "$SRC" | sort -k1,1 -k2,2n -k3,3n
)

echo "[probe7] 대상 ${#TARGETS[@]}칸"

: > "$CSV"
echo "sort,region_size,ratio,tag_size,mode,reason,driving_table,sort_node,runs_ms,median_ms" >> "$CSV"

MEDIDX=$(( (RUNS + 1) / 2 ))

for t in "${TARGETS[@]}"; do
  read -r sort_name R ratio tsize reason <<<"$t"
  tag_id="${TAGID[$tsize]}"
  towns="$(towns_of "$R")"
  cid="r${R}_ratio${ratio}"
  idcol="ps.place_id"; drv="ps"
  [ "$sort_name" = "latest" ] && { idcol="p.id"; drv="p"; }
  conds_sql="
$(ex "$idcol" "= $tag_id")"
  for mode in natural forced; do
    hint=""; [ "$mode" = "forced" ] && hint="/*+ JOIN_PREFIX($drv) */"
    if [ "$sort_name" = "popular" ]; then sql="$(pop "$towns" "$conds_sql" "$hint")"
    else sql="$(lat "$towns" "$conds_sql" "$hint")"; fi

    f="$OUTDIR/${sort_name}_${cid}_${mode}_r7.txt"
    { echo "################################################################"
      echo "# 프로브(7회) · $sort_name · R=$R · ratio=$ratio · n=$tsize (tag_id=$tag_id) · $mode"
      echo "# 사유: $reason / towns: $towns"
      echo "# 채취: $(date '+%Y-%m-%d %H:%M:%S')"
      echo "################################################################"
      echo "--- SQL ---"; echo "$sql"; } > "$f"

    q "$sql" >/dev/null || true                    # 워밍업
    times=(); drvs=(); srts=()
    for run in $(seq 1 $RUNS); do
      tree="$(q "EXPLAIN ANALYZE $sql")"
      { echo; echo "--- EXPLAIN ANALYZE (run $run) ---"; echo "$tree"; } >> "$f"
      tm="$(printf '%s\n' "$tree" | head -1 \
            | grep -o 'actual time=[0-9.e-]*\.\.[0-9.e-]*' | sed 's/.*\.\.//')"
      times+=("${tm:-NA}"); drvs+=("$(driving_of "$tree")"); srts+=("$(has_sort "$tree")")
    done
    med="$(printf '%s\n' "${times[@]}" | sort -g | sed -n "${MEDIDX}p")"
    joined="$(printf '%s;' "${times[@]}")"; joined="${joined%;}"
    echo "$sort_name,$R,$ratio,$tsize,$mode,$reason,$(uniq_or_mix "${drvs[@]}"),$(uniq_or_mix "${srts[@]}"),$joined,$med" >> "$CSV"
    echo "[probe7] $sort_name/R=$R/ratio=$ratio/$mode  drv=$(uniq_or_mix "${drvs[@]}") med=${med}ms ($joined)"
  done
done

echo "[probe7] 저장: $CSV"
