# 인기순(누적 북마크) 장소 조회 벤치마크 — v0(DB 직행) vs v1(스냅샷 캐시 집계)

## 0. 실험 목적
- "캐시가 정말 필요한가"를 선험이 아닌 실측으로 판단한다.
- v0: 요청마다 DB 집계 (popular-read-mode=db) / v1: 스냅샷 로드 시 1회 집계 (기본값 cache)
- 참고 비교: v1' 배치 비정규화 (bench/denorm-compare.sql, SQL 수준)

## 1. 환경·부하 모델
- 시드: 수도권 16개 leaf 동네(+root 3개), 장소 719개, 북마크 ~500만
  (#379 구 북마크 100만 + 신규 400만 = 정확히 4,000,406건)
- 분포: 동네 축 편중(강남 550k / 홍대 524k / 건대·성수 442k 상위 3) × 장소 축 Zipf(0.9,
  강남 1위 65,730 ↔ 최하위 5,016 ≈ 13배) × 유저 축 멱법칙 × 시간 축 최근 편향
- 부하: `place-list-popular.yml` — 웜업 20 → 램프 30→100 → 지속 100 req/s (총 165초),
  믹스(동네 40 / 시 단위 20 / 태그 20 / 스크롤 20)
- 앱 스펙 (Task 10.5, 부하 모델 기반 산정): 컨테이너 2 vCPU / 2GB (heap 1.25G), Tomcat threads 50, Hikari 10
  - 근거: 실서비스 비용 현실 체급(단일 인스턴스) 가정. Little's law — 120 req/s × 평균 30ms ≈ 동시 ~4
  - MySQL 버퍼풀: **2G 유지**. 시드 후 실측 결과 `bookmarks` 1,286MB(data 319 + index 967),
    전체 스키마 합계 ~1.32GB로 2,048MB 안에 상주 → 상향 불필요 (실측 hit rate 100.0000%로 확인됨)
- 실행: `docker compose -f docker/docker-compose.bench.yml up -d --build` (bench-app 포함)
  + `npx artillery run scenarios/place-list-popular.yml`

## 2. 실행 계획 (EXPLAIN)

`load-test/bench/explain-popular.sql` 결과. V21로 재생성한 `idx_bookmark_target`이 두 경로 모두에서 커버링으로 동작한다.

**v0 직행 쿼리 (상관 서브쿼리)**

| id | select_type | table | type | key | ref | rows | Extra |
|---|---|---|---|---|---|---|---|
| 1 | PRIMARY | \<derived2\> | ALL | NULL | NULL | 435 | Using filesort |
| 2 | DERIVED | p | range | idx_places_town_active | NULL | 435 | Using where; Using index |
| 3 | DEPENDENT SUBQUERY | b | ref | **idx_bookmark_target** | const, p.id | 547 | **Using index** |

→ 기대대로 장소당 인덱스 range scan 1회(커버링). 다만 `DEPENDENT SUBQUERY`가 **행마다** 반복되어
435개 장소 × 평균 547행 = 요청당 약 24만 인덱스 엔트리를 훑는다. 이것이 §3 붕괴의 직접 원인.

**스냅샷 로더 집계 쿼리 (IN + GROUP BY)**

| id | select_type | table | type | key | rows | Extra |
|---|---|---|---|---|---|---|
| 1 | SIMPLE | b | range | **idx_bookmark_target** | 373,888 | Using where; **Using index** |

→ 커버링 range scan 1회. 이 비용은 캐시 미스/리프레시 때만 발생(동네당 10분에 1회).

## 3. v0 결과 (popular-read-mode=db) — **목표 부하에서 붕괴**

`reports/popular-v0-r1.json`

| 지표 | 값 |
|---|---|
| 총 요청 | 14,298 |
| **200 응답** | **231 (1.6%)** |
| ETIMEDOUT | 14,062 |
| ECONNRESET | 5 |
| 실효 처리율 | 80 req/s 유입 대비 ~1.4 req/s 성공 |
| p50 / p95 / p99 | 5,487ms / 9,417ms / **9,801ms** |
| max | 9,994ms |

엔드포인트별 p99 — 동네 9,607ms / 시 단위 9,607ms / 태그 9,801ms / 스크롤 p1 9,607ms

**서버 측 지표** (`results/metrics/popular-v0-r1.*`)

| 지표 | 값 | 해석 |
|---|---|---|
| app CPU 피크 | 99.9% (2 vCPU 중) | 앱은 대기만 — 병목 아님 |
| **mysql CPU 피크** | **410.9%** (4 vCPU 상한 근접) | **DB가 병목** |
| threads_running 피크 | 12 | Hikari 10 + α, 커넥션 대기 포화 |
| buffer_pool_hit_rate | 100.0000% | 디스크 I/O 아님 — 순수 CPU 바운드 |
| innodb_data_reads | 0 | 〃 |
| questions | 38,148 | 성공 요청이 적어 쿼리 수 자체는 적음 |
| created_tmp_disk_tables | 0 | 정렬은 메모리 내 처리 |

## 4. v1 결과 (cache, 기본값) — **여유 있게 통과**

`reports/popular-v1-r1.json`

| 지표 | 값 |
|---|---|
| 총 요청 | 17,083 |
| **200 응답** | **17,083 (100%)** |
| 에러 | 0 |
| 처리율 | 103 req/s (목표 100 충족) |
| p50 / p95 / p99 | 4ms / 7.9ms / **19.1ms** |
| max | 279ms (스냅샷 최초 적재 구간) |

엔드포인트별 p99 — 동네 19.1ms / 시 단위 18.0ms / 태그 23.8ms / 스크롤 p1 19.1ms / p2 13.1ms

**서버 측 지표** (`results/metrics/popular-v1-r1.*`)

| 지표 | v1 | v0 대비 |
|---|---|---|
| app CPU 피크 | 88.4% | 유사(이제 앱이 실제로 일함) |
| **mysql CPU 피크** | **9.5%** | **410.9% → 9.5% (약 43배 감소)** |
| threads_running 피크 | 3 | 12 → 3 |
| buffer_pool_read_requests | 6,192,628 | 69,013,753 → 6.2M (약 11배 감소) |
| questions | 242,232 | 요청이 74배 성공했음에도 쿼리 총량은 6배 증가에 그침 |
| select_scan | 130 | 2,342 → 130 |
| buffer_pool_hit_rate | 100.0000% | 동일 |

> 캐시 효과의 직접 증거: **성공 요청 수는 74배(231 → 17,083)로 늘었는데 MySQL CPU는 43배 낮아졌다.**
> 남은 DB 접근은 북마크 여부 배치 조회(커버링 인덱스 1회)와 10분 주기 스냅샷 리프레시뿐이다.

### v0 vs v1 요약

| | v0 (DB 직행) | v1 (스냅샷 캐시) |
|---|---|---|
| 성공률 | 1.6% | **100%** |
| p99 | 9,801ms | **19.1ms** (약 513배) |
| mysql CPU 피크 | 410.9% | **9.5%** |

## 5. v1' 참고 (배치 비정규화, SQL 수준)

`load-test/bench/denorm-compare.sql` — `places.bookmark_count` 컬럼 + 주기 재계산

| 항목 | 값 |
|---|---|
| 배치 UPDATE 소요 (3회) | 1,090ms / 1,058ms / 1,083ms (평균 ≈ **1.08초**, 1,039행 갱신) |
| 읽기 쿼리 소요 (10회 평균) | **0.81ms** |
| 읽기 EXPLAIN | `type: ALL`, `Using where; Using filesort` — `idx_places_town_bookmark` **미사용** |

해석:
- 배치 비용 1.08초/회는 충분히 저렴하다 (전체 북마크 500만 GROUP BY 1회).
- 다만 `(town_id, bookmark_count DESC)` 인덱스는 **시 단위 조회(town_id IN 10개)에서 정렬에 쓰이지 못한다** —
  여러 town 파티션에 걸친 ORDER BY라 filesort로 흐른다. 장소 수가 1,039개라 지금은 무시할 수준(0.81ms)이지만,
  장소가 수만 개로 늘면 이 인덱스는 시 단위 조회를 도와주지 못한다.
- 즉 v1'은 v0의 병목(상관 서브쿼리 반복)은 확실히 제거하지만, v1이 이미 p99 19ms를 내는 상황에서
  스키마 변경 + 배치 스케줄러 + 신선도 관리 비용을 추가로 지불할 근거는 약하다.

## 6. 판단

<!-- 사용자와 함께 결정 — 수치는 §2~§5에 채워져 있음 -->
<!-- 검토 포인트:
     (1) v0은 목표 부하(100 req/s)를 견디지 못함이 실측으로 확정 → 캐시(또는 동등한 사전 집계)는 선택이 아니라 필수.
     (2) v1은 목표 부하에서 p99 19ms, 에러 0으로 충분한 여유. 현재 스펙(2vCPU/2G)으로 헤드룸 있음.
     (3) v1' 채택 여부 — 배치 1.08초는 저렴하나 v1 대비 추가 이득이 불명확. 스키마·운영 비용 대비 판단 필요.
     (4) 신선도 트레이드오프: 인기순 순위가 최대 10분(soft TTL) stale. 서비스 수용 가능한지 확인 필요. -->

<!-- 후속(별도 플랜): v2 다중 인스턴스 — 순위 불일치·중복 집계·무효화 전파 측정 -->
