# 2026-08-06 · 최신순 정렬 인덱스 — 방향이 갈랐다

> 캠페인: [`load-test/campaigns/2026-08-06_latest-sort-index`](../../load-test/campaigns/2026-08-06_latest-sort-index/)
> **부하 테스트가 아니다.** 인덱스 채택을 묻는 질문이라 `EXPLAIN ANALYZE`로 답한다 (가이드 게이트 ①).
> 처리량·백분위에 대해서는 **아무것도 주장하지 않는다.**
> 채취 2026-08-06, 형상 3개(현행 / ASC / DESC) × 케이스 7개 각 3회.

## 판정 요약

| # | 주장 | 실측 | 판정 |
|---|---|---|---|
| ① | ASC에서 단일 town의 filesort가 사라진다 | `Backward index scan; Using index` — **`Using filesort` 없음**. 0.149 → **0.025ms** | **충족** |
| ② | DESC에서는 filesort가 남는다 | `Using index; Using filesort` — 남는다. 0.062ms | **충족** |
| ③ | 다중 town은 filesort가 남되 커버링이 된다 | `Using index condition` → `Using index`. 2.030 → **0.780ms** | **충족** |
| ④ | 태그가 붙어도 단일 town이면 ①이 유지된다 | `카페` 0.162 → 0.095ms · `카페`+옵션 2종 0.257 → 0.180ms, 둘 다 정렬 없음 | **충족** |

**②가 이 채취의 존재 이유다.** V10이 만들었다가 V11이 지운 인덱스는 `created_at DESC`였는데,
그 형태를 그대로 되살렸다면 **정렬을 절반만 고쳤을 것이다.**

---

## 1. 왜 방향이 문제인가

정렬은 `ORDER BY p.created_at DESC, p.id DESC`다. 세컨더리 인덱스 뒤에는 PK(`id`)가 **항상 오름차순**으로
붙으므로 두 형태가 만드는 순서가 다르다.

| 인덱스 | 정방향 스캔 | 역방향 스캔 |
|---|---|---|
| `(town_id, active, created_at DESC)` | created_at DESC, **id ASC** | created_at ASC, id DESC |
| `(town_id, active, created_at)` ASC | created_at ASC, id ASC | created_at DESC, **id DESC** ← 필요한 순서 |

ASC를 거꾸로 읽어야만 ORDER BY와 정확히 맞는다. MySQL은 그것을 `Backward index scan`으로 표시한다.

## 2. 결과

시간은 `EXPLAIN ANALYZE` 최상위 노드 actual time의 **3회 중앙값**(ms)이다.

| 케이스 | 현행 | **ASC (V31)** | DESC |
|---|---:|---:|---:|
| 동네 1곳 · 첫 페이지 | 0.149 | **0.025** | 0.062 |
| 동네 1곳 · 커서 | 0.124 | **0.033** | 0.084 |
| 동네 18곳 · 첫 페이지 | 2.030 | **0.780** | 0.775 |
| 동네 18곳 · 커서 | 1.810 | **0.860** | 0.818 |
| 동네 1곳 · `카페` | 0.162 | **0.095** | 0.114 |
| 동네 1곳 · `카페`+옵션 2종 | 0.257 | **0.180** | 0.189 |
| 동네 18곳 · `카페`+옵션 2종 | 2.950 | 3.130 | 2.600 |

`Extra` 값은 이렇게 갈린다.

| 케이스 | 현행 | ASC | DESC |
|---|---|---|---|
| 동네 1곳 | `Using filesort` | **`Backward index scan; Using index`** | `Using index; Using filesort` |
| 동네 18곳 | `Using index condition; Using filesort` | `Using index; Using filesort` | `Using index; Using filesort` |

## 3. 느렸던 이유는 둘이고, 크기가 다르다

현행이 치르던 비용은 **정렬**과 **행 복원** 두 가지다.

`created_at`이 인덱스에 없으므로, 세컨더리 인덱스에서 후보를 고른 뒤 **행마다 PK로 클러스터드
인덱스를 찾아가 본문을 읽어야** 정렬 키를 얻는다. 시 단위면 1,800번이다. `Extra`의
`Using index condition`이 그 상태다(`Using index`가 아니다 = 커버링이 아니다).

**DESC 형상이 이 둘을 갈라 주는 대조군이 된다** — 커버링은 얻지만 정렬은 못 없애기 때문이다.

| | 현행 | DESC (커버링만) | ASC (커버링 + 정렬 제거) |
|---|---:|---:|---:|
| 동네 1곳 · 첫 페이지 | 0.149 | 0.062 | **0.025** |
| | | ← 행 복원 제거로 **0.087ms** | ← 정렬 제거로 추가 **0.037ms** |
| 동네 18곳 · 첫 페이지 | 2.030 | 0.775 | 0.780 |
| | | ← 행 복원 제거로 **1.25ms** | ← 추가 이득 **없음** |

읽는 방법은 이렇다.

- **시 단위에서는 개선분이 전부 행 복원 몫이다.** ASC와 DESC가 사실상 같다(0.780 vs 0.775) —
  town이 18개면 인덱스가 town별로만 순서를 만들어 어느 방향이든 전역 정렬은 못 만들기 때문이다.
  1,800번의 본문 읽기가 사라진 것이 2.6배의 정체다.
- **동네 1곳에서는 행 복원이 약 2/3, 정렬 제거가 약 1/3이다.** 둘 다 얻는 ASC만 0.025ms까지 간다.

즉 질문("본테이블을 탐색하러 가서 느렸나")의 답은 **그렇고, 특히 조회 범위가 넓을수록 그 몫이
지배적**이다. 다만 동네 1곳에서는 정렬 제거 몫도 무시할 수 없고, 그것을 얻으려면 방향이 ASC여야 한다.

## 4. 효과가 없는 자리

**동네 18곳 + 태그 3개**는 2.950 → 3.130ms로 오히려 소폭 느리다(6%, 노이즈 범위). 그 조합은
주도 테이블이 `place_tag`라 `places`를 PK로만 만지고, 이 인덱스를 쓰지도 않는다
(`Extra`가 세 형상 모두 `Using temporary; Using filesort`로 동일).

**이 인덱스는 지역에서 시작하는 계획에만 효력이 있다.** 태그 주도로 뒤집힌 경로는
[`tags` 조인 제거](2026-08-06-tag-filter-join-order.md)가 담당하는 다른 문제다.

## 5. 무엇을 적용했는가

`V31__restore_places_created_at_index_for_latest_sort.sql`

```sql
CREATE INDEX idx_places_town_active_created ON places (town_id, active, created_at);
DROP INDEX idx_places_town_active ON places;
```

`(town_id, active)`는 새 인덱스의 프리픽스라 중복이고, `town_id` FK 제약도 새 인덱스가 만족한다.
V10·V11과 같은 순서로 **새 인덱스를 먼저 만들고 옛 것을 지운다.**

`Place` 엔티티의 `@Index` 선언도 함께 고쳤다. 다만 **Hibernate `validate`는 인덱스를 검사하지 않으므로**
그 선언은 문서이고, 정합의 단일 진실은 Flyway다.

결과 집합이 바뀌지 않는다는 것은 `PlaceListDbQueryRepositoryIT`·`PlaceListFlowIT`·`BookmarkRepositoryIT`
**39개 테스트**로 확인했다(Testcontainers MySQL 8.0에 V31까지 실제 적용).

## 6. 이 채취로 말할 수 없는 것

- **처리량·레이턴시에 대해 아무 주장도 하지 않는다.** 부하 0에서 단일 문장을 잰 CPU 시간이다.
- **쓰기 비용 증가를 재지 않았다.** 인덱스 컬럼이 둘에서 셋으로 늘었으니 장소 등록·수정 시
  갱신 비용이 는다. 장소는 쓰기가 드문 테이블이라 문제로 보지 않았을 뿐, **측정한 것은 아니다.**
- **깊은 페이지에서의 이득을 재지 않았다.** 커서 케이스는 2페이지 하나뿐이다. 커서 술어가 range
  경계로 흡수되므로 페이지가 깊어질수록 이득이 커져야 하지만, 확인은 안 했다.
- **`created_at`이 전부 유일한 시드다.** 같은 초에 장소가 몰릴 때 역방향 스캔의 타이브레이크가
  실제로 옳게 동작하는지는 이 데이터로 드러나지 않는다. 정합성은 IT가 담당한다.
- **인덱스 크기·버퍼풀 점유 변화를 재지 않았다.**

## 7. 재현

```bash
docker compose -f docker/docker-compose.bench.yml up -d bench-mysql
bash load-test/campaigns/2026-08-06_latest-sort-index/tools/explain-latest-index.sh baseline
```

스크립트가 세 형상을 스스로 만들고 지우므로 코드·스키마 상태와 무관하게 재현된다. 끝나면
**V31 적용 전 상태로 되돌린다** — 벤치에 인덱스를 남기면 다음 앱 기동 때 Flyway가 V31을 실행하다
"Duplicate key name"으로 부팅에 실패하기 때문이다. 스키마는 앱이 올리게 둔다.
