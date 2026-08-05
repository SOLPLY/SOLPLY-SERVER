# 2026-08-06 · 최신순 조회의 실행계획 — 정렬 인덱스가 없어도 조인은 정렬 뒤에 붙는다

> 캠페인: [`load-test/campaigns/2026-08-06_place-list-latest-explain`](../../load-test/campaigns/2026-08-06_place-list-latest-explain/)
> **부하 테스트가 아니다.** 플랜을 묻는 질문이라 `EXPLAIN ANALYZE`로 답한다 (가이드 게이트 ①).
> Artillery·샘플러를 돌리지 않았으므로 처리량·백분위에 대해서는 **아무것도 주장하지 않는다.**
> 채취 2026-08-06 01:56~02:00, 10케이스 × 3회. 같은 날 인기순 채취
> ([2026-08-06-place-stats-version-explain](2026-08-06-place-stats-version-explain.md))와
> **같은 DB·같은 데이터·같은 파라미터**라 케이스가 1:1로 대응한다.

## 판정 요약

채취 전에 등록한 예상 다섯 개에 대한 판정이다.

| # | 예상 | 실측 | 판정 |
|---|---|---|---|
| ① | 태그가 없어도 항상 filesort이고 커버링이 아니다. 동네 1개에서 **100행 전부** 읽는다 | 전 케이스 `Using filesort`, `Extra`는 `Using index condition`(커버링 아님). 동네 1개에서 **100행**을 읽어 11행을 낸다 | **충족** |
| ② | 커서 페이지가 첫 페이지보다 싸지지 않는다 | 시 단위 첫 페이지 1,800행 → 커서 페이지도 **1,800행**(필터 통과 1,790). 시간은 오히려 1.72 → **2.17ms**로 늘었다 | **충족** (예상보다 나쁘다 — §4.2) |
| ③ | LEFT JOIN이 정렬 이전에 수행돼 시 단위면 `place_stats` 룩업이 1,800회 붙는다 | **반증.** `Nested loop left join`의 바깥이 `Sort`라 조인은 **정렬 뒤 11행에만** 붙는다(`loops=11`) | **불충족 — 예상이 틀렸다** |
| ④ | 버전 스칼라 서브쿼리는 상수 1회 평가다 | `Select #2 (subquery in condition; run only once)` · traditional `select_type=SUBQUERY, type=const` · `Rows fetched before execution loops=1` | **충족** |
| ⑤ | 다중 태그에서 주도 테이블이 뒤집히되 인기순만큼(30배) 나빠지진 않는다 | 뒤집힘은 **인기순과 완전히 같은 임계**에서 일어난다. 배율은 13.8배로 작지만 **절대 시간은 23.8ms로 인기순(22.1ms)보다 오히려 비싸다** | **부분 충족 — 배율은 맞고 결론은 틀렸다** |

**두 개가 중요하다.** 하나는 ③의 반증이다. 최신순에는 정렬 인덱스가 없지만 조인은 정렬 뒤로
밀려나서, 걱정했던 "LIMIT 11인데 룩업 1,800회"는 **페이징을 요청한 경로에서는 일어나지 않는다.**
다른 하나는 ⑤다. 태그가 붙는 순간 두 정렬의 비용이 **거의 같아진다** — 플립 이후에는 `place_tag`가
비용을 지배해서 정렬 축의 차이가 사라지기 때문이다. 인기순이 얻은 인덱스의 이점은 **태그가 없을
때만** 드러난다.

---

## 1. 검증 환경

인기순 채취와 같은 컨테이너·같은 데이터다. 그 사이 앱은 계속 정지 상태였고 버전도 그대로다.

| 항목 | 값 |
|---|---|
| 토폴로지 | `docker/docker-compose.bench.yml` — MySQL(2 CPU / 3G, 버퍼풀 2G) 단독. **앱 정지** |
| DB | `solply_bench_db` / 계정 `solplyuser` |
| 데이터 | 활성 장소 **6,320** · `place_tag` **15,461** · `place_stats` **12,640행**(버전 2벌) |
| 부하 | **0** |
| 인덱스 | `places`에 쓸 수 있는 것은 `idx_places_town_active (town_id, active)` 하나. `created_at`은 **없다** |
| 정렬 축 분포 | 시 단위 1,800행의 `created_at`이 **전부 서로 다르다**(distinct 1,800). 2025-01-28 ~ 2026-07-29 |

정렬 축이 전부 유일하다는 것은 기록해 둔다 — 타이브레이크(`p.id`)가 실제로 발화하는 경우가
이 시드에는 **없다**. 같은 초에 여러 장소가 들어오는 실데이터에서는 다를 수 있다.

## 2. 무엇을 실행했는가

`PlaceListDbQueryRepository.findLatestRows`가 조립하는 문자열을 그대로 옮겼다.

```sql
SELECT p.id, p.created_at, COALESCE(ps.bookmark_count, 0),
       COALESCE(ps.review_count, 0), ps.avg_rating
FROM places p
LEFT JOIN place_stats ps
       ON ps.place_id = p.id
      AND ps.version = (SELECT current_generation FROM place_stats_meta WHERE id = 1)
WHERE p.town_id IN (:townIds)
  AND p.active = 1
  [AND EXISTS (... pt.place_id = p.id AND t.id = :mainTagId ...)]   -- 최대 3개
  [AND (p.created_at < :cursorCreatedAt
        OR (p.created_at = :cursorCreatedAt AND p.id < :cursorPlaceId))]
ORDER BY p.created_at DESC, p.id DESC LIMIT 11
```

town·태그·LIMIT은 인기순 채취와 같은 값이다. 커서만 정렬 축이 달라 다시 유도했다
(1페이지의 10번째 행 — 동네 `2026-05-31 08:59:37 / 10077`, 시 `2026-07-27 04:51:40 / 10546`).

## 3. 케이스별 결과

시간은 `EXPLAIN ANALYZE` 최상위 노드 actual time의 **3회 중앙값**이다. 플랜 모양은 3회 모두
동일했다 (원자료: `results/explain/{baseline,rep2,rep3}.txt`).

| # | 케이스 | 주도 테이블 | 사용 인덱스 | covering | filesort | 읽은 행(actual) | 최신순 | (참고) 인기순 | 배수 |
|---|---|---|---|---|---|---:|---:|---:|---:|
| 1 | 동네 1개 · 첫 페이지 | `places` | `idx_places_town_active` (ref) | ❌ | ✅ | **100** → 11 | **0.128ms** | 0.036ms | 3.6배 |
| 2 | 동네 1개 · 다음 페이지 | `places` | 〃 (ref) | ❌ | ✅ | **100** → 90 → 11 | **0.175ms** | 0.032ms | 5.5배 |
| 3 | 시 단위 18개 · 첫 페이지 | `places` | 〃 (range) | ❌ | ✅ | **1,800** → 11 | **1.72ms** | 0.68ms | 2.5배 |
| 4 | 시 단위 18개 · 다음 페이지 | `places` | 〃 (range) | ❌ | ✅ | **1,800** → 1,790 → 11 | **2.17ms** | 0.73ms | 3.0배 |
| 5 | 동네 1개 · 메인 태그 | `places` | 〃 + `uk_place_tag_place_tag` | ❌ | ✅ | 100 → 61 → 11 | **0.196ms** | 0.12ms | 1.6배 |
| 6 | **시 단위 · 메인+A+B** | **`place_tag`** | `idx_place_tag_tag_id` + `PRIMARY` | ❌ | ✅ + weedout | **10,536** → 2,874 → 211 → 11 | **23.8ms** | 22.1ms | 1.08배 |
| 7 | (참고) 시 단위 · 페이징 미요청 | `places` | `idx_places_town_active` | ❌ | ✅ | 1,800 → **1,800** | **3.78ms** | 0.88ms | 4.3배 |
| 8 | (경계 확인) 시 단위 · 메인만 | **`place_tag`** | `idx_place_tag_tag_id` + `PRIMARY` | ❌ | ✅ | **1,146** → 275 → 11 | **1.99ms** | 1.80ms | 1.1배 |
| 9 | (경계 확인) 시 단위 · 메인+A | **`place_tag`** | 〃 | ❌ | ✅ + weedout | **1,756** → 409 → 275 → 11 | **4.55ms** | 4.40ms | 1.03배 |
| 10 | (경계 확인) 동네 1개 · 메인+A+B | **`place_tag`** | 〃 | ❌ | ✅ + weedout | **10,536** → 162 → 32 → 10 | **12.0ms** | 11.5ms | 1.04배 |

표를 세로로 읽으면 **배수 열이 6번을 경계로 무너진다.** 태그가 없으면 최신순이 2.5~5.5배 비싸고,
태그가 붙으면 1.0~1.1배 — 사실상 같다. 인기순이 V29/V30으로 얻은 것은 **태그 없는 요청에서만
효력이 있는 이점**이라는 뜻이다.

## 4. 왜 그렇게 되는가

### 4.1 정렬은 항상 만들어야 한다 (case 1·3)

```
-> Limit: 11 row(s)  (actual time=0.108..0.119 rows=11 loops=1)
    -> Nested loop left join  (actual time=0.108..0.119 rows=11 loops=1)
        -> Sort: p.created_at DESC, p.id DESC  (actual time=0.102..0.102 rows=11 loops=1)
            -> Index lookup on p using idx_places_town_active (town_id=301, active=1)
               (actual time=0.0318..0.0809 rows=100 loops=1)          ← 100행 전부 읽는다
        -> Filter: (ps.version = (select #2))  (actual time=0.00128..0.00133 rows=1 loops=11)
```

`idx_places_town_active (town_id, active)`는 필터만 만들고 순서는 만들지 못한다. `created_at`이
인덱스에 없으니 **동네의 활성 장소를 전부 읽어 정렬해야** 상위 11건이 정해진다. 같은 조건에서
인기순은 인덱스 순서를 그대로 따라가며 11행에서 멈췄다(0.036ms). 이 3.6배가 **정렬 인덱스의 값**이다.

`Extra`는 `Using index condition`이지 `Using index`가 아니다 — 세컨더리 엔트리에 `created_at`이
없어 정렬 전에 행을 복원해야 한다. 시 단위(case 3)에서는 이 복원이 1,800번이고, 그것이
인기순과의 2.5배 차이 대부분을 만든다.

### 4.2 커서는 최신순에서 아무것도 아끼지 못한다 (case 2·4)

```
-> Sort: p.created_at DESC, p.id DESC  (actual time=2.67..2.67 rows=11 loops=1)
    -> Filter: ((p.created_at < TIMESTAMP'2026-07-27 04:51:40') or (...))
       (actual time=0.703..2.33 rows=1790 loops=1)
        -> Index range scan on p using idx_places_town_active over (town_id = 301 AND active = 1) OR (17 more)
           (actual time=0.701..2.21 rows=1800 loops=1)                 ← 커서가 있어도 1,800행
```

커서 술어가 **인덱스 경계로 흡수되지 않는다.** `created_at`이 인덱스에 없으니 range를 좁힐 수단이
아니라 읽은 뒤 걸러내는 조건일 뿐이다. 1,800행을 읽어 1,790행을 남기고, 그 1,790행을 다시 정렬한다.
시간이 첫 페이지보다 오히려 느린 것(1.72 → 2.17ms)은 **읽는 양은 같은데 필터 평가가 더해졌기**
때문이다.

인기순은 정확히 반대였다. 거기서는 커서 술어가 range 경계에 흡수돼(key_len 16 → 33) 여전히
11행에서 멈췄다. **같은 커서 페이징인데 한쪽은 공짜고 한쪽은 순수한 추가 비용이다.**

그리고 이 성질은 **페이지가 깊어져도 변하지 않는다** — 10페이지째든 100페이지째든 그 동네의
활성 장소를 전부 읽어 정렬한다. 지금 규모(시 1,800)에서 2ms대라 문제로 보이지 않을 뿐이다.

### 4.3 조인은 정렬 뒤에 붙는다 — 예상이 틀렸다 (case 1~5·7)

등록한 예상 ③은 "LEFT JOIN이 정렬 전에 수행돼 시 단위면 룩업 1,800회"였다. 실제 트리에서
`Nested loop left join`의 바깥(왼쪽)은 `Sort`이고, `place_stats` 룩업의 `loops`는 **11**이다.
정렬해서 상위 11건을 정한 **다음에** 그 11건에만 카운트를 붙인다.

그럴 수 있는 이유는 둘이다. **아우터 조인이라 행 수를 바꾸지 않고**(안쪽이 없으면 NULL을 채울
뿐 행이 사라지지 않는다), **정렬 키 `created_at`·`id`가 전부 `p`에 있어** 조인 전에 순서를 확정할
수 있다. 둘 중 하나만 어긋나도 — 예를 들어 `ps`의 컬럼으로 정렬하거나 INNER JOIN으로 바꾸면 —
이 성질은 사라진다. **카운트 컬럼을 정렬·필터 조건으로 끌어들이지 말 것.**

예외는 참고 케이스(case 7)이다. `size`와 `cursor`를 둘 다 생략한 요청은 `LIMIT 2147483646`으로 나가서
사실상 LIMIT이 없고, 그러면 조인이 **1,800행 전부**에 붙는다(`loops=1800`, 3.78ms). 인기순의 같은
경로(0.88ms)와 4.3배 차이가 나는 이유가 이것이다 — 인기순에는 애초에 조인이 없다.
**상한 없는 페이지 경로는 두 정렬 모두의 부채지만 최신순 쪽이 더 비싸다.**

### 4.4 버전 스칼라 서브쿼리는 상수 1회다 (case 전체)

```
-> Select #2 (subquery in condition; run only once)
    -> Rows fetched before execution  (cost=0..0 rows=1) (actual time=42e-6..84e-6 rows=1 loops=1)
```

traditional EXPLAIN에서도 `select_type=SUBQUERY`, `type=const`, `rows=1`이다. `findLatestRows`
javadoc의 "PK 1행 조회라 MySQL이 상수로 한 번만 평가한다"는 주장이 그대로 확인됐다 —
**메타를 따로 읽지 않고 같은 문장에 접합해 요청당 SQL 수를 유지한다**는 설계가 값을 치르지 않는다.

### 4.5 태그가 붙으면 인기순과 같은 자리에서 뒤집힌다 (case 5·6·8·9·10)

동네 1개 + 메인 태그 하나(case 5)는 뒤집히지 않는다. `p`를 정렬한 뒤 태그를 단건으로 확인만 하며
61행을 훑어 11건을 채운다. 그런데 동네를 시로 넓히거나(case 8) EXISTS를 더 붙이면(case 10)
주도 테이블이 `place_tag`로 넘어간다.

| 조건 | 최신순 주도 테이블 | 인기순 주도 테이블 | 최신순 | 인기순 |
|---|---|---|---:|---:|
| 동네 1개 + 메인 1개 (case 5) | `places` | `place_stats` | 0.196ms | 0.12ms |
| 동네 1개 + 메인+A+B (case 10) | **`place_tag`** | **`place_tag`** | 12.0ms | 11.5ms |
| 시 단위 + 메인 1개 (case 8) | **`place_tag`** | **`place_tag`** | 1.99ms | 1.80ms |
| 시 단위 + 메인+A (case 9) | **`place_tag`** | **`place_tag`** | 4.55ms | 4.40ms |
| 시 단위 + 메인+A+B (case 6) | **`place_tag`** | **`place_tag`** | 23.8ms | 22.1ms |

**임계가 완전히 같다.** 오추정의 출처도 같다 — 태그 두 축의 해시 조인 결과를 옵티마이저가
`rows=0.024`로 보는데 실제는 24다(`t.active = 1`의 `filtered 10.00`이 곱해진다). 기준 테이블이
`place_stats`든 `places`든 상관없이, **태그 쪽이 "거의 0행"으로 보이면 거기서 시작한다.**

등록할 때 나는 "최신순은 잃을 정렬 인덱스가 없으니 덜 나빠질 것"이라고 적었다. 배율만 보면
맞다 — 무태그 대비 인기순 32배, 최신순 13.8배다. **그러나 사용자가 겪는 것은 배율이 아니라
시간이고, 그 시간은 23.8ms 대 22.1ms로 최신순이 오히려 조금 더 비싸다.** 배율이 작은 이유는
개선돼서가 아니라 **분모(무태그 비용)가 이미 컸기** 때문이다. 등록 문장이 배율로 쓰여 있어
"덜 나쁘다"는 잘못된 안도를 유도했다 — 다음 등록에서는 절대값 조건을 함께 쓴다.

## 5. 표기에 관한 주의

- **`Sort` 노드의 `rows`는 정렬 입력이 아니라 출력이다.** case 3의 `Sort ... rows=11`은
  11행만 정렬했다는 뜻이 아니라 11행을 내보냈다는 뜻이고, 입력은 그 아래 스캔 노드의 1,800이다.
- **`Using index condition`은 커버링이 아니다.** `Using index`와 헷갈리기 쉽다 — 전자는 인덱스에서
  조건을 먼저 걸러 행 복원 횟수를 줄인다는 뜻이고, 복원 자체는 일어난다.
- **case 6·9·10의 `Remove duplicate ... (weedout)`은 EXISTS가 2개 이상일 때만 나온다.**
  하나면 `uk_place_tag_place_tag`가 장소당 1행을 보장해 중복 제거가 필요 없다 (인기순과 동일).

## 6. 이 채취로 말할 수 없는 것

- **처리량·레이턴시에 대해 아무 주장도 하지 않는다.** 부하 0에서 단일 문장을 잰 CPU 시간이다.
  요청 하나는 이 문장 말고도 SQL을 여러 개 쓴다.
- **최신순 요청이 실트래픽에서 얼마나 되는지 모른다.** 태그 필터 비중도 모른다. 위 배수는
  "이 조합이 오면 이만큼"이고, 그것이 전체에 미치는 몫은 별도 관측이 필요하다.
- **`created_at` 인덱스를 더하면 얼마나 좋아지는지 재지 않았다.** 위 결과는 없는 상태의 비용일
  뿐이고, 있는 상태와 비교하려면 인덱스를 만들어 같은 10케이스를 다시 채취해야 한다.
  쓰기 비용 증가분도 그때 함께 봐야 한다.
- **시드의 `created_at`이 전부 유일해 타이브레이크가 한 번도 발화하지 않았다.** 같은 초에 장소가
  몰리는 실데이터에서 커서 경계가 어떻게 되는지는 이 채취로 알 수 없다 (정합성은
  `PlaceListDbQueryRepository` javadoc의 계약이 다루고, 여기서 잰 것은 비용뿐이다).
- **3회 중 baseline 회차만 case 2·3에서 2.5~3배 높게 나왔다**(0.462 / 4.57ms). 워밍업 1회를
  돌렸는데도 남은 편차라 원인을 특정하지 못했다. 중앙값으로 대표했고 나머지 두 회차는 서로
  0.03ms 안에서 일치한다.

## 7. 다음에 볼 것

**이 문서는 진단이고 아무것도 고치지 않았다.**

1. **`(town_id, active, created_at DESC)` 복합 인덱스의 재검토.** V10이 만들었다가 V11이
   "LIMIT 없는 전체 조회에서 효과가 없다"며 지운 바로 그 형태다. 그 판단은 **커서 페이징이 붙기
   전**의 것이고, §4.2가 보여 준 것은 "커서가 아무것도 아끼지 못한다"이다. 되살리면 case 1~4가
   인기순처럼 11행에서 멈출 수 있다. 단 **태그 경로(case 6·8·9·10)에는 아무 효과가 없다** —
   거기서는 이 인덱스를 쓰지도 않는다. 쓰기 비용과 함께 저울질할 것.
2. **태그 경로의 플립은 두 정렬에 공통이다.** 고친다면 한 번에 고쳐야 하고, 후보는 인기순
   문서 §7과 같다(STRAIGHT_JOIN 고정 / EXISTS → `IN (서브쿼리)`). 어느 쪽도 이 채취만으로
   정당화되지 않는다.
3. **페이징 미요청 경로의 상한 부재**(case 7). 최신순은 조인까지 1,800회 붙어 인기순보다 4.3배
   비싸다. 두 정렬 공통의 부채이므로 함께 막는 것이 맞다.

## 8. 재현

```bash
docker compose -f docker/docker-compose.bench.yml up -d bench-mysql   # 앱은 띄우지 않는다
bash load-test/campaigns/2026-08-06_place-list-latest-explain/tools/explain-latest.sh baseline
```

커서를 이 문서와 똑같이 못 박으려면 환경변수로 넘긴다.

```bash
TOWN_CURSOR_TS='2026-05-31 08:59:37' TOWN_CURSOR_ID=10077 \
CITY_CURSOR_TS='2026-07-27 04:51:40' CITY_CURSOR_ID=10546 \
bash load-test/campaigns/2026-08-06_place-list-latest-explain/tools/explain-latest.sh rerun
```
