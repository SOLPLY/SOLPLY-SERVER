# 2026-08-06 · 최신순 정렬 인덱스 — ASC와 DESC 중 무엇인가

> **부하 테스트가 아니다.** 인덱스 채택을 묻는 질문이라 `EXPLAIN ANALYZE`로 답한다
> ([가이드](../../../docs/perf/load-test-guide.md) 게이트 ①). 처리량·백분위는 **아무것도 주장하지 않는다.**
>
> 진단은 [2026-08-06_place-list-latest-explain](../2026-08-06_place-list-latest-explain/)이 했다.
> 여기서는 그 진단이 지목한 처방의 **형태**를 가른다.

## 1. 문제

최신순은 `ORDER BY p.created_at DESC, p.id DESC`로 나가는데 `created_at`이 인덱스에 없다. 그래서
조회 범위의 활성 장소를 **전부 읽어 정렬**하고, 커서를 줘도 술어가 range 경계로 흡수되지 못해
읽는 양이 줄지 않는다. 동네 1곳이면 100건, 시 단위면 1,800건을 매 요청 정렬한다.

`(town_id, active, created_at DESC)`는 **V10이 만들었다가 V11이 지웠다.** 지운 근거는 "LIMIT 없는
전체 조회에서 filesort 비용 절감 효과가 없다"였고, 그 판단은 **커서 페이징 도입 전**의 것이다.

## 2. 원인 가설

정렬 축이 인덱스에 없으니 정렬을 인덱스가 만들 수 없다. 축을 넣으면 만들 수 있다.

다만 **방향이 쟁점이다.** 세컨더리 인덱스 뒤에는 PK(`id`)가 오름차순으로 붙는다.

| 형태 | 정방향 스캔 | 역방향 스캔 |
|---|---|---|
| `(..., created_at DESC)` | created_at DESC, **id ASC** | created_at ASC, id DESC |
| `(..., created_at)` ASC | created_at ASC, id ASC | created_at DESC, **id DESC** ← 필요한 순서 |

가설: **ASC의 역방향 스캔만 ORDER BY와 일치하고, V10이 만들었던 DESC 형태는 타이브레이크 방향이
어긋나 filesort를 못 없앤다.**

## 3. 기술적 선택

`(town_id, active, created_at)`을 **ASC로** 만들고 `idx_places_town_active`를 지운다
(새 인덱스의 프리픽스라 중복이고, `town_id` FK 제약도 새 인덱스가 만족한다). → `V31`

## 4. 검증할 주장

| # | 주장 |
|---|---|
| ① | ASC에서 **단일 town은 filesort가 사라진다** (`Backward index scan`) |
| ② | **DESC에서는 filesort가 남는다** — 방향이 어긋나기 때문 |
| ③ | 다중 town은 두 형태 모두 filesort가 남되, **커버링이 되어**(`Using index`) 행 복원이 사라진다 |
| ④ | 태그가 붙어도 단일 town이면 ①이 유지된다 |

## 5. 성공 조건

- ① ASC 단일 town의 `Extra`에 `Using filesort`가 **없다**
- ② DESC 단일 town에는 **있다** (없으면 가설이 틀린 것이고, 그러면 V10 형태를 써도 된다)
- ③ 다중 town의 `Extra`가 `Using index condition` → `Using index`로 바뀐다
- ④ 결과 집합 불변 — `PlaceListDbQueryRepositoryIT`·`PlaceListFlowIT`·`BookmarkRepositoryIT` 통과

## 6. 실행

```bash
docker compose -f docker/docker-compose.bench.yml up -d bench-mysql
bash load-test/campaigns/2026-08-06_latest-sort-index/tools/explain-latest-index.sh baseline
```

**이 스크립트는 인덱스를 만들고 지운다 — 읽기 전용이 아니다.** 세 형상(현행 / ASC / DESC)을 차례로
만들어 케이스 7개씩 재고, 끝나면 **V31 적용 전 상태로 되돌린다.** 벤치의 `flyway_schema_history`를
건드리지 않기 위해서다 — 인덱스를 남겨 두면 다음 앱 기동 때 Flyway가 V31을 실행하다
"Duplicate key name"으로 부팅에 실패한다. 스키마는 앱이 올리게 둔다.

## 7. 결과

→ [`docs/perf/2026-08-06-latest-sort-index.md`](../../../docs/perf/2026-08-06-latest-sort-index.md)
