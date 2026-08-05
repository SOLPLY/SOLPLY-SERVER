# 2026-08-06 · place_stats 버전 행 인기순 조회 실행계획 채취

**부하 테스트가 아니다.** 인덱스 채택 여부를 묻는 검증이므로 `EXPLAIN ANALYZE`가 맞는 도구다
(방법론 가이드의 게이트 ①). Artillery도 샘플러도 돌리지 않았고, 그래서 이 캠페인에는
`scenarios/`·`results/reports/`·`results/metrics/`가 없다.

## 무엇을 확인하려 했는가

V29(버전 행) → V30(커버링 확장) 이후 인기순 목록 조회가 `idx_place_stats_version_town_score`
하나로 서빙되는지를 **실제 벤치 데이터**(장소 6,320 · 북마크 1,040만)에서 확인한다.
채취 전에 등록한 예상은 셋이다.

| # | 예상 |
|---|---|
| ① | 단일 town은 인덱스 프리픽스로 잘라내 정렬 없이 페이지만큼만 읽는다 |
| ② | 다중 town(시 단위)은 town별 range가 여러 개라 filesort가 붙는다 — 대신 정렬 대상이 커버링 엔트리라 싸다 |
| ③ | 조인 플립이 없다 (주도 테이블이 place_stats에서 뒤집히지 않는다) |

판정과 근거는 [`docs/perf/2026-08-06-place-stats-version-explain.md`](../../../docs/perf/2026-08-06-place-stats-version-explain.md)에 있다.
요약하면 **① 충족(ref가 되어 예상보다 더 좁다) · ② 충족 · ③ 태그 없는 경로만 충족, 태그가
하나라도 붙으면 불충족**이다.

## 산출물

```
tools/explain-popular.sh          읽기 전용 채취 스크립트 (SELECT/EXPLAIN만)
results/explain/baseline.txt      1회차 (본문 인용의 정본)
results/explain/rep2.txt          2회차 — 재현성 확인용
results/explain/rep3.txt          3회차
```

3회를 채취한 이유는 `EXPLAIN ANALYZE`의 actual time이 1회 값이라 흔들릴 수 있어서다.
본문의 시간은 3회의 **중앙값**이고, 플랜 모양은 3회 모두 동일했다.

## 재현

```bash
docker compose -f docker/docker-compose.bench.yml up -d bench-mysql
bash load-test/campaigns/2026-08-06_place-stats-version-explain/tools/explain-popular.sh baseline
```

`place_stats`에 현 버전이 없으면 스크립트가 즉시 실패한다. 그때는 앱을 한 번 띄워
최초 적재(`ApplicationReadyEvent` → `recalculateIfEmpty`)를 태운다 — 이 캠페인도 그렇게 준비했다
(준비 절차의 전말은 결과 문서 §2).
