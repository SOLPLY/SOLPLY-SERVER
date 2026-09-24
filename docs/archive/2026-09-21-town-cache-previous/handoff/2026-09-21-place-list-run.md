> **이전 설계·작업 이력 — 현재 구현 요구사항이나 완료 판정으로 사용하지 마세요.** 현재 기준: [설계 근거와 진행 상황](../../../blog/2026-09-21-place-list-design-evidence.md).

# 동네 캐시 구현·비교 Run 진행 기록

## 권한과 형상

- 새 메인 Run: `run_ff6af38c2ab0`. 이전 사전조사 Run은 재사용하지 않았다.
- 원본: `/Users/mkyu/Desktop/SOPT/solply-server`, `develop@64a97e08274303199b33244ba579adde22e4437d`.
- 시작 시 tracked 변경 없음. 미추적 최종 설계와 handoff 문서, ignored TODO와 로컬 설정을 보존한다.
- 구현 위치: `/Users/mkyu/orca/workspaces/solply-server/feat-place-list-town-cache`.
- 브랜치: `uykm/feat-place-list-town-cache`.
- Task `task_4b1ea6c3994e`, Dispatch `ctx_90e7529b0d88`.
- 작업자 `term_a125fa63-289d-4eaa-8029-f94d09845b18`. receipt의 requested/effective 모두 `claude-opus-5`, dispatch input accepted, liveness live 확인.
- 메인은 조정·설계·코드/계약 검토, Opus5는 구현·테스트·비교 도구 및 실행 담당. PR/배포/커밋/푸시 없음.

## 단계별 실행과 완료 기준

1. **계약 구현**: 작업자가 현재 코드를 읽고 구체 파일/인터페이스 계획 작성 후 구현한다. 메인은 metadata/source/read view, 쓰기 분류, frozen 표시 객체, 커서의 leaf 범위, 전체 요청 1초 예산을 검토한다.
2. **기능 검증**: 단위 테스트와 MySQL 별도 연결/latch 경쟁 테스트로 정합성을 증명한다. 테스트 XML의 실패·오류·skip 수와 명령을 기록한다. H2나 Docker 부재에 따른 skip을 MySQL 통과로 부르지 않는다.
3. **비교 준비**: 기능 검증을 통과한 정적 5축을 전체 기준선 / 동네 사전정렬 / 동네 객체+요청정렬 / DB 직접으로 비교한다. 동일 데이터·결과·페이지 계약을 먼저 확인한다. 기존 기준선의 계약 보정 비용과 전략 자체 비용은 따로 기록한다.
4. **측정**: 단일 동네 준비 비용부터 시작한다. 이후 hit/cold miss, 편중/분산, 일부 버전 갱신/전체 배치 후 조회, 배열별 재사용 횟수를 비교한다. 환경 조사 후 데이터 규모·예산·반복·기간을 캠페인 README에 수치로 고정하고 실행한다. 기존 수치와 새 수치의 절대 우열 비교는 하지 않는다.
5. **선택과 종료**: 요구사항별 구현/테스트 근거, 원자료/형상, 채택·기각 이유, 최종 cache budget과 미확인 한계를 남긴다. 근거가 부족한 성능 결론이나 운영 SLO는 확정하지 않는다.

## 메인 사전 코드 검토 초점

- 현재 `PlaceListRequestOrchestrator`는 metadata를 호출 스레드에서 읽는다. 새 경로는 DB 커넥션·작업 큐·여러 동네 대기까지 요청 예산을 공유해야 한다.
- 현재 `PlaceService`는 leaf 범위를 반복 해석하고 표시 holder에 없는 행을 제거한다. 새 경로는 관측 범위와 확보 객체를 응답까지 유지해야 한다.
- `AdminPlaceService`뿐 아니라 `BookmarkCountDeltaProcessor`, `AdminTownService` 활성화, `PlaceStatsBatchProcessor`의 모든 source 쓰기 경계를 검토한다.
- 현재 어드민 upsert는 기존 통계/정렬 카운트를 덮지 않는다. 이름/썸네일만 변경한 경우 버전을 올리지 않는 정책과 맞춰 유지한다.
- 거리 경로는 이번 최적화·비교에서 제외한다. 필요한 공통 코드 호환 경계만 명시한다.

## 환경

- 초기 재확인에서 Docker daemon 연결 실패: `unix:///Users/mkyu/.docker/run/docker.sock`.
- 작업자에게 비파괴 로컬 복구 가능 여부와 Testcontainers 실행 가능 여부 확인을 맡겼다. 운영/dev 부하와 타 컨테이너 종료는 금지한다.
- 측정 시 가이드의 cgroup CPU, digest 실행당 비용, allocation/GC/live heap, p50과 오류를 연결한다. 호스트 스왑 전후 차분·무효 라운드를 보존한다.

Docker Desktop 기동 후 daemon 28.0.1 연결이 복구됐다. 기능 검증과 비교 결과는 아직 없다.

## 구현 중 메인 리뷰 전달 사항

아래는 완료 판정이 아니라 작업자에게 전달한 수정·검증 요청이다.

- 동네별 resident cache는 최신 한 항목. `(town,version)`은 공유 적재 식별에 쓰되 구버전 payload의 별도 보관으로 확장하지 않는다. 빈 동네 weight도 최소 1로 둔다.
- 거리순의 기존 v7 커서를 일괄 만료시키지 않는다. 새 정적 커서만 분리하고 field delimiter 충돌을 피한다.
- 63,200 장소는 과거 x10 실험 규모다. 현재 데이터 규모나 cache budget의 검증 근거로 바꿔 쓰지 않는다.
- 어드민 동시 A→B/A→C 수정에서 나중 쓰기의 실제 출발지 B를 놓치지 않도록 writer 직렬화와 최신 원본 관측을 검증한다.
- 요청정렬 대안은 적재 시 5축 배열을 만들지 않는다. 동네별 정렬 후 합집합 재정렬도 제거하고 한 번 정렬한다.
- 공유 적재 registry는 실행기 거절/inline 실행에도 완료 future가 영구 잔류하지 않아야 한다. 일반 비동기 완료만으로 누수가 확정된다는 초기 설명은 CHM bin 잠금 때문에 정정했다. 핵심 검증은 mapping 함수 내부의 동기 재진입·거절 및 실패 후 재시도다.
- 동기 metadata 조회 뒤에만 timeout을 걸면 connection 대기가 1초를 넘길 수 있다. 요청 진입에서 시작한 독립 응답 future의 예산으로 metadata/큐/적재 대기를 모두 감싼다. cache all-hit 경로도 예산 경계를 검증한다.

현재 상태: 구현 Task 진행 중. 최종 diff와 테스트 증거에 대한 메인 검토는 아직 남아 있다.

## 비교 계획 담당 (독립 문서 작업)

- Task `task_7647073495aa` / Dispatch `ctx_1142a360377d`, Opus5 requested/effective 확인, 완료 보고 수신.
- 소유 파일은 원본의 새 캠페인 `load-test/campaigns/2026-09-21_town-cache-comparison/README.md`, `results/environment-preflight.md` 둘뿐이다. 앱/DB/컨테이너 변경이나 부하 실행은 하지 않았다.
- 같은 터미널 `term_d645ce26-14a5-4f30-91c5-7a2be9dd9d6d`에 후속 Task `task_9047c9e45c1c` / Dispatch `ctx_77d5d00b748f`를 배치했다. arrival/request 구분, cold/warm 대조, 실제 leaf 집합, digest 분모를 보정한 완료 보고를 수신했고 해당 worker resource를 release했다.
- 환경의 Docker VM 3.83GiB와 컨테이너 최대치 합 5GB는 overcommit 위험이다. 그 자체로 실제 메모리 압박이나 실행 불가능을 확정하지 않는다. DB 실제 규모/Flyway 상태는 정지된 컨테이너를 읽지 않아 현재 미확인이다.
- 비교 후속 구현에는 DB_DIRECT 연결과 실제 hit/miss/배열 재사용 계측이 필요하다. 네 팔 비교를 세 팔로 축소하지 않는다.

## 중간 계약 검토 및 비교 도구 준비

- 구현 worktree의 `docs/handoff/2026-09-21-main-contract-review.md`에 필수 리뷰를 통합했다. 표시값 변경의 actual admin 경로, 동시 이동 writer 잠금, 전체 대기 예산, resident 최신 한 항목, v7 호환, 진짜 objects-only 후보, flight 등록/실행 경계를 요구한다. 중간 테스트 통과는 이 리뷰 통과를 대체하지 않는다.
- 기존 버전 bump 테스트 13개, flow 11개, concurrency 7개의 MySQL XML 통과를 확인했지만 실제 facade 분류/source 변경 read view/배치 rollback 증거가 부족했다. 해당 검증을 보강하도록 전달했다. 전체 회귀의 최초 실패들은 작업자가 수정 중이다.
- Task `task_8d6223f64a3e` / Dispatch `ctx_c95d8b88d904`, 터미널 `term_1cfaba7b-45b4-459b-bb5a-f422b6a11749`에 비교 실행 도구 준비를 배치했다. 요청/effective 모두 `claude-opus-5`, `input_accepted`와 `turn_started` 확인.
- 소유권은 원본 `load-test/campaigns/2026-09-21_town-cache-comparison/` 하위뿐이다. 앱/DB/컨테이너 변경과 실제 부하 실행은 금지하고 앱 hook 계약·등가성/원자료 수집 도구를 준비한다.
- 초기 문서의 24라운드 파일럿/48라운드 큰 격자는 최종 실행 약속이 아니다. 네 전략·준비·재사용·편중/분산·갱신 질문은 유지하되 작은 반복 실험부터 시작하고 산포/오류에 따라 확대한다. 운영 SLO/실제 분포는 주장하지 않는다.

### 17:11 UTC 이후 수정·분리 작업

- 구현 작업자가 inbox 17건과 통합 리뷰 문서를 읽고 7개 전부 반영에 착수했다고 회신했다. 중간 전체 테스트 680/0/0/0은 수정 전 결과이며 최종 통과 근거가 아니다.
- DB 직접 조회 reader를 독립 Task `task_8b34ea2ac11b` / Dispatch `ctx_b094b36071de`, 터미널 `term_2b234ad8-c136-46af-91fb-fe37b2acc8fd`에 배치했다. requested/effective `claude-opus-5`, input accepted/turn started 확인.
- 소유권은 새 `TownDbDirectReader.java`, `TownDbDirectReaderIT.java`, reader 검증 문서뿐이다. 기존 앱 파일은 읽기 전용이며 우선 `/tmp` 전용 staging에서 완성한다. worktree 반영 및 Gradle/Testcontainers 실행 전에 메인에게 슬롯을 요청한다. 현재 Gradle 실행 소유자는 core 구현 작업자다.
- 비교 도구의 중간 등가성 판정에서 동일 401/500·잘못된 body·빈 cases도 통과할 수 있는 허점을 발견해 실패 처리와 selftest 보강을 요청했다. 고정 데이터 비교에서 표시값 차이는 자동 허용하지 않는다.
- 계측 계약은 정렬축별 재사용 근거를 포함하고, global 준비를 수행하는 reset 호출이 측정창 밖으로 사라지지 않도록 보정 중이다. profile은 운영 이미지에서 클래스 제거와 다르다는 문서 오류도 정정 요청했다.

### API 연결 중단과 정상 재시도

- 17:30~17:32 UTC 세 Opus 최종 turn이 `UNKNOWN_CERTIFICATE_VERIFICATION_ERROR` 또는 `ECONNRESET`로 끝났고 `worker_done`을 보내지 못했다. transcript의 마지막 오류 메시지와 activity done을 확인했다. 단순 heartbeat 부재로 중단을 추정한 것이 아니다.
- 실패 attempt를 `worker-abandon`으로 fence한 뒤 같은 Task·worktree·기존 Opus5 터미널을 `--retry-of`로 재사용했다. 파일/맥락을 보존했고 TLS 검증·인증서 신뢰 설정은 변경하지 않았다.
- core의 새 Dispatch `ctx_3a666d785037`; 도구 `ctx_a4543ea449e6`; DB reader `ctx_0e1c448ce41d`. 모두 input accepted/turn started, 같은 provider process incarnation 확인. terminal reuse receipt의 launch model null은 모델 변경을 뜻하지 않는다. 최초 requested/effective Opus5 근거는 위에 있다.

### 18:02 UTC 계약 검증 및 비교 연결 준비

- 비교 도구 보완 Task `task_f39a985cf699` / Dispatch `ctx_0853a0a86d5b`를 같은 도구 Opus5 터미널에 즉시 재사용 배치했다. 오류/잘못된 본문의 거짓 동등성 통과, 숫자처럼 생긴 표시 문자열의 오정규화, ApplicationReady 준비 완료 판정, legacy global 비용 누락을 보정 중이다. fixture selftest 45/45 로그를 읽었으나 실제 앱/부하 검증은 아직 수행하지 않았다.
- core는 동시 이동 latch 검증, winner cache 재확인, 응답 조립 executor 분리, metadata/큐/커넥션 예산 IT, 확보 후 다른 writer 커밋 검증을 추가했다. 695/0/0/0 XML 요약이 있으나 아직 최종 형상의 통합 완료 근거는 아니다.
- 17:59:44 core 슬롯 양도 보고를 받아 18:00:18 reader 설치/단일 IT 슬롯을 배정했다. 그러나 메인이 빌드 로그와 terminal screen을 확인해 core가 양도 뒤 clean build를 다시 실행한 것을 발견했다. 둘의 실행 결과가 겹칠 수 있어 해당 빌드는 최종 근거에서 제외한다. 현재 작업의 자연 종료를 기다리고 단독 reader 검증 후 core 슬롯을 돌려준다. 강제 종료는 하지 않았다.
- 추가 인프라 예외 정규화 검토 요청은 core에 전달된 상태다. DB metadata/트랜잭션 가용성 오류는 재시도 가능 오류로 반환하되, 잘못된 요청·만료·프로그래밍 오류와 섞지 않도록 요청했다.
- 구현 worktree에 `docs/handoff/2026-09-21-comparison-integration.md`를 작성했다. 다음 Opus5 작업은 DB_DIRECT 요청 연결과 최소 bench 계측/준비 통로다. GLOBAL 기준선을 새 town 계약으로 개조하지 않고, 기존과 후보의 계약 비용 차이는 기록한다.
- 앞선 17:37:38 UTC 검증 기록: 잠금 current read 수정 뒤 `TownVersionBumpIT` 17 tests / 0 failures / 0 errors / 0 skipped를 확인했다. 이 이전 결과를 현재 전체 회귀 완료 근거로 쓰지 않는다.
- 도구 보완 Task는 18:02:28 worker_done으로 종료했고, 같은 Opus5 터미널을 로컬 DB/형상 준비 Task `task_329c665d39e3` / Dispatch `ctx_8639047f25e4`로 재사용했다. input accepted/turn started와 같은 provider process incarnation을 확인했다. 해당 작업은 bench DB 읽기 조사 및 campaign resource overlay만 소유하며, 앱/부하/Gradle은 실행하지 않는다.

### 18:18 UTC 기능 검증에서 비교 실행 준비로

- reader의 단독 최종 IT는 18:07:53 UTC XML 9/0/0/0, 전체 로그 EXIT=0이다. 메인이 구현 및 실제 metadata→별도 연결의 삭제+bump 커밋→page/hydrate 순서로 고정된 테스트를 읽고 승인했다. `ctx_0e1c448ce41d` worker_done을 수신하고 해당 터미널을 release했다. XML 사본도 worktree `docs/verification/logs/2026-09-21-db-direct-reader-result.xml`에 보존했다.
- core로 슬롯을 반환한 뒤 인프라 예외 정규화가 추가됐고 전체 706/0/0/0, clean build exit0을 메인이 직접 확인했다. 이후 비동기 재관측 실패 경로의 누락을 보완하고 전용 테스트를 추가하여 다시 최종 build 중이다. 앞선 706 결과는 이 마지막 변경 전 결과다.
- 실제 로컬 DB 조사: places/place_stats 6320, towns84, active leaf66, 빈 leaf0, bookmarks10405825, FlywayV40. 앱/DB 각1536m, heap/buffer768m, CPU각2의 동일 비교 overlay를 준비했다. 조사 당시 redo256M은 원자료로 남기고 실제 비교에서는 기존1G 유지로 정정했다. OOM0을 압박 없음이나 충분한 여유로 해석하지 않는다.
- 런타임 조사 Task `task_329c665d39e3`는 worker_done으로 완료했다. 같은 터미널을 실제 비교 Task `task_4cc714f265f4` / Dispatch `ctx_2d6657851295`로 재사용했고 input accepted/turn started 및 동일 Opus5 process incarnation을 확인했다.
- 메인이 `docs/handoff/2026-09-21-comparison-execution.md`를 작성했다. 측정 담당자는 지금 bench DB의 redo1G 적용, 전용 빈 leaf 한 행 및 raw 갱신, 측정 도구 준비를 수행할 수 있다. 앱/Gradle/이미지 빌드/부하는 integration 완료와 메인의 빌드 슬롯 전달까지 대기한다. 빈 leaf 추가는 메인이 승인된 검증 범위 안에서 결정했으며 사용자 재승인 대상이 아니다.
- 최종 채택은 먼저 새 동네 계약 충족이 조건이다. 기존 GLOBAL_SNAPSHOT은 비용 기준선이며, 그 성능이 더 빠르더라도 영향 동네만 만료 등 요구사항을 버리는 선택으로 연결하지 않는다.

### 18:48 UTC integration 및 측정 세션 재개

- core 계약 Task는 최종 clean build 707 tests / fail0 / error0 / skip0 / exit0으로 종료했다. 메인이 전체 로그 및 XML 80 suite 집계를 직접 확인했고, 완료 터미널을 integration Task `task_4d396e4349c6`로 즉시 재사용했다.
- integration 첫 Dispatch `ctx_c89b0e4417a4`는 입력 수신 뒤 API `UNKNOWN_CERTIFICATE_VERIFICATION_ERROR`로 최종 turn이 종료됐다. transcript의 오류와 미보고를 확인한 뒤 abandon하여 fence하고 같은 Task/터미널로 재시도했다. 현재 Dispatch는 `ctx_d660ecdd884f`다. 재시도 초기 receipt는 turn_start_unobserved였지만 이후 실제 task 문서 읽기 tool call 및 working 상태를 확인했다. 시작 확인 지연만으로 두 번째 중복 실행을 만들지 않았다.
- 측정 Dispatch `ctx_2d6657851295`도 API `ECONNRESET` 최종 오류로 종료되어 같은 절차로 fence했다. 현재 retry Dispatch는 `ctx_cb2d042b059b`, 같은 Task `task_4cc714f265f4` 및 터미널이다. input accepted/turn started 확인. 적용된 fixture 여부는 재시도에서 실제 DB를 확인하도록 지시했다.
- TLS/인증서 설정은 변경하지 않았다. 로컬 curl의 api.anthropic.com TLS 검증은 0(성공), HTTP404였으나 이를 Claude 연결 오류의 원인 규명으로 해석하지 않는다.
- 현재 integration만 앱 파일/Gradle을 소유한다. 측정 담당자는 bench DB·campaign 준비만 진행하고 앱/이미지/부하는 아직 시작하지 않는다.

### 18:57 UTC 측정 전 원자료 경계 검토

- 측정 담당자의 독립 준비 보고: bench-mysql만 같은 볼륨으로 재생성하여 redo1G 실효 적용. 빈 active leaf `9001 / __empty_leaf_fixture__ / parent201` 한 행 추가, towns85/leaf67/empty1. 장소·통계6320, 태그연결15551, 북마크10405825 유지. 원자료는 campaign results/raw 13~18에 남겼다. 앱/Gradle/이미지/부하는 아직 시작하지 않았다.
- 메인이 도구 코드에서 `WANT` 환경 변수가 파이프 왼쪽에만 전달되어 카운터 파일이 비어도 성공할 수 있는 오류를 발견했다. global build/prepare 수집 누락, 라벨 후행쉼표 의존 파서, 누락 지표를 0으로 합치는 문제도 수정 요청했다. 워밍업과 정본 창, CPU 표본 실제 구간 및 전체 요청 분모 오차를 구분하도록 요구했다.
- 메인이 integration 중간 코드에서 same-version global 재구성은 실제 읽기·정렬을 했는데 install=false 때문에 build/prepare 계측이 누락되는 경로를 발견했다. 실제 construction과 설치 여부를 분리해 집계하고 전용 검증을 추가하도록 전달했다. 이는 완료 판정이 아닌 검토 중 수정 사항이다.

### 19:17 UTC 측정 도구 검토 마무리, 통합 최종 검증 대기

- 도구 담당자는 원자료 19~21에 noload 워밍업 분리/실행 fixture, meter 환경전달 오류 재현·수정, 라벨 순서/후행쉼표 독립 파서, 누락=null, nonzero 초기값 Timer count/sum 차분 검증을 남겼다. 분석 selftest34/34 및 등가성45/45 보고. 메인은 관련 코드 변경을 읽었다. 실제 앱 scrape 및 부하 실행은 아직 하지 않았다.
- JFR은 host mount에 dump와 repository를 보관하되 유효 기록의 근거는 종료 후 읽을 수 있는 실제 파일이다. `filename` 설정만으로 진행 중 청크가 보존된다고 주장하지 않는다. CPU 표본 구간과 전체 요청 구간의 차이를 coverage로 남기고 근사값으로 해석한다. 준비 Timer는 kind=town/global을 분리한다.
- 통합 담당자가 same-version global 계측 위치, benchChain의 profile+property 조건, registry 설명을 수정했다. 같은 버전 재구성 계측의 negative control을 보고했으며 최종 로그 검토는 남았다.
- 메인이 19:12:13 UTC `BenchPrepResetTownIT` XML 10/0/0/0과 테스트 코드를 확인했다. 실제 MockMvc `/actuator/prometheus` 응답에서 10종 지표를 검증한다. 테스트의 기본 exporter 비활성은 `@AutoConfigureObservability`로 해결했다. build.gradle 의존성 변경은 불필요하며 원본 유지다.
- Gradle 런처 JDK25의 스크립트 파싱 문제가 관측되어 최종 clean build는 설치된 Temurin21 경로를 명시하도록 했다. 다른 daemon 전체 종료는 하지 않는다. 현재 통합 담당자만 Gradle/앱 파일을 소유하며, 측정 담당자는 명시적인 슬롯 전달을 기다린다.

### 19:23 UTC 통합 완료, 실제 비교 실행 슬롯 전달

- integration Task `task_4d396e4349c6` / Dispatch `ctx_d660ecdd884f`는 19:22:06 UTC succeeded로 종료했다. 메인이 JDK21 clean build 전체 로그 exit0 및 XML86 suites/752tests/fail0/error0/skip0을 직접 확인했다. `git diff --check`도 성공했다. 707 근거 로그는 보존됐다.
- 메인은 보고서 `docs/verification/2026-09-21-comparison-integration.md`의 사실 경계를 정정했다. 총 요청 예산 공통은 세 후보이고 GLOBAL은 기존경로/예산이다. build.gradle 원본 유지가 JDK25 재파싱 안전을 뜻하지 않으며 운영 실제 설정은 미확인이다. 앱/테스트 변경은 없었다. DB_DIRECT timeout 자체를 별도로 재현한 테스트는 없다는 한계도 유지했다.
- 구현 담당자가 19:21:31 앱/테스트 변경·Gradle 실행 종료를 명시 확인했다. 이를 받아 19:21:55 측정 Dispatch `ctx_cb2d042b059b`에 앱/Gradle/이미지/로컬 부하 독점 슬롯을 전달했다. 실제 HTTP smoke·등가성·부하는 이제 이 작업자 소관이다. 같은 worktree dirty code와 동일 이미지로 수행하며 추가 앱 수정은 메인에게 요청한다.
- integration worker terminal은 완료 수신 후 release했고 transcript archive captured다. 현재 live Task는 측정 `task_4cc714f265f4` 하나다. 실제 측정 결과/최종 선택/캐시 예산 판단 및 종료 기록은 아직 남아 있다.

### 19:37 UTC 실제 연결 및 네 방식 등가성 통과

- worktree JDK21 bootJar로 만든 이미지 `solply-bench-app:towncache-8aa914d2b85f`, image ID `sha256:935fed8570ba85ca9b810ea62c695a9c32ec15405626f4cd871f11b940c4daea`. 원자료22에 HEAD·dirty diff·새 src 해시·jar 해시가 있다. 메인이 식별값을 읽었다.
- 측정 담당자는 라운드 밖에서 V41~48 적용, 실제 H1 10종 scrape 및 H2 dryRun 확인을 보고했다. 옛 벤치 토큰의 현재 인증 클레임 불일치는 캠페인 전용 토큰 생성기로 해결했다. shared 파일과 앱 인증정책은 바꾸지 않았다. `calculated_at`→실제 `score_calculated_at` 도구 질의 및 compose로 전달되지 않던 bench profile 설정도 수정했다.
- 메인이 `results/equivalence/compare-GLOBAL_SNAPSHOT-vs-{TOWN_PRESORTED,TOWN_REQUEST_SORT,DB_DIRECT}.json`을 읽었다. 각 gateOpen=true, equivalent21, display-only/mismatch/invalid/unavailable/missing 모두0, required/passing21, twoPageCheckedCases16. 5정렬·큰/작은/상위합집합/빈동네 및 태그 필터 포함. 표시값 차이 허용 옵션은 false다.
- 이 근거는 고정 데이터의 HTTP 결과 등가성이다. 실제 자원/지연·교체 비용 비교 및 최종 채택은 아직 진행 전/중이며 완료로 기록하지 않는다. 앱+DB 동시 기동의 메모리·VM·swap/OOM 기준점도 남기도록 전달했다.

### 19:47 UTC 초기 재사용 측정 도구 오류

- 메인이 `s2-{global,presorted,reqsort,dbdirect}-reuse{1,10,100}.serial.csv`가 헤더뿐임을 확인했다. 유효 성능 자료가 아니다. GLOBAL prep.log 실제 응답은 200/`preparedDuringCall=true`/`prepareMillis=49`였으나 도구는 필드 부재로 판정했다. basic sed의 GNU식 alternation에 기대는 boolean 파싱을 발견해 수정 요청했다.
- 앱/이미지는 바꾸지 않는다. H2 필드 타입과 versionsBumped=0을 실제 검증하고 단일 smoke부터 통과시키며, 실패한 attempt는 새 라벨/별도 경로로 보존하도록 전달했다. screen에서 worker가 `s2-dbdirect-reuse1.*`를 삭제 후 재실행한 것을 확인해 이 손실도 최종 무효 시도 기록에 명시하도록 요청했다. 다른 초기 파일은 메인이 19:46 읽을 때 남아 있었다.
- 후속 inbox를 자연 체크포인트마다 전체 body로 읽고 ack할 것을 다시 지시했다. 긴 shell arm 루프가 실패를 다음 arm으로 넘기지 않도록 exit와 stderr를 확인해야 한다. 현재 성능 수치나 선택 결론은 아직 없다.

### 20:04 UTC 재사용 원자료 확인 및 반복 부하 진행

- 이후 worker screen에서 `find results/metrics -name 's2-*' -delete` 실행을 확인했다. 위 초기 무효 시도의 원본은 손실됐다. 메인 관측 기록이 남아 있다는 것과 원자료가 보존됐다는 것은 다르다. 삭제를 중지하고 새 라벨로 보존할 것을 다시 지시했다.
- 새로 생성된 Stage2 CSV는 4방식 × 재사용1/10/100 모두 예정된 measure 행 수와 HTTP200을 담고 있다. 다만 N>1 도구가 MIX 설정을 무시하고 정적5축을 순회하므로 이 자료는 혼합 조건이다. 인기순 전용10/100은 별도 라벨로 추가하도록 했다. 이 한 번씩의 결과만으로 우열/회수 시점을 확정하지 않는다.
- Stage3 편중 분포 진행 중. GLOBAL 3회 각각 client attempts3600/responses3600/HTTP2003600/vusers.failed0을 메인이 읽었다. r1은 client interval30.3초, 외곽 수집37초, CPU 표본35초다. clock.txt의 '서버가 받은 요청수' 및 모든 분모가 정확히 같다는 설명은 부정확하여 정정 기록을 요청했다. 기존 raw는 덮어 고치지 않는다.
- r1 digest.diff에 실행행이 없음을 확인했다. 실제 prepared execute 문장 가시성을 검증하고, 미관측이면 SQL 실행당 비용은 미측정으로 기록하도록 전달했다. 0으로 해석하지 않는다.
- worker가 긴 shell 루프 동안 필수 inbox 리뷰를 읽지 않아, 기존 Task 유지 및 다음 명령 전 전체 body 확인을 요구하는 주의 알림을 terminal에 한 번 전달했다. 화면상 현재 실행 뒤 queued 상태를 확인했다. 별도 Task/ownership 전달이나 추가 실행 명령은 아니다.
- core 검증 문서 상단에 후속 통합752개 검증 및 DB_DIRECT 연결 문서를 연결했다. 앱/테스트/이미지에는 변경하지 않았다. 실제 비교와 최종 선택/예산/종료 기록은 계속 진행 중이다.

### 20:16 UTC skew 12회 완료 및 비교 판정 검토

- worker가 20:07 UTC 필수 inbox9건 전체 읽기/ack 및 초기 s2 원자료 손실을 명시 보고했다. 손실 기록은 campaign `results/discarded/2026-09-21-lost-s2-attempts.md`에 생겼으며 복구본이 아니다. JSON 타입/versionsBumped=0 검증과 인기순 N>1 처리 수정 코드를 메인이 읽었다.
- 4방식 × skew3회가 모두 client attempts/responses/HTTP200 3600, 오류0으로 완료됐다. raw27의 p50은 네 구성 모두 6/5/5ms. 앱 CPU는 반복 내 감소 폭이 구성 간 중앙값 차이보다 컸다. 첫 회를 사후 제외하지 않는다.
- raw27의 DB CPU 판정은 최대-최소 차이로 최저 구성을 전체 승자처럼 출력하는 오류가 있다. presorted1.195와 request-sort1.211ms/request 차이는 각 반복 범위보다 작다. 쌍별 또는 극단 쌍만의 기술적 비교로 고치고 원raw27은 보존하도록 요청했다. 통계적 유의성이나 사전정렬 우승의 근거가 아니다.
- raw25는 앱 문장이 prepared_statements_instances에서 실행되고 digest에는 없는 상황을 기록한다. 서버 준비문장 설정을 바꾸지 않고 digest 기반 비용을 미측정/null로 남기는 선택을 메인이 수용했다. 앱/DB CPU 관측과 문장별 비용 미관측을 구분한다.
- worker가 예전 campaign README의 첫 라운드 폐기 문구를 이유로 skew4×4 재실행을 시작했으나, 최신 Task 지시 `2026-09-21-comparison-execution.md`는 외부15초 warmup + 정본3회다. 메인은 전면 재실행 불필요 및 아직 없는 필수조건 우선으로 결정했다. 현재 라운드 자연 종료/원자료 보존 후 후속 arm을 시작하지 않도록 전달했다. 이후 실행은 arm 단위로 끊고 inbox 전체 확인 후 다음 arm에 들어간다.
- 후속 정리 항목: V48 마지막 SQL 주석은 현재 구현과 달리 모든 커서가 decode에서 거부된다고 적혀 있다. 실제 v7/v8 decode는 둘 다 유지되고, 새 town 경로의 G/T scope 불일치가 옛 정적 커서를 만료시킨다. GLOBAL/거리 v7은 유지한다. 측정 중 앱 변경은 금지하며 측정 종료 후 Opus5 소유권으로 주석을 정정한다.

### 20:23 UTC 실행 중첩 발견 — 앞선 s3b 종료 보고 정정 필요

- worker의 20:18 보고는 s3b GLOBAL만 기동/라운드0/후속arm0이었다. 그러나 이후 자체 조사에서 driver PID46629가 살아 presorted 부하까지 실행했음을 발견했다. 메인도 `s3b-global-skew-r1/r2`, `s3b-presorted-skew-r1/r2` 실제 HTTP3600 보고서를 읽었다. 앞선 종료 보고는 사실이 아니다.
- 이에 `s2b-presorted-pop100`과 `s1b-presorted-big`의 무부하 측정이 부하와 중첩됐다. CSV 요청 수/HTTP200만으로 유효성을 판단할 수 없다. worker의 기록 `results/discarded/2026-09-20-contaminated-s2b-s1b.md`는 원자료를 보존하고 무효 사유를 남긴다. s2b-pop10도 최종 clean 비교에서는 새 라벨로 다시 채취하도록 했다.
- 메인은 자신이 시작한 driver/자식/샘플러 PID와 종료증거를 관리하고, 실제 `/api/places` 요청 누계가 증가하지 않는 것을 확인한 뒤 다음 측정을 시작하도록 했다. town lookup만으로는 GLOBAL/DB_DIRECT 트래픽 부재를 알 수 없다. 무관한 프로세스/컨테이너 종료는 금지다.
- raw28 자원 수치를 메인이 직접 읽었다. 앱/DB cgroup memory.current와 Docker stats 수치는 다르며 동일 측정값으로 취급하지 않는다. VM MemAvailable 약1.10GiB도 MemFree와 함께 기록한다. swap89.5MiB와 Swapouts 누계/차분은 macOS 호스트 지표이며 VM swap과 구분한다. 이 기준점은 후속 시각 관측이고 과거 skew 시작 시점으로 소급하지 않는다.
- raw29 쌍별 비교에서 캐시 두 방식의 DB CPU 차이는 판정불가임을 확인했다. judgePairs에서 null을 포함한 배열 길이를 반복수로 세는 추가 문제는 유효 유한값 수로 바꾸고 fixture를 추가하도록 요청했다. 이번 skew CPU 값은 모두 있어 이미 나온 수치는 바뀌지 않는다.

### 20:54 UTC 24회 부하 완료, guard 보강 검토

- 메인이 실제 report JSON을 합산했다. `s3-*` 정본12회는 attempts/responses/HTTP200 모두43,200, 실패 사용자0이다. `s4-*` 정본12회는 모두42,899, 실패 사용자0, 회당 요청 범위3,563~3,585다. warmup·s3b 추가/중첩 라운드는 이 합계에서 제외했다. worker의 범위3,563~3,577 설명은 부정확하다.
- raw31의 spread 쌍별 비교도 앱 CPU와 p50은 모든 쌍에서 판정불가다. DB CPU는 DB_DIRECT 대비 세 방식이 낮은 방향이고, presorted와 request-sort 사이 차이는 반복 범위 안이다. 쌍별 기술 비교일 뿐 통계적 유의성 검정이나 운영 SLO가 아니다.
- noload 편집 중 token guard가 단독 `x`로 바뀌었고 `set -uo pipefail`에는 `-e`가 없어 실패를 무시한 문제를 메인이 발견했다. 또 idle_guard는 미관측을 awk의 0으로 만들어 통과시켰다. worker가 복구했고 메인은 최종 파일과 raw30의 빈 토큰·미관측·정상·누계 증가 fixture, 실제 count16,100의 20:50:27~30 UTC 불변 smoke를 읽었다. skip flag는 제거됐다.
- helper 유효 n 계산 및 null fixture(총39칸)는 worker가 보고했고 메인은 코드 수정과 raw31의 유효 n 표기를 확인했다. 최종 도구 검사 로그의 집계는 종료 전 다시 대조한다.
- s2c 두 town 후보의 POPULAR10/100 및 s1c warm2+정본3 자료가 존재한다. presorted 100회에서 loads1/miss1/hit99, POPULAR 배열 built1/uses100, 나머지축 built1/uses0이다. request-sort는 POPULAR built100/uses100이다. 이것은 재사용 구조의 증거이며 총CPU/할당 비용 회수나 100배 성능 개선의 증거가 아니다.
- GLOBAL/DB_DIRECT의 인기순 전용10/100과 warm2+정본3은 아직 파일을 확인하지 못해 남은 필수 칸으로 재지시했다. 이후 버전 교체·JFR/heap·계측 대조·최종보고가 남아 있다. 앱/이미지 변경은 계속 금지하며 최종 기본값 및 V48 주석 정정은 측정 종료 후 Opus5에게 맡긴다.

### 21:18 UTC 실제 전체 배치와 추가 대조 검토

- 네 방식 모두 clean 인기순10/100 및 warm2+정본3 자료가 생겼다. 메인은 DB_DIRECT의 실제 CSV warm2/measure3 HTTP200, town/global prepare count와 sum 차분0을 읽었다. no-op 준비가 DB 요청 비용0이라는 뜻은 아니다.
- raw33에서 앱의 실제 인기점수 정기 배치가 06:09 KST에 성공했다(6320행,4548ms). 동네 version66행/합66이 됐고 이후67leaf 요청에서 loads66/hit1, 다시 같은67요청에서는 loads0이었다. 빈 leaf9001은 장소가 없어 전체배치 대상에서 제외되며 코드 bumpAllTownsWithPlaces와 일치한다.
- raw33의 준비 설명 leaf10과 누계67은 모순되어 실제 warm 요청 범위/형상 증거를 요청했다. 초기 readiness probe 때문에67을 채웠다는 가설은 코드와 맞지 않아 철회했다. raw의 version-only 부분변경은 source+version 원자 교체 실험이 아니므로 실제 원본 변경을 포함한 별도 재현 TX를 요구했다. 두 순차 창1.64/1.33초의 차이0.31초를 순수 교체비용으로 귀속할 수 없다.
- raw34 JFR·힙 폴러 off 대조3회에서 앱/DB CPU와p50은 반복 폭으로 구별되지 않았다. Micrometer는 계속 켜져 있다. s5는 실제 배치 이후 데이터/버전이고 s3는 이전 상태이므로 같은 데이터 상태의 순수 계측 A/B도 아니다. 이 한계와 fresh JVM/시간 차이를 최종 보고에 기록하도록 전달했다. 차이 미검출은 계측 비용 없음/비열등성 증거가 아니다.
- 초안은 GLOBAL을 최종 유지 후보로 삼거나 TTL을 미결정으로 부르던 오류를 정정했다. GLOBAL은 새로운 동네 계약에 부적합한 비용 기준선이며 최종 선택은 나머지 세 후보에서 한다. 현재 TOWN_PRESORTED 기본값은 측정 승자가 아니다. JFR 창별 분석·실제 부분변경 및 최종 기록은 계속 작업 중이다.

### 21:21 UTC JFR 분석 초안 반려 및 실제 범위 정정

- raw35는 네 arm의 정본r2라고 적었지만 실제로 warmup20초를 잘랐다. 메인은 presorted clock의 warmup20:03:02~22와 측정창20:03:22~59를 직접 대조했다. 파서도 소수초를 버리고 GC duration을pause로 쓰며 Before GC/After GC를 구분하지 않았다. 이 자료는 정본 비교에서 제외하고 보존한다.
- 실제 JFR JSON은 offset 포함 startTime, sumOfPauses, when, heapUsed를 제공한다. 메인은 DefNew 이벤트를 읽어 보고서의 G1 가정도 반려했다. GCConfiguration 이벤트는 해당 파일에 없었다. worker는 JSON 기반 파서/fixture 검증, 네 arm×3정본 창 재분석 및 allocation weight의 표본 추정을 수행한다. 새 원자료에서 실효GC를 확인해야 한다.
- 21:19 worker의 잔여실행 전부 완료 status는 미완료로 판정했다. worker는21:20 inbox8건을 전체 처리하고 JFR/부분source+version/해석수정의 재실행을 확인했다. worker_done은 아직 없으며 소유권은 유지한다. 자동compact 진행 중이라는 이유로 중복 worker를 만들지 않는다.
- raw33 warm67의 경위는 worker의 loop가 한 줄짜리 LEAF_IDS에 head를 걸어 모든67개를 순회했기 때문이라는 보고를 받았다. 실제 loop 증거를 최종 raw 보완에서 확인한다. leaf10 설명은 틀렸다.

### 21:33 UTC JFR 정본 검토, 최종 기본값 선택, 실제 부분변경 확인

- raw36은 clock 측정창 줄에서 추출한4방식×3정본창이며 메인이 새 JSON파서를 읽었다. sumOfPauses, After GC, allocation weight가 분리됐다. old raw35는 보존된다. GC 뒤 점유를 cache retained/live heap으로 읽지 않는다. 표본 추정 할당량은 DB_DIRECT가 약3195MiB/창, town 두 후보가약2880MiB/창으로 관측됐지만 둘 사이는 구별되지 않았다. 원인 귀속과 운영 일반화는 하지 않는다.
- 네 JFR 모두 DefNew/SerialOld가 확인됐다. G1 가정은 틀렸다. raw36의 heap절반이라 ergonomics라는 추론은 Xms/Xmx768m 명시와 맞지 않아 정정 요청했다. DB_DIRECT r2 실제 측정창에 CodeCache 원인의 full GC가 있으므로 전부 기동/워밍업이라는 문장도 반려했다. 선택 원인/실효flags 및 작업부하와의 인과는 확인하지 않았다. JFR range합 판정은 기존CPU max-range와 다른 탐색적 보조 기준임을 명시하도록 했다.
- 메인은21:29 UTC TOWN_REQUEST_SORT를 최종 기본값으로 선택했다. 계약 충족을 먼저 확인하고, 두 town 방식의 CPU/지연/할당 이득 및 준비 비용 회수를 가리지 못한 상황에서 객체만 적재/요청 합집합1회정렬의 단순성을 선택한다. PRESORTED의재사용100은성능100배가아니며미사용4축준비도발생했다. GLOBAL은새계약부적합기준선, DB_DIRECT는이창에서앱CPU절감없이DBCPU/할당추정이더커미채택. 다른규모의영구우열이나비열등성은주장하지않는다. 코드변경은측정종료뒤Opus5가수행한다.
- s6 partial.log: place10104/town302 score8.577223→99.999999과version2→3을같은TX로커밋, 목록5위→1위, loads차분1/prepare8.697ms, 추가3조회loads0. 복구TX에서score원값/버전4, 목록원래순서, loads추가1. 메인이script·log·restoreSQL을읽었다.
- 이TX는legacyglobalmetadata를올리지않은명시적town재적재재현이다. 실제앱쓰기전체비용/legacyglobalpoll까지의성능으로일반화하지않는다. 동네2·3·4는변경뒤최초적재라변경전warm항목유지의대조는아니다. armHELP줄오독은실제sample행으로보완하도록요청했다. source복구확인후불필요한재실험은요구하지않았다.

### 21:51 UTC 측정 완료 수용 및 최종화 배치

- 측정 Task task_4cc714f265f4 / ctx_cb2d042b059b가21:50:17 UTC succeeded로 종료했다. 메인은 실제raw와도구코드,전체배치/부분TX복구,JFR12창을검토했고잔여문구를최종보고에서직접정정했다. 종료terminal은즉시release,transcript archive captured로확인후delivery를ack했다.
- raw38은 같은JVM/데이터에서 ON→OFF를3회반복한6창이다. 토글은200msheap폴러만이며 sampler/cpustat/Micrometer는양쪽켜짐,JFR은양쪽없음. 앱CPU관측3.081대2.833ms/request는CPU표본창과client창의차이·순서/JIT효과를남기는근사값이다. 순수인과비용/다른구성의상수비용/스크레이프당비용으로환산하지않는다. 추가실험은종료했다.
- 메인이dockerinspect에서bench-app exited,FinishedAt21:47:28.802359632Z,OOMKilled=false를직접확인했다. raw39에source복구8.577223/version4,metadata0:2,fixture9001보존,DB/lb유지,JFR6개보존,수집기종료및앱/Gradle/이미지슬롯반환기록이있다. 무효자료보존과초기s2손실예외를구분한다.
- 최종화Task task_88562ebef587 / Dispatch ctx_0cc8512c67d4 / terminal term_693c1c21-4196-414d-9ef1-c9ae079551fe를같은구현worktree의새Claude세션에배치했다. receipt requested/effective 모두claude-opus-5, input accepted/turn started/ready를확인했다.
- 최종화범위는TOWN_REQUEST_SORT기본값,세앱파일의주석정정,필요테스트/전체JDK21build및형상기록이다. 측정이미지는재빌드하지않고oldJAR를clean전에ignored로그경로로보존한다. V48주석변경의checksum경계를검증하되측정DBhistory는repair하지않아이전이미지의재현상태를보존한다. 앱/테스트는Opus5만편집한다.
- 메인은원본성능보고서의결정표모순,부하창과자원관측시각,GC r2표기,기존전역캐시경계,할당산포배수및인과표현을정정했다. 수치원본/보고JSON/JFR은변경하지않았다. 최종기본값을적용한새build검증은진행중이다.

### 22:06 UTC 최종 검증 수용·종료

- 최종화 Task task_88562ebef587 / ctx_0cc8512c67d4가22:05:26 succeeded. 메인은 최종 세 번째 JDK21 clean build 로그와 XML86개/752 tests/fail0/error0/skip0, exit0를 확인했다. 기본값 변경에 따른 비교 테스트의 의존성을 두 명시적 캐시 모양 비교로 해소했고 기대값을 약화하지 않았다.
- 기본값 TOWN_REQUEST_SORT 및 주석 후속2건 반영을 직접 읽었다. 최종 JAR3189bb31a484ca4ad465140d31133d1e571e84cfa4023819515fb16b478a7ffe, 내부V48/소스일치 및 oldJAR8aa914d2b85f8592a2189bba77b178d04c0c7a2156c8d54e8fd10dd0b4240e23 보존을 확인했다. checksum -1902597483→-1840823740은 메인도 독립 계산해 확인했다. 측정DB history repair는 하지 않았다.
- 도구최신selftest26/39/45와16bash/4node문법검사 로그를 읽었다. 첫 실패build와중간성공build전체로그는같은경로재실행으로덮였고첫실패발췌만남았다. 그한계를검증문서/완료기록에명시했다.
- 마지막terminal worker-release는 closed_agent_terminal/archive captured. 수용후release한뒤delivery ack. Task10개completed,reclaimable0, inbox새메시지없음. 원본developHEAD/앱무변경과bench-app exited/OOMfalse를재확인했다.
- 메인은종료후finalization문서의repair필수단정을적용이력·DDL확인후전환방식결정으로정정했다. 앱코드나테스트는메인이편집하지않았다. 최종설계상태/TODO/완료기록/성능보고연결을갱신하고정본문서를worktree에도전달했다. 커밋/푸시/PR/배포없음.
