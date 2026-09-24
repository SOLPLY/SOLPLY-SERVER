> **이전 설계·작업 이력 — 현재 구현 요구사항이나 완료 판정으로 사용하지 마세요.** 현재 기준: [설계 근거와 진행 상황](../../../blog/2026-09-21-place-list-design-evidence.md).

# 장소 목록 동네 캐시 — 메인 최종 검토 기록

상태: 완료 — 2026-09-21 07:06 KST. 구현·비교·기본값 적용·최종 검증·작업자 종료를 메인이 확인했다.

설계 정본: [요구사항·트레이드오프](../design/2026-09-21-place-list-town-cache.md).
비교 결과: [성능 보고서](../../../perf/2026-09-21-place-list-town-cache.md).
진행·정정 이력: [Run 기록](2026-09-21-place-list-run.md).

## 작업 위치와 소유권

- 원본: `/Users/mkyu/Desktop/SOPT/solply-server`, develop 기준 `64a97e08274303199b33244ba579adde22e4437d`. 앱 코드 수정 없이 설계/인계 문서와 ignored 로컬 캠페인·TODO를 보존한다.
- 구현: `/Users/mkyu/orca/workspaces/solply-server/feat-place-list-town-cache`, `uykm/feat-place-list-town-cache`. 변경은 미커밋 상태다.
- 새 Run `run_ff6af38c2ab0`. 메인은 조정·설계·코드/계약/원자료 검토를 담당하고 구현·테스트·측정 도구 및 실행은 requested/effective `claude-opus-5`가 확인된 Orca 세션이 수행했다. 이전 사전 조사 Run은 재사용하지 않았다.
- 정적5축만 비교했다. 거리 최적화·부하, 운영/dev 부하, 이슈 선행 승인, 커밋/푸시/PR/배포는 범위에 포함하지 않았다.

## 요구사항과 근거

구체 메서드/테스트명은 구현 worktree의 [계약 검증](/Users/mkyu/orca/workspaces/solply-server/feat-place-list-town-cache/docs/verification/2026-09-21-town-cache-contract.md)과 [통합 검증](/Users/mkyu/orca/workspaces/solply-server/feat-place-list-town-cache/docs/verification/2026-09-21-comparison-integration.md)에 있다.

| 요구사항 | 구현 경계 | 검증 근거 |
|---|---|---|
| 영향 동네만 자동 bump, 이동은 양쪽 | AdminPlaceService 잠금+전후 지문, TownVersionService/Repository, 통계 쓰기와 같은 TX | TownVersionBumpIT 실제 admin 생성/삭제/이동/태그/표시 분류, 별도 writer·latch 경합 및 rollback |
| 여러 동네 metadata 및 적재 source 일관성 | metadata IN 한 문장, TownSourceLoader RR REQUIRES_NEW에서 version+source, 관측 버전 일치 확인 | TownCacheConcurrencyIT 별도 연결 commit과 읽기 순서 고정, TownCacheListFlowIT 이동 중 중복 없음 |
| 구커서 다음 요청 만료, leaf 집합 변화도 만료 | town scope v8에 leaf별 version, global scope v7 유지 | PlaceListCursorTest 바이트 호환, TownCacheListFlowIT 버전/leaf 변화·캐시 제거 후 같은 커서 |
| 이미 확보한 일관된 참조로 응답 | 불변 TownPlaces와 요청 보유 참조 | TownCacheConcurrencyIT 확보 후 commit |
| 표시값만 수정시 bump 없음, 다음 성공 배치+재적재 후 갱신 | 표시값 frozen payload, 배치 성공 TX에서 처리 대상 전체 bump | 실제 admin 표시 변경, 수치 불변 전체 배치, 표시 반영, 배치 source+bump 성공/rollback IT |
| 필요한 동네만 load, 총 요청 예산1초 | orchestrator 진입 future가 validation/metadata/queue/connection/load/resume을 감쌈 | TownCacheBudgetIT metadata/queue/Hikari 고갈, timeout·cancel 격리 IT |
| 공유 적재와 실패/늦은 완료 처리 | TownLoadRegistry putIfAbsent 후 실행, 요청별 future copy, townId별 단조 publish | 공유5요청, inline/rejected executor, 실패 후 retry, old load 덮어쓰기 방지 |
| 용량 제한과 구버전 상주 보관 없음 | Caffeine maximumWeight, weight=max(1,placeCount), townId당 최신1항목 | TownPlacesCacheTest 용량 eviction/빈 동네/동일버전 재적재/단조성 |
| 사전정렬 vs 요청정렬의 실제 차이 | presort5배열은 객체 참조 공유, objects-only 적재는 배열 없음, 요청 합집합 정렬1회 | TownPlaces/TownListReader 테스트 및 실제 built/uses 차분 |
| DB_DIRECT도 같은 정적 결과/동네 계약 | 단일 RR에서 metadata→DB page→hydrate, 공통 요청 예산 | TownDbDirectReaderIT9개, DbDirectHttpArmIT7개, 실제HTTP 등가21칸 |

테스트 경계도 구분한다. 인스턴스 이동은 별도 두 서버 부하가 아니라 독립/빈 로컬 캐시로 같은 버전을 재적재하는 의미를 검증했다. 동시 이동은 실제 admin 한쪽과 별도 SQL writer를 latch로 경합시켰다. 예산 테스트는 지연을 짧게 설정해 장기 block보다 먼저 끊김을 확인하며, 엄격한 실제시간1초 SLO 보장은 아니다. DB_DIRECT 자체의 timeout 재현과 실제 SQL 실행 중 stall은 별도 검증하지 않았다.

## 검증 형상

측정 전 최종 JDK21 clean build: 86 suites / 752 tests / failure0 / error0 / skip0 / exit0. 메인이 전체 로그·XML 집계를 읽었다. 이전707개 기록과 뒤섞지 않는다.

측정 이미지: `solply-bench-app:towncache-8aa914d2b85f`, ID `sha256:935fed8570ba85ca9b810ea62c695a9c32ec15405626f4cd871f11b940c4daea`.
JAR SHA256: `8aa914d2b85f8592a2189bba77b178d04c0c7a2156c8d54e8fd10dd0b4240e23`.
HEAD만으로 미커밋 형상을 식별하지 않는다. dirty diff/new src 해시는 campaign `results/raw/22-build-identity.txt`에 있다. 최종 기본값 변경 후 새 clean build도 86 suites / 752 tests / failure0 / error0 / skip0 / exit0다. 메인이 새 로그와 XML을 직접 집계했다. 기본값에 의존했던 DB 비교 테스트는 사전정렬·요청정렬을 각각 명시적으로 적재해 두 결과까지 비교하도록 수정했다. 최종 형상·JAR·Flyway checksum은 [최종화 검증](/Users/mkyu/orca/workspaces/solply-server/feat-place-list-town-cache/docs/verification/2026-09-21-finalization.md)에 별도로 기록한다. 도구 selftest도 JFR26/analyzer39/equivalence45 전부 통과한 최신 로그를 확인했다.

## 최종 선택

메인은 TOWN_REQUEST_SORT를 기본값으로 선택했다(21:29 UTC). 새 동네 계약 충족이 선행 조건이며, 두 town 후보 사이 CPU·지연·할당 이득은 이번 조건에서 구별되지 않았다. 사전정렬은 배열 재사용을 확인했지만 준비 비용 회수를 입증하지 못했고 미사용 축까지 준비한다. 요청 정렬은 객체만 적재하고 요청 합집합을 한 번 정렬하는 단순성을 선택한다. 성능 우월이나 비열등성 증명은 아니다.

GLOBAL은 새 동네 만료 계약에 부적합하므로 비용 기준선으로만 남긴다. DB_DIRECT는 이 창에서 앱 CPU 절감을 확인하지 못한 반면 DB CPU와 JFR 표본 추정 할당량이 더 컸다. 다른 데이터 규모·분포에서의 영구적 기각은 아니다. GC 뒤 점유 차이는 cache retained 크기로 귀속하지 않는다. 최종 코드 기본값은 TOWN_REQUEST_SORT로 적용했고 전체 빌드를 검증했다.

## 정책과 한계

- 새 동네 계약에 별도 revision, 상태값, 과거 payload 보관, Redis, TTL/softTTL을 넣지 않았다. 기존 전역 metadata는 거리 호환 때문에 남아 있는 별도 경로다.
- `maxPlaces=200000`, loader4/unbounded queue, 첫페이지 재관측2는 현재 구현 기본값이다. 특히 용량 상한은 운영에서 검증된 메모리 예산이 아니다. 진행중 참조/적재 후보/GC 미회수분 및 legacy 전역 snapshot까지 포함한 힙 상한도 아니다.
- 기본 선적재는 추가하지 않았다. 추가 동시 적재 제한/queue bound 및 운영 용량 수치는 이번 작은 데이터·부하에서 확정하지 못했다. timeout은 공용 적재나 DB 작업의 즉각 중단을 보장하지 않는다.
- 이름·썸네일은 성공한 정기 전체 배치 뒤 해당 동네가 성공적으로 재적재될 때 반영된다. 배치 실패/재적재 실패가 있으면 더 늦어질 수 있으며 고정 시간 상한을 약속하지 않는다.
- v7 decode를 제거하지 않는다. 새 town 정적 경로의 scope가 기존 global 정적 커서와 달라 해당 전환시 탐색을 다시 시작해야 한다. V48 테이블 생성 자체가 모든 커서를 decode에서 거부하는 것은 아니다. 기존 거리 경로의 v7 호환을 유지한다.
- cache 두 방식에서도 legacy 전체 snapshot은 남는다. 부분 준비 비용을 앱 전체 메모리 절감으로 부르지 않는다.
- 벤치 hook은 bench profile+enabled property+조건부 security로 기본 비활성이다. 바이트코드는 JAR에 포함된다. 실제 운영 환경변수는 확인하지 않았다.

## 비교와 자원 종료 기록

- 정적5축·태그·여러 동네·빈 동네의 고정 데이터 등가 게이트: 세 후보 모두 GLOBAL 대비21/21. 이 중16조건은2페이지까지 비교했다. GLOBAL의 새 만료 계약 적합성을 뜻하지 않는다.
- 정본 정상부하: 편중12창43200요청, 분산12창42899요청, 모두200. 네 방식×각3회이며 다른 데이터 규모나 운영 SLO의 증거는 아니다.
- 준비/재사용, 실제 성공 전체 배치 뒤66동네 재적재, 실제 점수+버전 같은TX 변경/복구, JFR12정본창, 계측 ON/OFF 보조 비교까지 원자료를 검토했다. 자세한 창·형상·한계는 성능 보고서가 정본이다.
- JFR 표본 추정 할당과 GC 뒤 점유를 정확한 할당량·live heap·캐시 retained 크기로 해석하지 않는다. SQL digest로 앱 SELECT 비용을 관측하지 못했으므로 해당 값은 미측정이다.
- 잘못된 JFR 창, 겹친 보조부하, 허가되지 않은 불필요 재측정은 제외 이유와 함께 보존했다. 초기 s2 헤더만 있던12파일은 worker가 삭제해 복원하지 못했다는 예외도 기록했다. 최종화 첫 실패 build도 같은 경로 재실행으로 전체 로그/XML이 덮였으며, 실제 관측 발췌만 `2026-09-21-final-build-attempt1-failed.md`에 남아 있다. 이를 전체 로그 보존으로 주장하지 않는다.
- 측정 앱은21:47:28 UTC 종료, OOMKilled=false를 메인이 확인했다. DB/lb와 재현용 볼륨·fixture·기존 이미지·JFR은 보존했다. 다른 컨테이너를 종료하지 않았다. 원자료 종료 정본은 campaign `results/raw/39-final-resources-and-slot-return.txt`다.
- V48은 DDL이 같아도 주석 정정 때문에 Flyway checksum이 달라진다. 측정 DB history는 repair하지 않았다. 이미 V48을 적용한 환경의 이력·DDL을 검토하고 전환 방법을 별도로 결정해야 한다. 새 Testcontainers clean build 성공과 기존 DB validate 호환을 혼동하지 않는다.

## 종료 확인

최종 주석까지 반영한 세 번째 clean build의 XML86개/752 tests/실패·오류·skip0, exit0를 다시 확인했다. 최종 JAR SHA256은 `3189bb31a484ca4ad465140d31133d1e571e84cfa4023819515fb16b478a7ffe`이며 JAR 내부 V48과 현재 SQL 바이트도 일치한다. V48 checksum은 `-1902597483`에서 `-1840823740`으로 달라졌다. 측정 당시 JAR은 별도로 보존했다.

22:05:26 UTC 마지막 worker_done을 검토·수용하고 terminal을 release한 뒤 ack했다. Task10개 모두 completed, reclaimable terminal0개를 확인했다. 이전 dispatch의 resource absent 표기는 재사용·이전 이력이며 실제 마지막 소유 dispatch의 release를 기준으로 정리했다. 원본 tracked 앱 변경 없음과 develop HEAD 보존, 구현 worktree diff --check 통과도 확인했다.

 운영 용량 예산·적재 queue bound·실제 SQL stall·계측 전체 인과 비용은 이번 검증으로 확정하지 않은 한계이며 추가 구현 약속이 아니다.

## Task 추적

- 계약 구현: `task_4b1ea6c3994e` (707개 검증 당시 형상).
- 비교 환경·계획: `task_7647073495aa`, 계획 보정 `task_9047c9e45c1c`.
- 비교 도구: `task_8d6223f64a3e`, 판정 보강 `task_f39a985cf699`.
- DB 직접 reader: `task_8b34ea2ac11b`.
- 로컬 DB/형상 준비: `task_329c665d39e3`.
- HTTP 연결·계측: `task_4d396e4349c6` (752개 검증, 측정 전 형상).
- 실제 네 전략 비교: `task_4cc714f265f4`.
- 기본값·주석·최종 검증: `task_88562ebef587`.

이전 Task의 완료 보고 숫자나 초안 해석과 정정 기록이 다르면 메인의 실제 로그/XML·원자료 검토 및 최종 검증 문서를 따른다.
