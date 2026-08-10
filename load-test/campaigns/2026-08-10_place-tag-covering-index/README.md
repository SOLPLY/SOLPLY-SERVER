# 2026-08-10 · `place_tag` 태그 방향 인덱스를 커버링으로 — 실행계획 채취

> **부하 테스트가 아니다.** 인덱스 형태 판정이라 `EXPLAIN ANALYZE`로 답한다 (가이드 §2).
> 처리량·백분위에 대해서는 **아무것도 주장하지 않는다.**
> 아래 다섯 항목은 **측정 전에** 작성해 확인받았다.

## 1. 문제

`place_tag`의 태그 방향 인덱스가 `(tag_id)` 단일 컬럼이고, 이 테이블의 PK는 대리키 `id`다.

```sql
CREATE TABLE place_tag (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,        -- 대리키
    place_id BIGINT NOT NULL,
    tag_id   BIGINT NOT NULL,
    CONSTRAINT uk_place_tag_place_tag UNIQUE (place_id, tag_id)
);
CREATE INDEX idx_place_tag_tag_id ON place_tag(tag_id);   -- ← 단일 컬럼
```

InnoDB 보조 인덱스의 리프에는 **인덱스 컬럼 + PK**만 실린다. PK가 `id`이므로 이 인덱스의 실체는
`(tag_id, id)`이고 **`place_id`가 없다.** "태그로 장소를 찾는다"가 이 인덱스의 유일한 용도인데,
정작 장소 id를 얻으려면 클러스터드 인덱스를 다시 봐야 한다.

반대 방향인 `uk_place_tag_place_tag (place_id, tag_id)`는 두 컬럼이 다 있어 커버링이다.
**한쪽만 커버링인 비대칭 상태**다.

## 2. 원인 가설

2026-08-06 캠페인의 `results/explain/with-v31.txt` 트리에서 읽었다.

| 케이스 | 현재 플랜 | 해석 |
|---|---|---|
| 인기순 · 서울 · 태그 3개 | `Covering index scan on pt using uk_place_tag_place_tag` **15,461행**, 0.083→1.57ms | 비커버링 1,756건보다 커버링 15,461건이 싸다고 판단했다 |
| 최신순 · 서울 · 태그 3개 | `Index lookup on pt using idx_place_tag_tag_id` **1,146행**, 0.643ms | 커버링이 아니라 행마다 숨은 클러스터드 조회가 붙는다 |

즉 옵티마이저가 이상한 선택을 한 것이 아니라, **인덱스가 그 선택을 강요했다**고 본다.

## 3. 기술적 선택

마이그레이션 한 줄. 코드 변경 0줄.

```sql
CREATE INDEX idx_place_tag_tag_place ON place_tag (tag_id, place_id);
DROP INDEX idx_place_tag_tag_id ON place_tag;
```

선두 컬럼이 `tag_id`라 `fk_place_tag_tag`의 인덱스 요구를 그대로 만족한다. 새 인덱스를 먼저
만들고 옛것을 지우는 순서여야 그 사이에도 제약이 깨지지 않는다.

## 4. 검증할 주장

| # | 주장 |
|---|---|
| ① | 인기순 · 서울 · 태그 3개에서 `uk` 전체 스캔(15,461)이 사라지고 훑는 엔트리가 준다 |
| ② | 최신순 · 서울 · 태그 3개에서 `pt` 접근이 커버링이 되어 숨은 클러스터드 조회가 사라진다 |
| ③ | **나머지 조합(동네 1곳 · 희귀 185건 · 결과 0건)에서 플랜이 나빠지지 않는다** |
| ④ | 결과 집합이 바뀌지 않는다 |

## 5. 성공 조건

- ① 인기순 서울+태그3개의 총 훑는 엔트리가 **절반 이하**(≤7,730)
- ② 최신순 서울+태그3개의 `pt` 노드가 `Covering` 표기로 바뀐다
- ③ 어떤 조합에서도 훑는 엔트리·단건 조회 횟수가 늘지 않는다
- ④ `PlaceListDbQueryRepositoryIT` · `PlaceListFlowIT` 전부 통과
- **기각 조건:** ①이 미충족이면 마이그레이션을 되돌린다

## 효과 크기 가늠 (측정 전 기록)

**2배 미만을 예상한다.** 인기순 4.34ms 중 스캔 몫이 1.57ms이고, 남는 단건 조회(1,631회)는
그대로다 → 3ms 안팎, 1.4배 수준. 최신순은 더 작아 1.2배 안팎.

그래서 **ms로 판정하지 않는다.** 판정 지표는 플랜 형상과 훑는 엔트리 수·단건 조회 횟수(`loops` 합)다.
`EXPLAIN ANALYZE`의 시간은 참고치로만 기록한다.

다만 **뒤집힐 가능성도 등록해 둔다.** 태그 쪽이 커버링이 되면 비용 계산이 바뀌어 주도 테이블이
지역으로 돌아올 수도 있다. 그 경우 자릿수급 변화가 된다.

## 검증 환경

| 항목 | 값 |
|---|---|
| 토폴로지 | `docker/docker-compose.bench.yml` — MySQL(2 CPU / 3G, 버퍼풀 2G) 단독. **앱 정지** |
| 스키마 | **V32 적용 상태** (`place_stats`가 장소당 1행, `score_calculated_at` 사용) |
| 데이터 | 활성 장소 **6,320** · `place_tag` **15,461** · `tags` 34행(비활성 0) |
| 부하 | **0** |
| 태그 규모 | 메인 1 → 1,146 · 메인 2 → 1,094 · 옵션 7~10 → 424~455 · 옵션 21 → **185** · 메인 31 → **0** |
| 채취 | 정렬 2 × 범위 2 × 태그 조합 4 × 인덱스 형상 2 = **32건**, 각 워밍업 1회 후 3회 |

**SQL은 2026-08-06 캠페인의 스크립트를 그대로 쓰지 못한다.** 그 스크립트는 `ps.version`과
`place_stats_meta`를 쓰는 V29/V30 형상이고, 현행은 V32라 `score_calculated_at IS NOT NULL`을 쓴다.
`tools/explain-tag-index.sh`에 현행 `PlaceListDbQueryRepository`의 SQL을 옮겨 담았다.

**두 형상 모두에서 `ANALYZE TABLE place_tag`를 돌린다.** 인덱스를 바꾸면 통계도 함께 바뀌므로,
한쪽만 신선한 통계를 갖는 일이 없도록 대칭으로 맞춘다.

## 실행

```bash
docker compose -f docker/docker-compose.bench.yml up -d bench-mysql
docker stop solply-bench-app                       # 배치·백그라운드 쿼리 차단
bash load-test/campaigns/2026-08-10_place-tag-covering-index/tools/explain-tag-index.sh
```

스크립트가 **두 형상을 스스로 오가며** 32건을 채취하고, 끝나거나 중단되면 인덱스를 원래 형태로
되돌린다(`trap`). 제품 코드와 마이그레이션은 건드리지 않으므로, 채택하면 그때 V33을 만든다.
