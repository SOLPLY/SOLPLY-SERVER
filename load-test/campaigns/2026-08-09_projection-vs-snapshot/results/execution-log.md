# 실행 로그 — 프로젝션 vs 스냅샷 3-way

> 시간순 기록. 사전 등록([../README.md](../README.md))의 절차를 그대로 따랐고, 벗어난 것은
> 그 자리에 사유와 함께 남긴다. 라운드 유효/무효 판정은 §6의 표대로 한다.

## 형상

| 항목 | 값 |
|---|---|
| 브랜치 | `perf/#383-place-skeleton-cache` |
| 커밋 | `f91081c7ae668cf726b6bae8f82f72dfee2f571c` (`#383 docs: 프로젝션 vs 스냅샷 분해 캠페인을 사전 등록한다`) |
| 3모드 구현 커밋 | `bedbcea` (`#383 perf: 골격 필드의 출처를 3모드 설정으로 나눈다`) |
| 워킹트리 | 제품 코드·compose 무변경 (`git status`: `load-test/README.md` 수정 + 문서·캠페인 디렉터리 미추적만) |
| 빌드 | `./gradlew bootJar -x test` → `build/libs/solply-server-0.0.1-SNAPSHOT.jar` (2026-08-09 22:24) |
| 이미지 | `docker compose -f docker/docker-compose.bench.yml up -d --build` (22:25) |
| 페이지 크기 | 10 (`PlaceService.DEFAULT_PAGE_SIZE`, 운영 기본값) |
| 부하 진입점 | edge `localhost:8082` |

## 계측 도구 — 대체 1건 (명시)

`shared/sampler.sh`(3초 간격) 대신 직전 캠페인의 1초 수집기 4개를
(`tools/collect-{app,jvm,db}.sh`, `tools/collect-mysql-status.sh`) 그대로 계승해 썼다.
같은 자원 지표 계층을 채우면서 ① 해상도가 3초→1초라 phase 경계(측정 구간 60초)를 잘라낼 수
있고 ② `docker exec`를 매 틱 새로 띄우지 않아 계측이 DB 컨테이너 CPU를 덜 먹는다
(`load-test/README.md` §5의 미해결 함정 "계측 오버헤드"). `metrics-snapshot.sh start/report`는
사전 등록대로 라운드마다 그대로 돌렸다.

`sampler.sh`를 함께 돌리지 않은 이유는 계측을 이중으로 얹으면 그 오버헤드가 세 모드의 비교에
섞이기 때문이다. 이 대체는 절차 변경이므로 여기 남긴다.

---

## 준비 (측정 전 체크리스트)

| 항목 | 결과 | 근거 |
|---|---|---|
| bench 스택 기동 | 22:25:08 앱 기동 완료 (`Started SolplyServerApplication in 6.703 seconds`) | `docker logs solply-bench-app` |
| 벤치 DB 데이터 | places **6,320**(active 6,320) · place_stats 6,320 · bookmarks **10,400,682** · users 100,001 · place_images 6,944 · place_tag 15,461 · towns 84 | `mysql -e "SELECT COUNT(*) ..."` — 직전 캠페인 시드 잔존 확인, 재시드 불필요 |
| p6spy 로깅 OFF | 컨테이너 env `LOGGING_LEVEL_P6SPY=OFF`, 요청 1건 후 **앱 로그의 SQL 라인 0건** | `docker exec ... printenv` + `docker logs \| grep -cE "select \|SELECT \|p6spy"` = 0 |
| nginx worker_connections | **8192** (worker_rlimit_nofile 16384) — 420 arrivals 라운드의 선행 조건 충족 | `docker/nginx-bench-main.conf` |
| 토큰 | 22:26 재발급 1,000개 (`data/users.csv`), 스모크 요청 http=200 | `node shared/generate-users-csv.mjs` |
| `BENCH_CAMPAIGN` | `2026-08-09_projection-vs-snapshot` (`tools/run-round.sh`가 export) | — |
| 카운트 배치 cron | `0 30 * * * *` 확인. 22:30:00 발화 실측 (배치 1,596ms + 스냅샷 교체 48ms) → **라운드 창을 매시 :28~:32에 걸치지 않게 배치** | `docker logs`의 `인기순 카운트 배치 완료 - affectedRows=12640, elapsed=1596ms` |
| 시나리오 | `scenarios/mode-fixed-300.yml`(판정) · `mode-fixed-420.yml`(관찰). `phases` 단독 정의(`environments` 미사용), `payload.path = ../../../data/users.csv`, size=10, 요청 믹스는 직전 캠페인 size10 시나리오와 동일 | — |

---

## 정합성 게이트 (22:31~22:33)

### 모드 전환 검증 — 실행된 SQL (`bench/verify-switch.sh`)

digest를 비우고(`TRUNCATE performance_schema.events_statements_summary_by_digest`) 요청 1건을
보낸 뒤 골격 계열 문장의 실행 횟수를 셌다. 문장 판별은 테이블 이름이 아니라 **alias**로 한다 —
`rebuild()`와 `loadByIds()`가 같은 SQL 템플릿을 공유하고(그것이 값 동치의 근거다),
LATEST+태그 목록 쿼리도 `places`·`place_tag`를 함께 참조하므로 테이블 기준 판별은 오검출한다.

| 모드 | ent_places | ent_images | prj_places | prj_images | 판정 | 원자료 |
|---|---:|---:|---:|---:|---|---|
| snapshot | 0 | 0 | 0 | 0 | 통과 (+ 기동 로그 스냅샷 크기 **6,320**) | `results/digests/probe-snapshot.digest.tsv` |
| projection | 0 | 0 | **1** | **1** | 통과 | `results/digests/probe-projection.digest.tsv` |
| entity | **1** | **1** | 0 | 0 | 통과 | `results/digests/probe-entity.digest.tsv` |

snapshot 모드 요청 1건의 SELECT는 towns(leaf 확장)·place_stats(목록)·bookmarks(북마크 상태)
**3개뿐**이고 `places`·`place_images`는 등장하지 않는다. projection은 거기에 로더 2문장
(`SELECT p.id, p.name, p.town_id, m.tag_name, m.tag_active FROM places p LEFT JOIN (...)`,
`SELECT pi.place_id, pi.image_file_key FROM place_images pi WHERE pi.place_id IN (...)`)이 붙고,
entity는 하이버네이트 2문장(`FROM places p1_0` 페치조인, `FROM place_images pii1_0` 배치 페치)이 붙는다.

### 응답 diff (`bench/capture-responses.sh`)

같은 토큰·같은 5요청(인기순 시/동네+태그/커서 2페이지/최신순/태그 3개, 전부 size=10)을
세 모드에서 채취해 `diff`했다.

- snapshot vs projection: 5/5 **동일**
- snapshot vs entity: 5/5 **동일**
- `nextCursor` 5개 값도 세 모드에서 완전히 같다

원자료: `results/metrics/gate-{snapshot,projection,entity}.{1..5}.json`

**게이트 통과.** 본 라운드로 넘어간다.

---

## 폐기한 라운드 4개 — 전부 **계측·워밍업 설계 결함**이고, 결과를 보고 버린 것이 아니다

사전 등록에 시나리오의 워밍업 길이·부하생성기 기동 방식은 없다(§7이 정한 것은 "라운드마다
force-recreate → 워밍업 구간(폐기) → 측정 구간"이라는 골격뿐이다). 그 빈칸을 실행자가 채우다
네 번 틀렸고, 그 과정을 여기 남긴다. **네 라운드 모두 판정에 쓰지 않았고 원자료는 접두
`x1~x4`로 보존한다.**

| 라벨 | 시각 | 폐기 사유 | 증거 |
|---|---|---|---|
| `x1-e-r1-warmup-short` | 22:35 | 워밍업 부족. `20s 60→300 · 30s 300 · 60s 300`로는 적체가 측정 구간까지 남았다 — 측정 5버킷 중 앞 2개가 p50 **3,395·1,901ms**(뒤 3개는 5ms)로 정상상태가 아니다 | `x1-….json` 버킷별 p50 |
| `x2-e-r1-collector-gap` | 22:42 | **부하생성기 기동이 8.4분 걸려** 1초 수집기가 부하 전에 수명을 다 썼다(호출 22:42:12 / 첫 phase 22:50:35). 측정 구간의 자원 샘플이 통째로 없다 | `x2-….t_artillery_start` vs `x2-….artillery.log`의 `Phase started` |
| `x3-e-r1-host-collapse` | 22:58 | 붕괴. 앱 절대 코어 **2.003 > 2.0**(§6 무효) · 스로틀 100% · ETIMEDOUT 12,807 · 측정 p50 6,703ms. 같은 시각 호스트 load 19.6, `docker exec` 왕복이 느려진 VM 스톨 서명 | `x3-….cpustat.*`, `x3-….app1s.tsv` |
| `x4-e-r1-cold-collapse` | 23:06 | 위와 같은 붕괴가 **재현**(코어 2.002·스로틀 100%·ETIMEDOUT 14,137). 원인이 호스트 일시 경합이 아니라 **워밍업 설계**임이 드러났다 — w1의 고정 120 도착(≈184 req/s)에서 이미 붕괴했고, entity의 차가운 요청당 CPU가 **10.4ms**(더워진 값 2.4ms의 4.3배)라 그것만으로 1.9코어다 | `x4-….app1s.tsv` w1 구간 |

**처방.** ① 부하생성기를 `npx artillery` → `./node_modules/.bin/artillery` +
`ARTILLERY_DISABLE_TELEMETRY=true`로 바꾸고, 수집기를 **"Phase started"를 확인한 뒤** 붙인다
(계측 결함 제거이고 세 모드에 동일 적용). ② 워밍업을 직전 캠페인 계단 시나리오의 사다리
(30초씩 60·120·180·240 → 목표)로 되돌린다. 그 사다리는 같은 호스트에서 목표 부하까지
붕괴 없이 올라간 이력이 있다. 근거는 `scenarios/mode-fixed-300.yml` 머리주석에 적어 뒀다.

## 호스트 상태 — 인지된 한계

이 호스트에는 벤치 외 상시 부하가 있다(실측 23:05: WindowServer 22.5% · Gemini.app 20.3% ·
Orca Helper 15.3+7.2% · ChatGPT 2.6% 등, load average 2.5~5). 사용자 애플리케이션이라 종료하지
않았다. 라운드 중 load average가 13~19까지 오르는 구간이 있었고, 그것이 폐기 라운드 2건의
배경이다. **판정은 요청당 앱 CPU·요청당 statements로 한다** — 호스트 스톨은 모든 요청을 같이
지연시키지만 총 자원 소비량은 바꾸지 않으므로 이 두 지표는 오염된 라운드에서도 보존된다
(가이드 A.3). 각 라운드의 유효성은 §6 표로 따로 걸렀다.

---

## 본 라운드 (판정 · 300 arrivals/s)

라운드 = `force-recreate`(fresh JVM) → 전환 SQL 검증 → 사다리 워밍업 150초 + 측정 60초 →
60초 휴식. 순서는 E→P→S를 한 사이클로 3사이클. 배치 cron(:30)을 피해 라운드 창을 잡았다.

| # | 라벨 | 모드 | 창 (KST) | 전환 SQL | 유효성 (§6) |
|---|---|---|---|---|---|
| 1 | `e-r1` | entity | 23:14:08~23:17:42 | ent 1.000·1.000 / prj 0 | **유효** — 앱코어 1.174 · 도착률 100.0% · 실패 0 · 재시작 0 · DB코어 0.857 |
| 2 | `p-r1` | projection | 23:19:15~23:22:49 | prj 1.000·1.000 / ent 0 | **유효** — 앱코어 0.937 · 도착률 100.0% · 실패 0 · 재시작 0 · DB코어 0.806 |
| 3 | `s-r1` | snapshot | 23:32:19~23:35:53 | 넷 다 0 · 스냅샷 6,320 | **유효** — 앱코어 0.915 · 도착률 100.0% · 실패 0 · 재시작 0 · DB코어 0.693 |
| 4 | `e-r2` | entity | 23:37:24~23:40:58 | ent 1.000·1.000 / prj 0 | **유효** — 앱코어 1.260 · 도착률 100.0% · 실패 0 · 재시작 0 |
| 5 | `p-r2` | projection | 23:42:19~23:45:53 | prj 1.000·1.000 / ent 0 | **유효** — 앱코어 0.903 · 도착률 100.0% · 실패 0 · 재시작 0 |
| 6 | `s-r2` | snapshot | 23:47:15~23:50:49 | 넷 다 0 · 스냅샷 6,320 | **유효** — 앱코어 0.980 · 도착률 100.0% · 실패 0 · 재시작 0 |
| 7 | `e-r3` | entity | 23:52:20~23:55:53 | ent 1.000·1.000 / prj 0 | **유효** — 앱코어 1.083 · 도착률 100.0% · 실패 0 · 재시작 0 |
| 8 | `p-r3` | projection | 23:57:16~00:00:49 | prj 1.000·1.000 / ent 0 | **유효** — 앱코어 1.126 · 도착률 100.0% · 실패 0 · 재시작 0 (스로틀 8.3%로 이 모드 최고, 요청당 CPU도 최고 — 사후에 버리지 않았다) |
| 9 | `s-r3` | snapshot | 00:02:12~00:05:45 | 넷 다 0 · 스냅샷 6,320 | **유효** — 앱코어 0.879 · 도착률 100.0% · 실패 0 · 재시작 0 |

배치 창을 피하려고 라운드 2와 3 사이에 23:24~23:32를 비웠다. 23:30 배치는 부하 없는 구간에서
발화했고 어느 라운드와도 겹치지 않았다(§6의 "스냅샷 빌드가 라운드 창과 겹침" 무효 조건 회피).

## 관찰 라운드 (420 arrivals/s) — **모드 2개로 축소 (프로토콜 이탈, 협의된 것)**

사전 등록 §4는 "모드당 1라운드"였으나 **entity·projection 두 라운드만** 돌렸고 snapshot-420은
생략했다. 사유: 시간 절약이 목적이고 **420 데이터를 하나도 보기 전에** 내린 결정이라 결과 기반
조정이 아니다. snapshot-420은 정보량이 가장 낮다 — 요청당 앱 CPU 실측이 가장 낮은 모드라
420(≈670 req/s)에서 여유가 예측되고, 직전 캠페인에 480 arrivals 통과 이력이 있다. 관찰의 핵심
질문은 "쿼리 2회가 남는 projection이 entity처럼 붕괴하는가"이므로 E·P 쌍이면 답이 된다.

| # | 라벨 | 모드 | 창 (KST) | 결과 |
|---|---|---|---|---|
| 10 | `e-o420` | entity | 00:07:42~00:11:16 | 붕괴 **없음** — 671.9 req/s · 실패 0 · p50 7.0ms · p95 83.9ms · 앱코어 1.924 · 스로틀 **58.7%** (부분 포화) |
| 11 | `p-o420` | projection | 00:12:39~00:16:12 | 붕괴 **없음** — 672.7 req/s · 실패 0 · p50 5.0ms · p95 7.0ms · 앱코어 1.564 · 스로틀 **3.5%** (여유) |

두 라운드 모두 앱 절대 코어가 2.0 미만이고 도착률 100%·실패 0이라 §6 기준 유효하다.

**직전 캠페인의 "OFF는 420부터 붕괴(스로틀 100%·p50 1,790ms·3/3 재현)"는 이번에 재현되지
않았다.** 바뀐 축은 워밍업 진입 경로다 — 직전은 계단을 480까지 올리는 사다리의 S6이었고
이번은 420이 종점인 사다리의 마지막 두 칸이다. 이 캠페인은 그 차이를 규명하지 않았으므로,
"entity가 420에서 버틴다"를 직전 캠페인 결과의 반증으로 쓰지 않는다. 이번 관찰이 답하는 것은
**projection이 entity보다 뚜렷하게 여유롭다**는 것 하나다(스로틀 58.7% vs 3.5%, p95 83.9 vs 7.0ms).

---

## 산출물 위치

| 종류 | 경로 |
|---|---|
| Artillery 리포트 | `results/reports/{e,p,s}-r{1,2,3}.json` · `{e,p}-o420.json` · 폐기분 `x{1..4}-*.json` |
| 1초 자원 시계열 | `results/metrics/<라벨>.{app1s,jvm1s,db1s,dbstat1s}.tsv` |
| 라운드 전/후 cgroup | `results/metrics/<라벨>.cpustat.{start,end}` |
| MySQL 누적 diff | `results/metrics/<라벨>.{start,end,diff}` |
| digest 덤프 | `results/digests/<라벨>.digest.tsv` (라운드) · `<라벨>.switch.digest.tsv` (전환 검증) |
| 게이트 응답 | `results/metrics/gate-{entity,projection,snapshot}.{1..5}.json` |
| 분석 스크립트 | `tools/round-analyze.py` (`--compare`로 모드별 중앙값·간극·산포 표) |

---

## 측정값 (measure 구간 · 유효 라운드 9개)

전부 `python3 tools/round-analyze.py --compare e-r1 p-r1 s-r1 e-r2 p-r2 s-r2 e-r3 p-r3 s-r3`의 출력이다.

| 지표 | entity (r1,r2,r3 → 중앙값) | projection | snapshot | 모드 내 산포(최대) |
|---|---|---|---|---:|
| **요청당 앱 CPU ms** | 2.403, 2.588, 2.220 → **2.403** | 1.918, 1.849, 2.263 → **1.918** | 1.876, 2.008, 1.794 → **1.876** | 21.6% |
| 앱 코어 (쿼터 2.0) | 1.174, 1.260, 1.083 → **1.174** | 0.937, 0.903, 1.126 → **0.937** | 0.915, 0.980, 0.879 → **0.915** | 23.9% |
| **요청당 statements** (digest, 라운드 전체) | 10.991, 10.984, 10.994 → **10.991** | 10.997, 10.990, 10.997 → **10.997** | 8.989, 8.984, 9.001 → **8.989** | 0.2% |
| 골격 문장 실행수/요청 | ent_places **1.000** + ent_images **1.000** | prj_places **1.000** + prj_images **1.000** | **0.000** | — |
| **요청당 힙 할당 KB** | 1005, 1009, 1002 → **1005** | 769, 761, 759 → **761** | 696, 709, 695 → **696** | 2.0% |
| 커넥션 점유 ms/획득 | **3.36** | **2.36** | **1.88** | 34.7% |
| 요청당 DB CPU ms | 1.753, 1.857, 1.645 → **1.753** | 1.650, 1.646, 1.661 → **1.650** | 1.421, 1.368, 1.365 → **1.368** | 12.1% |
| 앱 스로틀 % | 5.9, 9.7, 0.6 → **5.9** | 0.4, 0.2, 8.3 → **0.4** | 0.4, 1.8, 0.4 → **0.4** | 1991% |
| p50 ms | 5.0 (3/3) | 4.0 (3/3) | 4.0 (3/3) | 0% |

MySQL 누적 diff는 9라운드 전부 동일하게 깨끗하다 — 버퍼풀 hit rate **100.0000%**,
디스크 읽기 0, `created_tmp_disk_tables` 0, 락 대기 0 (`results/metrics/*.diff`).

### 산포의 출처 — 스로틀과 요청당 CPU가 같이 움직인다

요청당 앱 CPU의 라운드 간 산포(11~22%)는 §5.1이 인용한 직전 캠페인의 ±4.5%보다 훨씬 크다.
원자료를 보면 그 산포는 무작위가 아니라 **스로틀 비율과 같은 방향**이다:

```
e-r3 스로틀 0.6% → 2.220ms      p-r2 0.2% → 1.849ms      s-r3 0.4% → 1.794ms
e-r1        5.9% → 2.403ms      p-r1 0.4% → 1.918ms      s-r1 0.4% → 1.876ms
e-r2        9.7% → 2.588ms      p-r3 8.3% → 2.263ms      s-r2 1.8% → 2.008ms
```

CFS가 창을 자르면 스레드가 일 중간에 내려가고, 그만큼 같은 일에 더 많은 CPU가 계산된다.
호스트 경합이 큰 이 환경에서는 스로틀 자체가 라운드마다 흔들리므로(직전 캠페인도 부분 포화
구간의 스로틀이 라운드 간 2~3배로 흔들린다고 기록했다) 요청당 CPU도 함께 흔들린다.
**이 관찰로 라운드를 골라내지 않았다** — 판정은 사전 등록대로 세 라운드 중앙값으로 한다.
다만 "간극이 산포 안"이라는 판정이 나올 때, 그 산포가 무엇에서 왔는지는 기록해 둔다.

### 사이클별 짝 (같은 사이클 = 인접 시각 = 호스트 상태가 가장 비슷하다)

```
cycle1: entity 2.403 · projection 1.918 · snapshot 1.876   e−p +0.485 · p−s +0.042
cycle2: entity 2.588 · projection 1.849 · snapshot 2.008   e−p +0.739 · p−s −0.159
cycle3: entity 2.220 · projection 2.263 · snapshot 1.794   e−p −0.043 · p−s +0.469
```

세 사이클 중 **둘에서 e>p, 하나에서 e<p**다. 부호가 뒤집힌 cycle3은 projection 라운드가
이 모드에서 유일하게 스로틀 8.3%를 맞은 회차다. p−s는 부호가 세 번 중 두 번 양수·한 번 음수로
**방향조차 일정하지 않다**.
