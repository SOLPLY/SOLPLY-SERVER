# 2026-08-06 · 태그 필터 EXISTS에서 `tags` 조인 제거 — 전후 실행계획

> **부하 테스트가 아니다.** 조인 순서 선택이 바뀌었는지를 묻는 질문이라 `EXPLAIN ANALYZE`로 답한다
> ([가이드](../../../docs/perf/load-test-guide.md) 게이트 ①). 처리량·백분위는 **아무것도 주장하지 않는다.**
>
> 진단은 앞선 두 캠페인([인기순](../2026-08-06_place-stats-version-explain/) ·
> [최신순](../2026-08-06_place-list-latest-explain/))이 했다. 여기서는 그 진단에 따른 변경의
> **전후를 같은 DB·같은 파라미터로 나란히** 채취한다.

## 1. 문제

태그 필터가 붙으면 주도 테이블이 `place_stats`/`places`에서 `place_tag`로 뒤집힌다. 가장 비싼
조합(시 단위 + 메인 + 서브A + 서브B)에서 `place_tag` 엔트리 **10,536건**을 훑고 장소를 **2,874번**
PK 조회한 뒤, 지역 조건을 통과한 211건만 남겨 중복 제거와 정렬을 한다. 인기순 22.1ms · 최신순 23.8ms로
같은 범위의 무태그 조회(0.68ms / 1.72ms)의 **13~32배**다. 뒤집히면 인기순은 랭킹 인덱스와 조기 종료를
함께 잃는다.

## 2. 원인 가설

EXISTS 안의 `t.active = 1` 때문이다. `tags.active`에는 인덱스도 히스토그램도 없어 옵티마이저가
통과율을 **기본 추측값 10%**로 잡는다(`filtered 10.00`). EXISTS가 여럿이면 그 추측이 곱해져
태그 조합 결과를 실제 24건이 아니라 **0.024건**으로 본다(약 1,000배 과소평가). 그 숫자와
지역 후보(100 또는 1,800)를 비교하면 태그부터 시작하는 것이 압도적으로 싸 보인다.

**옵티마이저가 틀린 게 아니라 재료가 오염된 것**이라는 가설이다.

## 3. 기술적 선택

EXISTS에서 `tags` 조인과 `t.active = 1`을 **제거한다**. `place_tag`는 그대로 둔다.

```sql
-- before
EXISTS (SELECT 1 FROM place_tag pt JOIN tags t ON t.id = pt.tag_id
         WHERE pt.place_id = ? AND t.id = :tagId AND t.active = 1)
-- after
EXISTS (SELECT 1 FROM place_tag pt WHERE pt.place_id = ? AND pt.tag_id = :tagId)
```

근거는 둘이다. **요청 태그의 존재·활성·타입은 `TagValidator.validatePlaceTagConditions`가
저장소 호출 전에 이미 검증한다**(`PlaceService#getPlaces`, 커밋 `22fbd63`부터). 그리고
`pt.tag_id`가 곧 태그 id이며 `fk_place_tag_tag`가 그 행의 존재를 보장하므로 **조인은 애초에
정보를 보태지 않았다**.

조인 순서 힌트(`STRAIGHT_JOIN` / `JOIN_PREFIX`)는 **택하지 않았다** — 근거는 §4의 주장 ②다.

## 4. 검증할 주장

| # | 주장 |
|---|---|
| ① | 흔한 태그 조합에서 오추정이 사라져 주도 테이블·훑는 양이 정상화된다 |
| ② | **희귀 태그·결과 0건 태그에서는 플랜이 그대로 유지된다** — 태그 주도가 옳은 입력에서는 옵티마이저의 선택권을 뺏지 않는다 (힌트와 갈리는 지점) |
| ③ | 결과 집합이 바뀌지 않는다 |

## 5. 성공 조건

- ① 흔한 태그 3개 조합에서 **5배 이상** 개선
- ② 희귀·0건 태그에서 **플랜이 동일**하고 시간 악화가 없다 (µs 단위 흔들림은 노이즈로 본다)
- ③ `PlaceListDbQueryRepositoryIT` · `PlaceListFlowIT` 전부 통과

## 6. 실행

```bash
docker compose -f docker/docker-compose.bench.yml up -d bench-mysql   # 앱은 띄우지 않는다
bash load-test/campaigns/2026-08-06_tag-filter-join-order/tools/explain-tag-filter.sh baseline
```

정렬 2 × 조회 범위 2 × 태그 조합 4 × 형상 2 = 32건. 각각 워밍업 1회 후 3회, 중앙값으로 읽는다.
태그 조합은 흔한 태그 1개 / 흔한 태그 3개 / 희귀 서브태그(185건) / 결과 0건 태그(31)다.

**두 회차가 있다.** `baseline`은 최신순 정렬 인덱스가 없던 상태, `with-v31`은 V31을 적용한 상태다.
결과 문서의 근거는 `with-v31`이다 — `tags` 조인의 A/B로는 두 회차 모두 유효하지만(각 회차 안에서
before/after의 인덱스 조건이 같다) 최신순 절대값이 현재 스키마와 맞아야 하기 때문이다.

## 7. 결과

→ [`docs/perf/2026-08-06-tag-filter-join-order.md`](../../../docs/perf/2026-08-06-tag-filter-join-order.md)
