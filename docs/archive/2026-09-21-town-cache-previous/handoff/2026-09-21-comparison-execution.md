> **이전 설계·작업 이력 — 현재 구현 요구사항이나 완료 판정으로 사용하지 마세요.** 현재 기준: [설계 근거와 진행 상황](../../../blog/2026-09-21-place-list-design-evidence.md).

# 로컬 비교 실행 지시 — 다음 Opus5 Task

정본: `docs/design/2026-09-21-place-list-town-cache.md`. 거리순은 제외한다. 이 문서는 측정 전 지시이며 결과가 아니다.

## 소유권

- 원본 `load-test/campaigns/2026-09-21_town-cache-comparison/`의 실행 도구·원자료·보고서와 `docs/perf/2026-09-21-place-list-town-cache.md`를 담당한다.
- 앱 코드/테스트 수정은 integration 담당자 소유다. 메인이 전달하는 integration 검증 완료 및 빌드 슬롯 이후에만 구현 worktree에서 jar/image를 빌드하고 앱을 띄운다.
- 앱 소스 위치는 `/Users/mkyu/orca/workspaces/solply-server/feat-place-list-town-cache`다. 원본 checkout으로 빌드하지 않는다. 최종 jar/image는 HEAD뿐 아니라 dirty diff·untracked source 해시로 식별한다.
- 운영/dev 부하, 무관한 컨테이너 종료, volume 삭제, compose down/remove-orphans, 커밋/푸시/PR/배포 금지. 비밀값/토큰을 로그에 기록하지 않는다.

## 환경/데이터 준비

1. `results/runtime-preflight.md`와 raw를 먼저 읽는다. 실제 DB는 장소/통계 6320, active leaf 66, 빈 leaf 0, V40까지였다. 값은 실행 시 재확인한다. x10/x100 스키마는 사용하지 않는다.
2. 모든 arm에 같은 축소 overlay(app/DB 1536m, app heap768m, DB buffer768m, CPU각2)를 적용한다. redo는 기존1G 유지(조사 당시256M와 구분). 앱을 띄운 후 실제 VM/컨테이너 사용량과 memory.events, OOM, swap 차분을 확인한다. OOM0이 압박 부재나 충분한 여유의 증거는 아니다. 형상을 바꾸면 네 arm 모두 같은 값으로 다시 고정한다.
3. 최초 앱 기동에서 V41~48 마이그레이션을 라운드 밖에서 완료한다. Flyway 결과와 스키마를 기록한다. 기존 벤치 volume/데이터를 보존한다.
4. 메인은 **비교 전용 빈 active leaf 한 행 추가**를 결정했다. 기존 parent 아래 식별 가능한 전용 이름으로 한 행만 추가하고 ID/INSERT 및 되돌릴 조건을 원자료로 기록한다. 기존 장소/태그/북마크를 수정하지 않는다. 이후 leaf 목록/합계/대표값을 다시 고정한다. empty를 coverage에서 빼지 않는다.
5. 토큰은 로컬 도구로 갱신하되 원자료에 담지 않는다. 배치 cron4개는 `-`로 동일하게 끈다. 기동 보정 종료/quiet을 확인한다. legacy snapshot poll은 동일한 간격으로 남기고 실제 비용을 기록한다.

## 먼저 네 arm의 실제 결과를 확인한다

- 같은 이미지, 동일 데이터, GLOBAL_SNAPSHOT / TOWN_PRESORTED / TOWN_REQUEST_SORT / DB_DIRECT.
- 정적5축 × 큰/작은/다중/빈 동네 및 태그 필터, 두 페이지. 모든 arm의 200/schema/순서/정렬키/표시/hasNext가 통과해야 한다. 커서 문자열 자체는 global/town 포맷이 다르다. 고정 데이터에서 표시 drift를 자동 허용하지 않는다.
- GLOBAL은 기존 global contract 그대로다. 후보의 town metadata/RR 비용과 다르다는 사실을 기록한다. 기준선을 개조하거나 계약 비용을 가짜 차감하지 않는다.
- hook/meter 존재만 확인하지 말고 실제 증가량을 확인한다. 공유 load는 한 번, req-sort 합집합 배열은 요청당 한 번, presorted는 사용된 동네 배열 단위다. legacy global 비용은 모든 arm에 실제 발생한 대로 남긴다.

## 작게 시작하는 측정 순서

1. **준비 비용:** 실제 기동 구간과 JVM 기동·probe 이후 준비를 분리한다. switch의 HTTP probe/등가성 요청이 이미 캐시를 데우므로 이후 요청을 JVM 최초 요청이라고 부르지 않는다. 반복 준비는 global reset 호출 내 준비와 town invalidate 뒤 첫 miss를 각각의 창으로 기록한다. warmup 2회 뒤 정본3회부터 시작한다.
2. **재사용:** 같은 동네/번호에서 1·10·100회. 인기 한 축과5축 혼합을 구분한다. 준비 횟수와 축별 생성/사용 차분으로 구간 평균 재사용을 산출한다. 개별 배열 수명 분포라고 하지 않는다. 준비+서빙 CPU/할당 및 총비용 회수 여부를 비교한다.
3. **편중/분산:** 60 arrivals/s, 사용자 흐름당 최대2목록 요청임을 명시한다. 먼저15초 워밍업을 측정창 밖에서 하고30초 정본을 각 arm·분포별3회 시작한다. 또는 도구의45초 전체창을 쓴다면 CPU/SQL/요청수/latency 모두 같은45초 창으로 명시한다. 서로 다른 창의 분모를 혼합하지 않는다. 동일 arm 반복 산포보다 차이가 작으면 그 칸만 확대하거나 판정불가로 남긴다. 무작정 큰 격자를 돌리지 않는다.
4. **변경 뒤 재사용:** 로컬 벤치에서 영향 동네 일부 bump 및 실제 전체 배치 성공 뒤 해당 후보의 재적재를 비교한다. source+version 쓰기는 실제 앱 경로를 이용하거나 별도 명확한 재현 트랜잭션을 기록한다. reset은 bump가 아니므로 버전 교체라고 부르지 않는다. 전체 배치 비용과 그 뒤 목록 준비 비용을 구분한다. 고정 데이터 등가성 게이트를 마친 뒤 별도 창에서 실행한다.
5. **캐시 예산:** 잠정 max-places200000을 근거 있는 운영 수치라고 하지 않는다. 상주 항목/장소 수·live heap·교체 중 비용과 eviction/reload 근거를 연결해 가능한 범위에서 예산을 제안/선택한다. 동네 캐시만의 비용과 남아 있는 legacy global 비용을 분리한다. 힙 엄격 상한이라고 주장하지 않는다.

## 원자료/판정

- 앱/DB cgroup CPU와 throttle, SQL COUNT_STAR를 분모로 한 digest 실행당 비용, 실제 요청수/오류/latency, allocation/GC/live heap, hit/miss/loads/array counts를 같은 창으로 연결한다.
- JFR 표본 추정 할당량과 정확한 할당량을 혼동하지 않는다. 앱 문장의 prepared-statement digest 가시성을 실제 요청으로 확인한다. 없는 지표를0으로 채우지 않는다.
- 로컬 동거 환경·VM 한계와 무효 라운드를 기록하고, 원자료를 덮거나 나쁜 구간만 잘라내지 않는다. fresh JVM/JIT·warm DB·계측 유무를 구분한다. 기존 CPU/JIT 캠페인 결론을 재설정하거나 운영 SLO로 일반화하지 않는다.
- 최종 보고는 네 후보 채택/기각 근거, 조건별 차이와 판정 불가, cache budget/TTL/선적재/동시적재 선택 또는 미결정, 미확인 운영분포를 포함한다. 사전정렬 우승을 전제하지 않는다.
- 이 Task가 전체 성능 실행을 소유하되, 앱 결함/누락 hook은 메인에게 정확한 재현을 보내고 무단 앱 수정을 하지 않는다.
