# 동네별 장소 목록 Caffeine 캐시 벤치마크 (S2b vs S3)

> 이슈 [#381](https://github.com/SOLPLY/SOLPLY-SERVER/issues/381) · 측정일 2026-07-13 · [북마크 인덱스 벤치마크](./2026-07-bookmark-index-benchmark.md) 후속

## 1. 환경

[북마크 인덱스 벤치마크 §1](./2026-07-bookmark-index-benchmark.md)과 동일 (벤치 MySQL 4CPU/4GB/버퍼풀 2G, 호스트 bootRun, Artillery bench 프로파일 55초, 사용자 1,000명 토큰 랜덤). 동일 벤치 DB — bookmarks 100만 행, **V19·V20 적용 완료 상태**(양 측정 공통이라 인덱스 변수 통제됨). 측정 프로토콜: 상태별 1회 (결론을 가르는 차이가 50% 이상).

| 상태 | 코드 | 장소 목록 경로 |
|---|---|---|
| S2b (캐시 전) | develop `766fabb` (worktree) | 매 요청 places+place_tag+tags fetch join + SQL 태그 필터 + 북마크 조회 1회 |
| S3 (캐시 후) | `feat/#381-caffeine-place-cache` | Caffeine 스냅샷 + 메모리 태그 필터 + **북마크 조회 1회만 DB** |

시나리오: `load-test/scenarios/place-list.yml` — `GET /api/places?townId=2` 무태그/메인태그(id=1) 50:50, 인증 헤더 포함(북마크 decorate 경로 활성).

## 2. 결과 (시나리오당 8,450 요청, 양쪽 성공률 100%·에러 0)

### 엔드포인트별

| 지표 | S2b no-tag | S3 no-tag | 개선 | S2b main-tag | S3 main-tag | 개선 |
|---|---:|---:|---:|---:|---:|---:|
| p50 | 16.0 | 8.9 | −44% | 10.9 | 10.1 | −7% |
| p95 | 32.1 | 18.0 | −44% | 24.8 | 19.9 | −20% |
| **p99** | **80.6** | **23.8** | **−70%** | **62.2** | **25.8** | **−59%** |
| max | 289 | 66 | −77% | 281 | 74 | −74% |

### 전체 aggregate

| 지표 | S2b | S3 | 개선 |
|---|---:|---:|---:|
| p50 | 13.9 | 8.9 | −36% |
| p95 | 29.1 | 19.1 | −34% |
| **p99** | **67.4** | **24.8** | **−63%** |
| p999 | 183.1 | 43.4 | −76% |
| max | 289 | 74 | −74% |

## 3. 해석

1. **캐시의 효과는 중앙값이 아니라 꼬리를 자르는 것.** S2b도 p50 13.9ms로 이미 빨랐다(fetch join이 버퍼풀에서 처리됨). 그러나 p99/p50 비율이 4.9배에 달하는 fat tail이 있었고 — 50 req/s 경합 구간에서 fetch join 결과셋 생성·커넥션 대기·GC 압력(추정)이 겹치는 순간들 — 캐시가 요청당 DB 작업을 북마크 조회 1회로 줄이자 이 꼬리가 p99 −63%, p999 −76%로 잘렸다. S2b에서 보이던 ~10초 주기 p99 스파이크(150~290ms)도 S3에서 소멸.
2. **main-tag의 p50 개선이 작은(−7%) 이유**: 태그 필터로 결과 행이 적어 S2b에서도 이미 싼 쿼리였다. 그러나 꼬리 원인(경합)은 태그와 무관해 p99는 −59% 동일하게 개선 — "공유 데이터 hot key 캐시는 평균이 아니라 꼬리 지연 개선"이라는 설계 가설과 부합.
3. **북마크 잔여 비용** (향후 북마크 캐시 도입 판단 근거): S3의 요청당 DB 작업은 북마크 커버링 인덱스 조회 1회뿐인데 p50 8.9ms / p95 19.1ms. 서버 처리 하한(min 3ms)을 빼면 북마크 조회+커넥션 획득의 기여는 p50 기준 ~3-6ms 추정. **이 수치가 병목이 아니므로 북마크 캐시(write-through)는 여전히 도입 명분 없음** — 설계 문서 §2의 "측정 후 병목이면 도입" 조건 미충족 확인.

## 4. 결론

- 공유·저변경·hot key(동네 7개) 데이터인 동네별 장소+태그 목록에 Caffeine `AsyncLoadingCache`(soft TTL 10분 SWR + hard TTL 1시간, cold miss single-flight)를 도입하고 태그 필터링을 SQL EXISTS → 메모리 연산으로 이전한 결과, **p99 63~70% 개선, max 74~77% 억제, DB 왕복 요청당 2~4회 → 1회**.
- per-user 데이터(북마크)는 캐시 없이 커버링 인덱스 DB 직행을 유지 — 실측상 잔여 비용이 병목이 아님을 확인.

## 5. 한계

- 로컬 단일 호스트, 상태별 1회 측정 — 절대치는 참고용, 상대 비교가 유효
- 캐시 무효화 경로(AdminPlaceService `invalidateAfterCommit`)는 부하 시나리오에 미포함 (관리자 변경 빈도가 낮다는 전제)
- GC 압력 감소는 추정 (GC 로그 미수집)
- townId=2 단일 동네 측정 — hot key 집중 상황을 대표하나 동네 간 편차는 미측정

## 6. 재현

```bash
docker compose -f docker/docker-compose.bench.yml up -d && ./load-test/seed/run-seed.sh
./load-test/bench/run-app.sh [코드경로] &   # 상태별 코드로 기동 (Flyway가 V19/V20 적용)
cd load-test && npx artillery run -e bench scenarios/place-list.yml --output reports/<상태>-placelist-r1.json
```
