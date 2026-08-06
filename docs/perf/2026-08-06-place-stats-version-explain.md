# 2026-08-06 · place_stats 버전 행 인기순 조회의 실행계획 — 태그가 붙으면 주도 테이블이 뒤집힌다

> 캠페인: [`load-test/campaigns/2026-08-06_place-stats-version-explain`](../../load-test/campaigns/2026-08-06_place-stats-version-explain/)
> **부하 테스트가 아니다.** 인덱스 채택을 묻는 질문이라 `EXPLAIN ANALYZE`로 답한다 (가이드 게이트 ①).
> Artillery·샘플러를 돌리지 않았으므로 처리량·백분위에 대해서는 **아무것도 주장하지 않는다.**
> 채취 2026-08-06 01:07~01:12, 10케이스 × 3회. 대상 형상: V29(버전 행) + V30(커버링 확장), 미커밋 작업트리.

## 판정 요약

채취 전에 등록한 예상 셋에 대한 판정이다.

| # | 예상 | 실측 | 판정 |
|---|---|---|---|
| ① | 단일 town은 인덱스 프리픽스로 잘라 정렬 없이 페이지만큼만 읽는다 (range) | 첫 페이지는 **ref**(등호 2컬럼), 커서 페이지는 **range**. 둘 다 `Using index`·filesort 없음·**읽은 행 11** | **충족** (예상보다 좁다 — 근거는 §4.1) |
| ② | 다중 town(시 단위 18개)은 filesort가 붙되 정렬 대상이 커버링 엔트리라 싸다 | `Using index; Using filesort`, 정렬 입력 **1,800행**, 전체 **0.68~0.73ms** | **충족** |
| ③ | 조인 플립이 없다 (주도 테이블이 place_stats에서 뒤집히지 않는다) | 태그 없는 4케이스는 조인 자체가 없어 자명하게 충족. **태그가 하나라도 붙으면 주도 테이블이 `place_tag`로 뒤집히고 정렬 인덱스를 통째로 잃는다** | **부분 충족 — 태그 경로에서 불충족** |

**핵심은 ③이다.** 태그 필터가 붙는 순간 MySQL은 EXISTS를 세미조인으로 바꾼 뒤 **태그 쪽에서
시작**하기로 결정한다. 그러면 `place_stats` 접근이 `idx_place_stats_version_town_score` range가
아니라 **`PRIMARY` eq_ref 단건 룩업**이 되고, 정렬을 만들어 주던 인덱스가 사라져
`Using temporary; Using filesort`가 붙는다. 비용은 동네 크기가 아니라 **태그의 인기도**에
비례하게 된다 — 가장 비싼 조합(시 단위 + 메인 + 서브A + 서브B)에서 `place_tag` 인덱스 엔트리를
**10,536건** 훑고 **22.1ms**가 걸린다. 같은 시 단위인데 태그가 없으면 0.73ms다 (**약 30배**).

단 이것을 V29/V30이 만든 회귀라고 말할 수는 없다 — 근거는 §6.

---

## 1. 검증 환경

| 항목 | 값 |
|---|---|
| 토폴로지 | `docker/docker-compose.bench.yml` — MySQL(2 CPU / 3G, 버퍼풀 2G) 단독. **앱은 채취 중 정지** |
| DB | `solply_bench_db` / 계정 `solplyuser` (앱과 같은 계정 — 권한·세션 기본값 차이를 없앤다) |
| 데이터 | 활성 장소 **6,320** · 북마크 **10,400,682** · 리뷰 **198,191** · `place_stats` **12,640행**(버전 2벌 × 6,320) |
| 버전 | current `1785978240` / prev `1785978136` (둘 다 6,320행) |
| 부하 | **0** — 다른 트래픽 없음. 앱을 내려 배치가 채취 도중 버전을 밀 수 없게 했다 |
| 인덱스 | `idx_place_stats_version_town_score (version, town_id, popular_score DESC, place_id, bookmark_count, review_count, avg_rating)` — V30 형태 |

`place_stats`는 12,640행이라 통째로 버퍼풀에 상주한다. 즉 **아래 시간은 전부 CPU 시간**이고
디스크 읽기는 섞여 있지 않다. 워밍업 실행을 한 번 돌린 뒤 채취했다.

## 2. 데이터를 어떻게 준비했는가

벤치 DB는 **V28(세대 = 컬럼 쌍) 스키마에 멈춰 있었다.** 기존 벤치 데이터(장소·북마크·리뷰)는
그대로 살아 있었으므로 재시드하지 않고 스키마와 랭킹만 현행으로 올렸다.

1. `./gradlew bootJar` → `bench-app` 기동. Flyway가 **V29·V30**을 적용한다. V29는 `place_stats`를
   `DROP` 후 재생성하므로 이 시점에 랭킹 행은 0이 된다 (마이그레이션은 백필하지 않는다 — 설계된 대가).
2. 부팅의 `ApplicationReadyEvent` → `recalculateIfEmpty`가 **최초 적재**를 돌렸다.
   `affectedRows=6320, elapsed=5578ms` (앱 로그). 이것이 prev 버전 `1785978136`이다.
3. 두 번째 버전은 배치를 한 번 더 발화시켜 만들었다 — `solply.place-stats.cron`을 1분 주기로 준
   컨테이너를 띄워 `PlaceStatsFacade`(ShedLock 포함)를 **운영과 같은 경로로** 통과시켰다.
   그 결과가 current `1785978240`이다.
4. 앱 정지. 이후 채취는 MySQL만 떠 있는 상태에서 했다.

보관 2버전이 **둘 다** 있는 상태를 만든 이유는, 인덱스 선두 컬럼 `version`의 카디널리티가 1이면
"버전 파티션을 잘라낸다"는 주장이 검증되지 않기 때문이다. 실제 카디널리티는 2다(운영 정상 상태).

## 3. 무엇을 실행했는가

문장은 `PlaceListDbQueryRepository.findPopularRows`가 조립하는 문자열을 그대로 옮겼다 —
컬럼 순서·WHERE 절 순서·EXISTS 블록·ORDER BY·LIMIT까지 같아야 운영에서 도는 문장과 같은 플랜이 나온다.

```sql
SELECT ps.place_id, ps.popular_score, ps.bookmark_count, ps.review_count, ps.avg_rating
FROM place_stats ps
WHERE ps.version = :version
  AND ps.town_id IN (:townIds)
  [AND EXISTS (SELECT 1 FROM place_tag pt JOIN tags t ON t.id = pt.tag_id
                WHERE pt.place_id = ps.place_id AND t.id = :mainTagId AND t.active = 1)]
  [AND EXISTS (... t.id IN (:subTagAIds) ...)]
  [AND EXISTS (... t.id IN (:subTagBIds) ...)]
  [AND (ps.popular_score < :cursorScore
        OR (ps.popular_score = :cursorScore AND ps.place_id > :cursorPlaceId))]
ORDER BY ps.popular_score DESC, ps.place_id ASC LIMIT 11
```

**LIMIT 11 = 기본 페이지 10 + hasNext 판정용 1건**(`PlaceService.DEFAULT_PAGE_SIZE`).
지난 캠페인 문서의 LIMIT 21은 부하 시나리오가 `size=20`을 명시하던 값이라 다르다 — 비교하지 말 것.

파라미터는 시드에서 가장 큰 시를 골랐다.

| 이름 | 값 | 규모 |
|---|---|---|
| 동네 1개 | `301` | 장소 100 |
| 시 단위 | `301`~`318` (부모 town 201의 leaf 18개) | 장소 1,800 |
| 커서(동네) | score `6584.197093`, id `10009` | 1페이지의 마지막 행 |
| 커서(시) | score `17550.157320`, id `10201` | 〃 |
| 태그 | main `1`(카페) · subA `7,8,9,10`(OPTION1) · subB `11~16`(OPTION2) | 셋 다 메인 1의 자식이라 `TagValidator`를 통과하는 조합. EXISTS 3개가 코드가 만들 수 있는 최대다 |

## 4. 케이스별 결과

시간은 `EXPLAIN ANALYZE` 최상위 노드의 actual time **3회 중앙값**이다. 플랜 모양은 3회 모두 동일했다
(원자료: `results/explain/{baseline,rep2,rep3}.txt`).

| # | 케이스 | type | 사용 인덱스 | covering | filesort | temporary | 읽은 행(actual) | 시간(중앙값) |
|---|---|---|---|---|---|---|---:|---:|
| 1 | 동네 1개 · 첫 페이지 | `ref` | `idx_..._version_town_score` (key_len 16) | ✅ | ❌ | ❌ | **11** | **0.036ms** |
| 2 | 동네 1개 · 다음 페이지 | `range` | 〃 (key_len 33) | ✅ | ❌ | ❌ | **11** | **0.032ms** |
| 3 | 시 단위 18개 · 첫 페이지 | `range` | 〃 (key_len 16) | ✅ | ✅ | ❌ | 1,800 → 11 | **0.68ms** |
| 4 | 시 단위 18개 · 다음 페이지 | `range` | 〃 (key_len 33) | ✅ | ✅ | ❌ | 1,790 → 11 | **0.73ms** |
| 5 | 동네 1개 · 메인 태그 | `ref` + `eq_ref` | 〃 + `uk_place_tag_place_tag` | ✅ | ❌ | ❌ | 57 → 11 | **0.12ms** |
| 6 | **시 단위 · 메인+서브A+서브B** | `eq_ref` | **`PRIMARY`** (정렬 인덱스 미사용) | ❌ | ✅ | ✅ (weedout) | **10,536** → 211 → 11 | **22.1ms** |
| 7 | (참고) 시 단위 · 페이징 미요청 | `range` | `idx_..._version_town_score` | ✅ | ✅ | ❌ | 1,800 → 1,800 | 0.88ms |
| 8 | (경계 확인) 시 단위 · 메인 태그만 | `eq_ref` | **`PRIMARY`** | ❌ | ✅ | ✅ | 1,146 → 275 → 11 | 1.80ms |
| 9 | (경계 확인) 시 단위 · 메인+서브A | `eq_ref` | **`PRIMARY`** | ❌ | ✅ | ✅ (weedout) | 1,756 → 275 → 11 | 4.40ms |
| 10 | (경계 확인) 동네 1개 · 메인+서브A+서브B | `eq_ref` | **`PRIMARY`** | ❌ | ✅ | ✅ (weedout) | **10,536** → 10 | 11.5ms |

"읽은 행"은 ANALYZE 트리 말단의 `rows × loops`다. covering·filesort·temporary는 traditional
EXPLAIN의 `Extra`(`Using index` / `Using filesort` / `Using temporary`)를 그대로 옮겼다.
아래에 인용하는 트리는 전부 **`baseline.txt` 원문**이라, 3회 중앙값을 실은 위 표와 소수점이 다를 수 있다.

### 4.1 단일 town — 예상보다 좁다 (case 1·2)

```
-> Limit: 11 row(s)  (cost=10.4 rows=11) (actual time=0.0337..0.0357 rows=11 loops=1)
    -> Covering index lookup on ps using idx_place_stats_version_town_score
       (version=1785978240, town_id=301)  (cost=10.4 rows=100) (actual time=0.0289..0.0305 rows=11 loops=1)
```

예상은 range였는데 실제는 **`ref`**다. `version`과 `town_id`가 둘 다 등호라 인덱스 앞 두 컬럼이
그대로 상수로 접혔고(`ref: const,const`, key_len 16 = BIGINT 8 × 2), 그 뒤는 이미
`popular_score DESC, place_id ASC` 순서로 정렬돼 있으니 **읽기를 11건에서 멈춘다.**
동네에 장소가 100개든 10,000개든 읽는 양이 같다는 뜻이다 — 예상이 겨눈 성질(정렬을 인덱스가
만든다)은 더 강한 형태로 성립했다.

커서 페이지(case 2)는 `popular_score` 비교가 들어와 `range`가 되지만, 커서 술어가 인덱스 경계로
흡수돼 앞 네 컬럼이 전부 탐색에 쓰인다(key_len 33 = version 8 + town_id 8 + popular_score 9 +
place_id 8). 여전히 커버링이고 filesort가 없으며 **11행에서 멈춘다.** 커서 술어가 조인 등식을 타고
전파돼 주도 테이블을 뒤집던 [2026-08-04의 결함](2026-08-04-saturation-amplification.md)은
**여기서 재현되지 않는다** — 뒤집힐 조인 상대(`places`)가 사라졌기 때문이다.

### 4.2 다중 town — filesort는 붙지만 싸다 (case 3·4)

```
-> Limit: 11 row(s)  (cost=364 rows=11) (actual time=0.674..0.674 rows=11 loops=1)
    -> Sort: ps.popular_score DESC, ps.place_id, limit input to 11 row(s) per chunk
       (cost=364 rows=1800) (actual time=0.673..0.674 rows=11 loops=1)
        -> Filter: (version = ... and town_id in (301,...,318))  (actual time=0.03..0.482 rows=1800 loops=1)
            -> Index range scan on ps using idx_place_stats_version_town_score
               over (version = ... AND town_id = 301) OR (... town_id = 302) OR (16 more)
               (actual time=0.0277..0.368 rows=1800 loops=1)
```

town별 range 18개가 각각은 점수순이지만 전역 점수순이 아니므로 정렬이 필요하다 —
`findPopularRows`의 javadoc이 "수용한다"고 적어 둔 그 filesort다. 정렬 입력은 **1,800건**
(= 그 시의 장소 수)이고 커버링 엔트리라 행 복원이 없다. 인용한 회차 기준으로 총 0.674ms 중
인덱스 스캔이 0.368ms, 필터를 통과하기까지가 0.482ms, 정렬 몫이 나머지 약 0.19ms다.
`limit input to 11 row(s) per chunk`는 우선순위 큐 정렬이라 1,800건을 전부 늘어놓지 않는다.

커서가 붙어도(case 4) 모양은 같다. 술어가 range 경계로 흡수돼 range가 36개로 늘 뿐이고
(town 18개 × OR 2갈래), 시간도 0.73ms로 사실상 같다.

**참고 케이스(case 7): `size`와 `cursor`를 둘 다 생략한 요청**은 `pageSize = Integer.MAX_VALUE - 1`이 되어
`LIMIT 2147483646`으로 나간다. 플랜은 같고 정렬 결과 1,800건을 전부 실어 내보내 0.88ms다.
지금 규모에서는 무해하지만 **이 경로만 페이지 크기의 상한이 없다**는 사실은 기록해 둔다.

### 4.3 태그가 붙으면 주도 테이블이 뒤집힌다 (case 5·6·8·9·10)

동네 1개 + 메인 태그 하나(case 5)는 뒤집히지 않는다.

```
-> Nested loop inner join  (cost=81.1 rows=100) (actual time=0.0557..0.123 rows=11 loops=1)
    -> Covering index lookup on ps using idx_place_stats_version_town_score (version=..., town_id=301)
       (actual time=0.0285..0.0333 rows=57 loops=1)          ← ps가 주도, 정렬 인덱스 유지
    -> Single-row covering index lookup on pt using uk_place_tag_place_tag (place_id=ps.place_id, tag_id=1)
       (actual time=0.00147..0.00147 rows=0.193 loops=57)
```

`ps`가 인덱스 순서대로 흐르고 태그는 장소마다 단건으로 확인만 하므로 정렬이 아예 없다.
11건을 채우는 데 57건만 훑었다.

그런데 **동네를 시 단위로 넓히거나(case 8) EXISTS를 하나 더 붙이면(case 10) 즉시 뒤집힌다.**
가장 비싼 조합(case 6)의 트리다.

```
-> Sort: ps.popular_score DESC, ps.place_id, limit input to 11 row(s) per chunk (actual time=20.8..20.8 rows=11)
    -> Remove duplicate (pt, ps) rows using temporary table (weedout)  (cost=254 rows=6.95) (actual time=0.3..20.7 rows=211)
        ...
            -> Inner hash join (no condition)  (cost=2.9 rows=0.024) (actual rows=24 loops=1)   ← 추정 0.024 / 실제 24
            -> Index lookup on pt using idx_place_tag_tag_id (tag_id=t.id)
               (cost=418 rows=579) (actual time=0.0501..0.153 rows=439 loops=24)                ← 439 × 24 = 10,536건
            -> Single-row covering index lookup on pt using uk_place_tag_place_tag (...) loops=10536
            -> Filter: (ps.town_id in (301,...,318))  (actual rows=0.214 loops=2874)
                -> Single-row index lookup on ps using PRIMARY (place_id=pt.place_id, version=...) loops=2874
```

읽어야 할 것은 셋이다.

1. **주도 테이블이 `tags`/`place_tag`다.** `place_stats`는 마지막에 `PRIMARY (place_id, version)`
   단건 룩업으로 붙고, `town_id` 필터는 그 뒤에 필터로 적용된다. 즉 **동네 필터가 인덱스 탐색이
   아니라 사후 걸러내기가 됐다** — 2,874번 룩업해서 214건만 남긴다.
2. **정렬 인덱스가 통째로 빠졌다.** `idx_place_stats_version_town_score`는 `possible_keys`에만
   있고 `key`는 `PRIMARY`다. 그래서 `Using temporary; Using filesort`가 붙는다.
3. **추정이 크게 빗나갔다.** 태그 두 축의 해시 조인 결과를 옵티마이저는 `rows=0.024`로 봤는데
   실제는 24다(약 1,000배). `t.active = 1`의 선택도(`filtered 10.00`)가 곱해지며 태그 쪽이
   "거의 0행"으로 보였고, 그러니 거기서 시작하는 것이 싸 보였다. **이 오추정이 뒤집기의 원인이다.**

케이스를 더해 경계를 좁히면 이렇게 갈린다.

| 조건 | 주도 테이블 | 정렬 인덱스 | 시간 |
|---|---|---|---:|
| 동네 1개 + 메인 1개 (case 5) | `place_stats` | 유지 | 0.12ms |
| 동네 1개 + 메인+A+B (case 10) | `place_tag` | **상실** | 11.5ms |
| 시 단위 + 메인 1개 (case 8) | `place_tag` | **상실** | 1.80ms |
| 시 단위 + 메인+A (case 9) | `place_tag` | **상실** | 4.40ms |
| 시 단위 + 메인+A+B (case 6) | `place_tag` | **상실** | 22.1ms |

**"태그가 붙으면 뒤집힌다"가 아니라 "태그 쪽 추정 행 수가 town 쪽보다 작아 보이면 뒤집힌다"이고,
EXISTS가 늘수록·동네가 넓을수록 그 조건이 쉽게 성립한다.** 유일하게 살아남은 조합(동네 1개 +
메인 1개)도 안전지대라기보다 아직 임계 아래일 뿐이다.

## 5. 표기에 관한 주의 두 가지

- **`EXPLAIN ANALYZE`는 covering 여부를 라벨에서 빠뜨린다.** case 3·4·7에서 `FORMAT=TREE`는
  `Covering index range scan`이라 쓰는데 같은 문장의 `EXPLAIN ANALYZE`는 `Index range scan`으로
  쓴다. 커버링이 깨진 것이 아니다 — traditional EXPLAIN의 `Extra`가 세 케이스 모두 `Using index`이고,
  애초에 이 SELECT가 만지는 컬럼 7개가 전부 인덱스 구성 컬럼이라 구조적으로 커버링이다.
  **판정은 `Extra`로 한다.**
- **case 8의 `Using temporary`는 weedout이 아니다.** traditional EXPLAIN의 첫 행에 붙지만
  TREE에는 weedout 노드가 없다 — 태그가 하나면 `uk_place_tag_place_tag`가 장소당 1행을 보장해
  중복 제거가 필요 없다. case 6·9·10에는 실제로 weedout 노드가 있다.

## 6. 이 채취로 말할 수 없는 것

- **처리량·레이턴시에 대해 아무 주장도 하지 않는다.** 부하를 걸지 않았다. 위 시간은 부하 0에서
  단일 문장을 잰 CPU 시간이고, 요청 하나는 이 문장 말고도 SQL을 여러 개 쓴다.
- **태그 경로의 플립이 V29/V30이 만든 회귀인지 알 수 없다.** 비교 가능한 유일한 과거 채취
  ([2026-08-04](2026-08-04-saturation-amplification.md)의 `tag-filter`)는 동네 1개 + 메인 1개뿐이고,
  그 조합은 **당시에도 지금도 뒤집히지 않는다**(둘 다 `ps` 주도 + 커버링). 시 단위·다중 태그는
  그때 채취한 적이 없어 전후 비교의 대상이 없다.
- **오추정의 원인을 `t.active = 1`로 특정하지 않았다.** `filtered 10.00`이 곱해진다는 관찰까지가
  실측이고, 히스토그램·`optimizer_trace`로 확인한 것은 아니다.
- **다른 시·다른 태그 조합에서 같은 임계가 나오는지 모른다.** 시드는 town당 장소 100개로 균일하고
  태그 분포도 생성기가 만든 것이라 실데이터의 편향(특정 태그 쏠림)을 재현하지 않는다.
- **`avg_rating`·`review_count`가 인덱스 말단에 있어 커버링이 유지된다는 것**은 확인했지만,
  V30이 없었을 때와의 **차이를 재지는 않았다** (V30 이전 형상으로 되돌려 채취하지 않았다).

## 7. 다음에 볼 것

우선순위대로 적되, **이 문서는 진단이고 개선은 하지 않았다.**

1. **태그 경로의 플립이 실제 트래픽에서 얼마나 비싼지** — 태그 필터 요청의 비중을 먼저 확인하고,
   유의미하면 부하로 재본다. 22ms는 부하 0의 값이라 그대로 곱하면 안 된다.
2. 고친다면 후보는 둘이다. ① `STRAIGHT_JOIN`으로 `place_stats`를 주도로 고정
   (2026-08-05에 최신순에서 같은 처방을 이미 썼다) ② EXISTS를 `place_id IN (서브쿼리)`로 바꿔
   세미조인 전략 선택 자체를 바꾸기. **어느 쪽도 이 채취만으로 정당화되지 않는다** — ①은 다른
   조합에서 더 나쁜 플랜을 고정할 위험이 있으므로 조합별 채취가 먼저다.
3. 페이징 미요청 경로(case 7)의 상한 부재 — 지금은 1,800건이라 무해하나 장소가 늘면 그대로 커진다.

## 8. 재현

```bash
# 1) 벤치 MySQL만 띄운다 (EXPLAIN에는 앱이 필요 없다)
docker compose -f docker/docker-compose.bench.yml up -d bench-mysql

# 2) 현 버전이 없으면(=배치가 한 번도 안 돌았으면) 앱을 한 번 띄워 최초 적재를 태운다
./gradlew bootJar
docker compose -f docker/docker-compose.bench.yml up -d --build bench-app
#    적재 확인 후 앱을 내려 배치가 채취 도중 버전을 밀지 못하게 한다
docker stop solply-bench-app

# 3) 채취 (읽기 전용 — SELECT/EXPLAIN만 실행한다)
bash load-test/campaigns/2026-08-06_place-stats-version-explain/tools/explain-popular.sh baseline
```

파라미터를 이 문서와 똑같이 못 박아 돌리려면 환경변수로 넘긴다.

```bash
VERSION=1785978240 \
TOWN_CURSOR_SCORE=6584.197093 TOWN_CURSOR_ID=10009 \
CITY_CURSOR_SCORE=17550.157320 CITY_CURSOR_ID=10201 \
bash load-test/campaigns/2026-08-06_place-stats-version-explain/tools/explain-popular.sh rerun
```
