# 북마크 인덱스 벤치마크 (bookmarks 100만 행)

> 이슈 [#379](https://github.com/SOLPLY/SOLPLY-SERVER/issues/379) · 측정일 2026-07-13 · 캐시 제거([#377](https://github.com/SOLPLY/SOLPLY-SERVER/pull/378)) 후속

## 1. 환경 (로컬 벤치 기준)

| 구성 | 스펙 |
|---|---|
| MySQL | 8.0 컨테이너, **4 CPU / 4GB / innodb_buffer_pool_size=2G** (데이터+인덱스 전부 버퍼풀 상주 — 디스크 I/O 노이즈 제거) |
| 앱 | 호스트 `bootRun` (-Xmx2g), HikariCP 기본 10커넥션. env로 벤치 DB(:3310)·Redis(:6382) 지정 |
| 부하 | Artillery bench 프로파일: 워밍업 10초@20 + 유지 45초@50 req/s, 사용자 1,000명 토큰 랜덤 배분 |
| 한계 | 부하 생성기·앱·DB가 동일 호스트(macOS). 절대치보다 상태 간 상대 비교가 목적 |

측정 프로토콜: 상태별 1회(효과 크기가 큰 비교 실험 — §5에서 결론을 가르는 쌍의 차이가 20% 이상으로 확인되어 추가 반복 불필요). 상태 전환마다 앱 재시작 + `ANALYZE TABLE`.

## 2. 데이터셋

- 합성 사용자 **10만 명** + bookmarks **100만 행** (사용자당 PLACE 9 + COURSE 1, 결정적 매핑으로 uk 충돌 없음)
- `created_at` 최근 1년 균등 분포, 대상은 실데이터 places 320행 / courses 63행
- 시드는 멱등 (`load-test/seed/run-seed.sh`, 실행 ~24초)

## 3. 인덱스 매트릭스

`idx_bookmark_target(target_type, target_id)`는 북마크 조회 4쿼리와 무관(타겟 축 조회용)하므로 S0 외 전 상태에서 상수로 유지 — 매트릭스가 순수하게 user 축 인덱스 설계만 비교한다.

| 상태 | user 축 인덱스 구성 |
|---|---|
| S0 | 없음 (PRIMARY만, FK 해제 — 벤치 DB 한정) |
| S1 (현재) | `uk_bookmark_user_target(user_id,target_type,target_id)` + `idx_bookmark_user_type(user_id,target_type)` |
| S2a | uk만 (중복 prefix 인덱스 제거안) |
| S2b | uk + `idx_bookmark_user_type_created_target(user_id,target_type,created_at DESC,target_id)` |
| cacheon | S1 + #377 이전 코드(Redis ZSET 북마크 캐시, worktree bb61d96) — r1 콜드 / r2 웜 |

## 4. 쿼리 레벨 결과 (EXPLAIN ANALYZE, 5회 중 콜드 제외 대표값)

| 쿼리 | S0 | S1 | S2a | S2b |
|---|---:|---:|---:|---:|
| 동네별 목록 (ORDER BY) | **239ms** (풀스캔+filesort) | 0.073ms (ref) | 0.124ms (ref) | **0.058ms (커버링)** |
| 폴더 프리뷰 (윈도우 함수) | **246ms** (풀스캔) | 0.176ms | 0.079ms | 0.081ms (커버링) |
| batch IN (30개) | **208ms** (풀스캔) | 0.128ms (ref) | 0.050ms (uk range 커버링) | **0.018ms (커버링 ref)** |
| exists 단건 | 145ms | ~0 (const) | ~0 (const) | ~0 (const) |

- S0: 4쿼리 전부 `type=ALL`, rows≈996,517 — 쿼리당 100만 행 전수 스캔
- S2b만 목록·batch IN에서 `Using index`(커버링) — 테이블(클러스터드 인덱스) 재탐색 제거
- S1→S2a 시 batch IN이 `idx_bookmark_user_type` ref → uk range로 전환(옵티마이저): uk가 prefix 조회를 완전 대체하지 못하는 케이스 확인
- 윈도우 함수의 `Using temporary; Using filesort`는 전 상태 공통 (ROW_NUMBER 구조상 불가피, 대상 9행이라 무시 가능)

## 5. API 레벨 결과 (Artillery, 상태별 1회 / baseline 2회)

### 종합 (시나리오당 8,450 요청)

| 상태 | 성공률 | place p95 / p99 (ms) | course p95 / p99 (ms) |
|---|---:|---:|---:|
| **S0** | **3.0% / 0.0%** | 9,230 / 9,801 (성공분만) | 측정 불가 (전량 ETIMEDOUT) |
| S1 | 100% | 22.9 / 41.7 | 23.8 / 39.3 |
| S2a | 100% | 22.9 / 36.2 | 23.8 / 27.9 |
| **S2b** | 100% | **22.0 / 27.9** | 23.8 / 30.3 |
| cacheon r1 (콜드) | 100% | 23.8 / 32.1 | 25.8 / 32.1 |
| cacheon r2 (웜) | 100% | 24.8 / 37.7 | 25.8 / 39.3 |

### 판정

1. **S0 → S1: 서비스 생존의 문제.** 무인덱스에서 place 성공률 3%(8,196건 ETIMEDOUT), course 0%. 쿼리당 ~200ms 풀스캔이 50 req/s에서 DB를 즉시 포화시켜 큐잉 폭주 → 사실상 전면 장애. 인덱스 추가만으로 성공률 100%, p99 9,801ms → 41.7ms.
2. **S1 → S2b: 꼬리 지연 유의미 개선.** p95는 전 상태 동급(±2ms)이나 p99는 place preview 47.9→26.8ms(−44%), place bookmark 37.7→29.1ms(−23%) 등 **20% 이상 개선**. 커버링 인덱스가 테이블 재탐색을 제거해 경합 시 꼬리가 짧아지는 효과.
3. **S1 → S2a: p99 개선은 있으나(course 축 −26%), batch IN이 uk range로 전환되는 부작용 확인** — S2a 단독으로는 어중간.
4. **cacheon(웜) vs DB 직행: 동급 (p95 차이 ≤3ms).** Redis ZSET 캐시가 웜 상태여도 인덱스 탄 DB 직행 대비 이득이 없음 — 왕복·직렬화 비용이 인덱스 조회 비용과 상쇄. **#377 캐시 제거 결정의 실측 근거.**

## 6. 결론 — S2b 채택

**Flyway V19: `idx_bookmark_user_type` DROP + `idx_bookmark_user_type_created_target(user_id, target_type, created_at DESC, target_id)` CREATE**

근거:
- p99에서 S1 대비 20~44% 개선 (트래픽 증가 시 경합이 심해질수록 커버링의 이점 확대)
- 목록·batch IN 쿼리가 커버링(`Using index`)으로 처리 — 4쿼리 전부에 대해 최적 계획
- 중복 prefix였던 `idx_bookmark_user_type` 제거로 인덱스 수는 동일(3개 유지) — 쓰기 오버헤드 순증 없음
- 읽기 중심 워크로드(북마크 조회 ≫ 생성/삭제)라 4컬럼 인덱스의 유지 비용 대비 이득이 큼

## 7. 한계·이상 신호

- 로컬 단일 호스트 측정 — 절대치는 참고용, 상대 비교가 유효
- 상태별 1회 측정 (결론을 가르는 차이가 전부 20% 이상이라 추가 반복 생략)
- cacheon r2(웜)가 r1(콜드)보다 일부 지표에서 느림 — GC/측정 노이즈 추정, "웜 캐시 = 항상 빠름" 아님에 유의
- EXPLAIN은 북마크 ~9건 사용자 기준 — 헤비 유저(수백 건)에선 커버링 이점이 더 커질 것
- **부수 발견**: Artillery `config.http.defaults.headers`는 payload 변수를 치환하지 않음 (`Bearer undefined`) — 요청 레벨 헤더로 수정. #349 당시 측정도 동일 구조였으므로 과거 수치 해석 시 유의

## 8. 재현 방법

```bash
docker compose -f docker/docker-compose.bench.yml up -d
./load-test/seed/run-seed.sh                      # 시드 (멱등)
cd load-test && npm run gen:users && cd ..        # 토큰 생성
./load-test/bench/apply-state.sh S2b              # 인덱스 상태 전환
./load-test/bench/run-explain.sh S2b              # 쿼리 레벨
./load-test/bench/run-app.sh &                    # 앱 기동 후
./load-test/bench/run-api.sh S2b 1                # API 레벨
```
