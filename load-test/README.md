# load-test — 부하 테스트 디렉터리 지도

> **이 파일이 색인이다.** 어떤 산출물이 어느 측정에서 나왔는지, 무엇이 현행이고 무엇이
> 역할이 끝난 것인지 여기서 판별한다. 새 측정을 시작할 때도 여기부터 읽는다.
>
> **`load-test/`는 실행 가능한 것과 숫자의 원본**, **`docs/perf/`는 방법론과 해석**이다.
>
> - **왜·무엇을 재는가 (방법론)** → [`docs/perf/load-test-guide.md`](../docs/perf/load-test-guide.md)
> - **이 프로젝트 환경의 특성·지표 계약** → 같은 문서 §12
> - **개별 측정의 결과와 판단** → `docs/perf/<날짜>-<주제>.md`

---

## 1. 디렉터리 구조

```
load-test/
├── README.md                  ← 이 파일 (색인)
├── package.json               Artillery 등 의존성
├── data/                      users.csv — JWT 토큰. gitignore 됨 (절대 커밋 금지)
│
├── shared/                    캠페인 공통 도구 — 여러 측정에서 재사용
│   ├── sampler.sh             라운드 중 3초마다 서버 자원 시계열 → 캠페인 results/metrics/*.samples.csv
│   ├── metrics-snapshot.sh    라운드 전/후 MySQL 누적 카운터 diff → 캠페인 results/metrics/*.diff
│   │                          (둘 다 BENCH_CAMPAIGN 환경변수 필수 — 없으면 즉시 실패)
│   ├── generate-users-csv.mjs 부하용 JWT 발급 → data/users.csv
│   └── run-app.sh             [디버깅용] 호스트 bootRun (정식 측정은 컨테이너)
│
├── seed/                      벤치 DB 시드 생성기 모음 (캠페인 무관 공용 — 2026-08-03 통합)
│   ├── run-seed.sh · seed-bench-data.sql      기본 벤치 데이터 (유저 10만·북마크)
│   ├── generate-popular-seed.mjs · run-seed-popular.sh   Zipf 북마크 400만 추가
│   ├── generate-bench-seed.mjs · run-bench-seed.sh       전국 동네·장소 6,000 재시드
│   └── generate-review-seed.mjs · run-review-seed.sh     리뷰 시드
│
└── campaigns/                 ★ 측정 단위. 하나의 캠페인 = 하나의 증명하려던 주장
    └── <YYYY-MM-DD>_<주제>/
        ├── README.md          무엇을 증명하려 했는가 · 결과 · 근거 문서 링크
        ├── scenarios/         Artillery 시나리오
        ├── seed/              이 측정 전용 시드 생성기
        ├── bench/             이 측정 전용 스크립트·SQL (EXPLAIN, 상태 전환 등)
        └── results/           산출물 — 문서의 근거이므로 git으로 추적한다
            ├── reports/       Artillery JSON (aggregate + 10초 버킷)
            ├── metrics/       *.samples.csv · *.diff · *.start · *.end
            ├── explain/       EXPLAIN / EXPLAIN ANALYZE 출력
            └── query-logs/    p6spy 쿼리 로그 (쿼리 수를 세는 라운드에서만)
```

**핵심 규약: 종류별로 모으지 않고 측정별로 모은다.** 시나리오·시드·결과가 한 디렉터리에
있어야 "이건 어떤 측정의 산출물인가"가 경로만 보고 판별된다.

### 세 가지 계측 도구는 서로 다른 질문에 답한다

Artillery만 돌리면 **클라이언트가 체감한 결과**만 남는다. "p99가 900ms였다"는 알 수 있으나
*그 순간 서버 안에서 무슨 일이 있었는지*는 알 수 없다. 그래서 셋을 함께 쓴다.

| 도구 | 지표 종류 | 답하는 질문 | 시점 | 산출물 |
|---|---|---|---|---|
| `npx artillery` | **결과 지표** | 사용자가 무엇을 체감했나 (응답시간·성공률·처리량) | 라운드 내내 | `results/reports/*.json` |
| `shared/sampler.sh` | **자원 지표** | 어디가 한계였나 (앱·DB·LB CPU, 메모리, 커넥션) | 3초 간격 | `results/metrics/*.samples.csv` |
| `shared/metrics-snapshot.sh` | **원인 지표** | 왜 그렇게 됐나 (요청당 쿼리 수, 버퍼풀 hit rate, 디스크 읽기, filesort) | 라운드 전/후 1회 | `results/metrics/*.{start,end,diff}` |

이 구분은 [가이드 §5](../docs/perf/load-test-guide.md)의 3단 지표 구조와 일대일로 대응한다.
**세 층을 연결하지 못하면 개선의 원인을 증명할 수 없다** — "p95가 줄었다(결과)"는
"DB CPU가 줄었기 때문(자원)"이고 그것은 "요청당 쿼리를 줄였기 때문(원인)"이어야 한다.

샘플링이 실제로 결론을 가른 예 (baseline §2.3):

```
17:26:47   app1 19.29%   app2 15.75%   mysql 12.68%
17:26:52   app1 99.74%   app2 90.01%   mysql 56.51%   ← 세 컨테이너가 동시에 급등
17:26:57   app1 20.56%   app2 23.02%   mysql 15.64%
```

세 컨테이너가 같은 순간 튀었고 앱 CPU는 상한(200%)의 절반에서 멈췄다 → 서비스 병목이라면
나올 수 없는 조합이므로 **호스트 문제**로 판정했다. 샘플링이 없으면 "p99가 튀었다"에서 멈춘다.

---

## 2. 캠페인 색인

| 캠페인 | 증명하려는 주장 | 결과 | 상태 |
|---|---|---|---|
| [`2026-08-02_post-removal-regression`](campaigns/2026-08-02_post-removal-regression/) | ① 캐시 제거 후 실코드가 목표 부하(120 req/s)를 회귀 없이 처리하는가 ② 피크 중 배치 발화가 무해한가 ③ 읽기·쓰기 혼합에서 한계 규모와 먼저 포화하는 자원은 무엇인가 | ① 3/4 — p50 7.9ms(게이트 7.2) 3라운드 재현, +1.9ms는 코드 귀속 불가(meta 조회 0.203ms)·구조 지표 불변 ② 충족 4/4 (배치 1.08배·p50 0.76배·ShedLock 1인스턴스) ③ 쓰기 믹스 재현: 천장 ≈240 req/s(목표 2배), 300에서 **앱·DB CPU 동반 포화** → 읽기만 캐시로 옮겨도 한계 비례 증가 없음. 성공률 게이트 미달(99.38%)은 포화가 아니라 **북마크 중복 등록의 500 변환(무가드, 운영 동일)** 귀속 — 최초 귀속(증분 모호 컬럼)은 벤치 잔재 컬럼 탓으로 같은 날 정정(§7.4) → [결과 문서](../docs/perf/2026-08-02-post-removal-regression.md) | ⚠️ 부분 충족 + 운영 결함 1건·벤치 오염 1건 발견 (형상 `e6ac941`) |
| [`2026-08-04_read-ceiling`](campaigns/2026-08-04_read-ceiling/) | 읽기 전용 믹스에서 현 구조가 목표 피크(DAU 20만 산정 → 도착 300 req/s)를 받지 못하며 붕괴 병목은 앱이 아닌 DB CPU인가 — 통과 시 상한 탐색으로 분기 | **300 통과** (실처리 361 req/s · p50 6~7ms · 실패 0 · DB CPU 43%) → 등록 주장 **기각**, **직전 "300 붕괴"는 쓰기 10% 믹스 + 중복 북마크 500의 예외 로깅 몫으로 귀속 확정**. 상한 탐색 **500 붕괴** (시도 554 req/s에서 처리량 361→308~313 하락 + p50 1,500배 = 적체형) → **병목 = MySQL CPU**(cgroup 스로틀 68% vs 앱 10%대, I/O·정렬 폴백·락 전부 0). 생성기 아님(도착 결손 0, 2.6코어/11). 한계는 실처리 361 통과와 시도 554 붕괴 사이 — 협착 안 함. p95/p99 재현성 재실패(유효 라운드 간 3.7배), 1급 지표는 ±0.3% → [결과 문서](../docs/perf/2026-08-04-read-ceiling.md) | ✅ 목표 부하 통과 — 개선 캠페인 미개시(병목 부재). 미규명 1건(포화 시 요청당 행 읽기 2배) + 벤치 함정 2건 발견 (형상 `722f3b0`, src = `e6ac941`) |
| [`2026-08-04_saturation-amplification`](campaigns/2026-08-04_saturation-amplification/) | 위 캠페인의 미규명 관찰(포화에서 요청당 행 읽기 2배)이 인기순 쿼리의 **플랜 플립**(range→ALL) 때문인가 — 3조건(digest 실행당 증가 / 4시점 EXPLAIN 변경 / 인덱스 고정 시 소멸) 모두 충족해야 성립하도록 사전 등록 | **플랜 플립 기각** — ① 실행당 값 불변(커서 12,669→12,664 · 비커서 938→939 · `NO_INDEX_USED` 0) ② 4시점 플랜 완전 동일(ALL 미출현, 포화 중 EXPLAIN ANALYZE 1,800행·2.47ms). "2배 증폭"은 **분모가 만든 허상**(비싼 커서 문장 비중 16.6%→6.4%, 총 rows_examined는 오히려 122.1M < 146.5M). **등록에 없던 발견: 커서 쿼리(scroll p2)의 상시 나쁜 플랜** — 술어 전파(`ps.place_id > X` → `p.id > X`)로 주도 테이블이 places로 뒤집혀 실행당 12,663행(비커서의 13.5배), **부하 0에서도 동일**. FORCE INDEX로 1,822행 + 포화 처리량 155→**421 req/s**(2.7배, 실패 67.6%→27.0%) — 단 ③ **부분 성립**(붕괴 잔존) → **남은 천장은 플랜이 아니라 문장량 × 문장당 고정 CPU**(193~227us 불변 × 요청당 ~18문장) → [결과 문서](../docs/perf/2026-08-04-saturation-amplification.md) | ✅ 진단 완료 — 개선 표적 1호 확정(커서 플랜), 방식은 미결정. 교훈이 가이드 §5 "원인 지표의 분모"로 승격 (진단 도구: FORCE INDEX 토글·LB `worker_connections` 8192, 미커밋 형상) |

| [`2026-08-05_improved-recheck`](campaigns/2026-08-05_improved-recheck/) | 개선 형상(STRAIGHT_JOIN `82cec34` + SQL 18→11 `22358cb`·`2a50575`, role 클레임 토큰)이 목표 부하를 어떤 여유로 받고 이전 붕괴점(도착 500/s)이 어떻게 되는가 — 300 판정 3조건 + 500 통과 정의 + 해석 분기 4개 사전 등록 | **300 통과** (SQL/요청 **11.17** · `users` digest 소멸 · 커서 실행당 **1,822행** · MySQL 사용률 46.9→**28.6%**·스로틀 ~1% · 요청당 DB CPU **−39% = SQL 감소율과 산술 일치**) · **500 통과 — 분기 (a)** (시도 ≈600 req/s를 실패 0·도착률 100%로 처리, 기준선 155 대비 **3.9배**. p50 하위 조건만 2라운드 중 1 — 미충족 라운드는 호스트 경합 서명) · **650 관찰 = 붕괴, 병목 앱 이동** (앱 스로틀 52~59% vs MySQL 23%, LB 서킷브레이커가 붕괴 모양 증폭). 배치 cron **:30 확정** → [결과 문서](../docs/perf/2026-08-05-improved-recheck.md) | ✅ 목표 재확인 + 새 천장(시도 600~700)·다음 병목(앱 CPU) 확정 (형상 `82cec34`) |

> **과거 캠페인 정리 (2026-08-03).** 2026-07-13 ~ 2026-08-01의 캠페인 6개는 작업 디렉터리에서
> 제거했다 — 현재 서사에 필요한 검증은 위 캠페인이 담당하고, 과거 산출물의 원자료·판정은
> git 히스토리와 `docs/perf/`의 결과 문서에 남아 있다. 되살리려면 히스토리에서 해당 경로를
> checkout하면 된다.

정리되어 남지 않은 측정:

| 측정 | 산출물 | 비고 |
|---|---|---|
| `#349`·`#350`·`#352` 북마크 API 최적화 (2026-03) | **없음 (삭제)** | 환경·데이터가 현행과 달라 재현·비교 불가. 수치는 각 PR과 커밋 이력에 남아 있다 |
| `#381` 캐시 벤치 (2026-07-13) | **없음 (폐기)** | Artillery `environments` 병합 버그로 의도(55초)와 다르게 실행됨. 폐기 사유는 `docs/perf/2026-07-30-place-popular-baseline.md` §0 |

---

## 3. 현행 측정 실행법

기동·검증 절차 전체는 [`docs/perf/load-test-guide.md`](../docs/perf/load-test-guide.md) §12에 있다.
여기서는 경로 규약만 정리한다.

```bash
cd load-test

# 1) 대상 캠페인을 한 번 export한다 — sampler와 metrics-snapshot이 같은 곳에 기록한다
export BENCH_CAMPAIGN=2026-07-30_place-popular-baseline

# 2) 토큰 (401이면 재발급)
node shared/generate-users-csv.mjs

# 3) 라운드
./shared/metrics-snapshot.sh start baseline-r1
./shared/sampler.sh baseline-r1 200 &
npx artillery run campaigns/$BENCH_CAMPAIGN/scenarios/place-list-popular.yml \
  -o campaigns/$BENCH_CAMPAIGN/results/reports/baseline-r1.json
wait
./shared/metrics-snapshot.sh report baseline-r1
```

`BENCH_CAMPAIGN`이 없거나 존재하지 않는 캠페인이면 두 도구가 **즉시 실패**하고 사용 가능한
캠페인 목록을 출력한다 — 산출물이 엉뚱한 곳에 쌓이는 것을 막기 위함이다.

---

## 4. 새 캠페인을 시작할 때

1. **먼저 [`docs/perf/load-test-guide.md`](../docs/perf/load-test-guide.md) §3의 다섯 항목을 작성한다** —
   문제 / 원인 가설 / 기술적 선택 / 검증할 주장 / 성공 조건. 이걸 건너뛰면 측정해도 결론이 안 난다.
2. 디렉터리를 만든다.
   ```bash
   C=campaigns/$(date +%Y-%m-%d)_<주제>
   mkdir -p $C/{scenarios,seed,bench,results/{reports,metrics}}
   ```
3. `$C/README.md`에 1번에서 정한 내용을 적는다. **측정 전에 적는다** — 사후에 쓰면 결과에 맞춰
   주장을 조정하게 된다.
4. 시나리오는 기존 것을 복사하지 말고 [`place-list-popular.yml`](campaigns/2026-07-30_place-popular-baseline/scenarios/place-list-popular.yml)을
   본으로 삼는다. `phases`를 단독 정의하고 **`environments`를 쓰지 않는다** (§5).
5. 시나리오의 `payload.path`는 캠페인 깊이 기준 `../../../data/users.csv`다.
6. `.gitignore`는 고칠 필요가 없다 — `campaigns/**/results/`는 이미 추적 대상이다.
7. 측정이 끝나면 `docs/perf/<날짜>-<주제>.md`에 결과를 쓰고, 이 파일 §2 색인에 한 줄 추가한다.

---

## 5. 알려진 함정

과거에 실제로 겪은 것만 적는다. 새 측정 전에 훑는다.

| 함정 | 증상 | 대응 |
|---|---|---|
| **Artillery `environments`** | phase 배열이 기본 `phases`와 **원소 단위로 병합**돼 기본 프로파일이 잔존. `#381`이 의도 55초 → 실제 175초로 실행되어 폐기 | `phases`를 단독 정의한다. `environments`를 쓰지 않는다 |
| **p6spy 쿼리 로깅** | 초당 240쿼리를 전문 로깅해 앱 CPU가 포화. PK 단건 조회가 495ms까지 늘어 레이턴시 측정이 무의미해짐 | `LOGGING_LEVEL_P6SPY=OFF` (compose에 반영됨). 쿼리 **수**를 셀 때만 별도 라운드로 켜고, 그 라운드의 레이턴시는 비교하지 않는다 |
| **환경변수와 `docker restart`** | 변경한 환경변수가 반영되지 않는다 | `up -d --force-recreate` 로 컨테이너를 재생성한다 |
| **`--remove-orphans`** | `docker/` 안 compose 5개가 프로젝트명을 공유해 무관한 컨테이너까지 삭제 (실제 사고) | 이 플래그를 쓰지 않는다 |
| **nginx upstream IP 캐시** | 앱만 재기동하면 nginx가 stale IP를 붙들고 전 요청 502 | 컨테이너에 고정 IP 부여 (compose에 반영됨) |
| **호스트 자원 경합** | 앱 2대와 MySQL이 **동시에** 수 초간 멈춤. p99가 16배 흔들림 | 측정 전 재부팅 + IDE·브라우저·AI 에이전트 종료. 오염된 라운드는 **구간을 잘라내지 말고 라운드 전체를 폐기**하고 재실행 |
| **첫 라운드 JIT** | r1만 유별나게 느리고 이후 단조 회복 | 첫 라운드는 워밍업으로 간주하고 버린다 |
| **`payload.path` 상대경로** | 토큰 CSV를 못 찾아 전 요청 401 | 캠페인 깊이 기준 `../../../data/users.csv` |
| **전역 카운터 ÷ 요청 수로 원인 판정** | 요청 믹스가 부하에 따라 변해 "요청당 평균"이 허상을 만든다. 실측: 실행당 13.5배 비싼 커서 문장의 비중이 포화에서 **16.6%→6.4%**로 변한 것을 "요청당 행 읽기 2배 증폭"으로 오독하고 플랜 플립 가설까지 세웠다(총 rows_examined는 오히려 감소했다) | 원인 판정은 **`performance_schema` digest의 실행당 지표**(`ROWS_EXAMINED ÷ COUNT_STAR`) + **동일 파라미터·동일 세션 EXPLAIN의 시점 비교**로 한다. 분모에 클라이언트 관점 수치(성공 응답 수)를 쓰지 않는다 — 클라이언트가 포기해도 서버는 일한다 (가이드 §5 "원인 지표의 분모", 2026-08-04 saturation-amplification) |
| **docker stats CPU 과대 보고** | 고동시성 라운드에서 배정 상한을 넘는 값이 찍힌다 (실측: 상한 200%인데 MySQL **320.8%**·앱 263.7%). 유효성 규칙("앱 CPU 상한 초과 → 폐기")을 문면대로 적용하면 정상 라운드가 무효가 된다 | cgroup이 정본이다 — `cpu.max`(쿼터 강제)와 `cpu.stat`(`usage_usec`·`nr_periods`·`nr_throttled`)로 확인한다. **유효성 판정에 docker stats 문면을 쓰지 않는다** (2026-08-04 read-ceiling §9.1) |
| **nginx `worker_connections` 기본 1024** | 실처리 ~550 req/s에서 커넥션이 고갈돼 **LB가 500을 낸다**. 앱 예외는 0건이라 앱 로그만 보면 원인 불명 (실측: 500 전량 160건이 LB 에러 로그 건수와 정확히 일치) | 고부하 측정 전에 `worker_connections`를 상향한다(361 req/s 초과 측정의 선행 조건). **앱 예외 0인데 5xx가 보이면 LB 에러 로그를 먼저 본다** (2026-08-04 read-ceiling §9.2) |
| **계측 오버헤드** (미해결) | `sampler.sh`가 3초마다 `docker exec mysql` 2회 → DB 컨테이너 CPU를 계측이 소비 | 인지된 한계. 경량화 예정 |
| **부하생성기 위치** (미해결) | Artillery가 측정 대상과 같은 호스트에서 동작 | 인지된 한계. 1급 지표로 판정하는 한 치명적이지 않다 |

---

## 6. 캠페인 간 의존성

원칙은 캠페인의 독립이지만, 시드에 하나 남아 있다.

```
2026-07-29_place-popular-sort/seed/run-seed-popular.sh
  └─(bench 유저 10만이 없으면 선행 실행)→ 2026-07-13_bookmark-index/seed/run-seed.sh
```

`2026-07-30_place-popular-baseline/seed/run-bench-seed.sh`는 **독립적이다** — 유저·장소·북마크를
전부 스스로 생성하므로 다른 캠페인의 시드가 필요하지 않다. 현행 측정은 이것을 쓴다.
