> **이전 설계·작업 이력 — 현재 구현 요구사항이나 완료 판정으로 사용하지 마세요.** 현재 기준: [설계 근거와 진행 상황](../../../blog/2026-09-21-place-list-design-evidence.md).

# 검증 기록: 측정 종료 뒤 최종 기본값과 주석 정정

작성일: 2026-09-21. 대상 형상: `develop@64a97e08274303199b33244ba579adde22e4437d` 위의 작업 트리
(`/Users/mkyu/orca/workspaces/solply-server/feat-place-list-town-cache`).
지시 정본: [최종 정리 지시](../handoff/2026-09-21-finalization-review.md),
요구사항 정본: [설계](../design/2026-09-21-place-list-town-cache.md).

**이 문서는 코드 형상 확정의 기록이다.** 새 성능 수치는 없다. 여기서 부하를 걸지 않았고,
기존 캠페인 원자료를 다시 쓰지도 않았다. 기본값 선택은 성능 우열 판정이 아니라 준비 비용을 줄인
단순성 선택이며, 그 근거는 캠페인 쪽 기록에 있다.

## 1. 바꾼 것

### 1.1 기본 listSource를 요청 시점 정렬로

`PlaceListProperties.listSource` 기본값을 `TOWN_PRESORTED` → `TOWN_REQUEST_SORT`로 바꿨다.

근거는 두 가지다. 비교 캠페인이 두 동네 구조의 CPU·지연·할당 차이를 구별해 내지 못했고,
인기 한 축만 조회하는 요청에도 사전정렬은 읽히지 않는 네 축을 준비했다. 우열을 가릴 근거가 없을 때
준비를 덜 하는 쪽을 고른 것이다. 사전정렬이 느리다는 뜻도, 요청 시 정렬이 우월하다는 증명도 아니다.

**비교 경로 넷은 그대로 남는다.** `GLOBAL_SNAPSHOT`은 새 계약의 채택 후보가 아니라 비용 기준선이고,
`DB_DIRECT`는 이 창에서 앱 CPU 절감을 확인하지 못한 채 DB CPU와 표본 추정 할당이 컸다. 거리순과
북마크 검색의 동작은 손대지 않았다 — 둘은 이 값과 무관하게 전역 경로로 간다.

**설정 override는 유지된다.** 이 기본값은 `application.yml`이 ignored라 리포지토리에 남는 유일한
선언일 뿐이고, `solply.place-list.list-source`로 네 값 중 무엇이든 기동 시 고를 수 있다.
운영 예산 200000 같은 수치는 이 Task에서 확정값으로 승격하지 않았다.

### 1.2 주석 네 곳 정정

| 자리 | 틀린 서술 | 현재 코드의 사실 |
|---|---|---|
| `PlaceListProperties.ListSource.DB_DIRECT` | 요청 경로에 연결하지 않았고 고르면 거부된다 | 연결돼 있다. 정적 5축이 `PlaceListRequestOrchestrator`의 동네 경로로 들어와 `TownDbDirectReader`로 가고, REQUIRES_NEW·읽기 전용·REPEATABLE READ 한 트랜잭션 안에서 번호 관측 → 커서 범위 비교 → 페이지 문장 → 페이지 place_id hydrate를 끝낸다 |
| 같은 자리, 예산 경계 | — | 공통 총예산과 범위 표현 비교를 공유하는 것은 **동네 경로로 들어오는 세 후보**(`TOWN_PRESORTED`·`TOWN_REQUEST_SORT`·`DB_DIRECT`)다. `GLOBAL_SNAPSHOT`은 기존 전역 경로를 그대로 써서 커서가 v7이고 대기 예산도 기존 전역 회차 catch-up 예산이다 — 그 경계를 주석에 명시했다 |
| V48 마지막 주석 | 마이그레이션 순간 모든 스크롤이 decode에서 거부된다 | v7·v8 decode를 둘 다 유지한다. 정적 5축이 새 동네 경로로 가면서 범위 표현이 G(전역 회차)에서 T(동네별 번호)로 바뀌어 옛 정적 커서가 **범위 비교**에서 만료된다. 거리순·북마크 검색은 v7로 계속 이어진다 |
| V48 bump 단정 | 번호가 오르면 반드시 무언가 바뀌었다 | 번호는 새 탐색 세대이자 무효화 경계다. 값이 같아도 성공한 정기 전체 배치가 처리 대상 동네를 통째로 올린다 |
| V48 상단 괄호 | 배치가 올려 그때 재적재된다 | 배치가 번호를 올리는 것과 표시값이 갈리는 시점은 다르다. 배치 성공 **뒤 그 동네에 요청이 와서 적재가 성공했을 때** 반영된다 |
| `TownVersionService` 클래스 주석 | `(동네, 태그 비트마스크)` 지문이 탐색의 대상·필터·**순서**를 바꾸는 변경을 전부 잡는다 | 지문은 대상과 필터만 잡는다. 정렬 키값이 갈리는 쓰기는 지문이 그대로여서 `markSortKeysChanged`를 따로 부르고(북마크 델타), 정기 전체 배치는 `markAllTownsChanged`로 처리 대상 전 동네를 올린다 |

정정은 주석과 SQL 주석뿐이고, DDL·실행 경로·계약은 바뀌지 않았다. 아래 두 줄은 메인 검토
(`msg_92143a673d68`)에서 되짚은 것이다 — 첫 정정본이 예산 공통을 네 구조로 넓게 쓰고
V48 상단 괄호의 시점을 뭉갠 것을 다시 좁혔다.

### 1.3 테스트 하나 수정 — `TownDbDirectReaderIT`

기본값을 바꾸자 `TownDbDirectReaderIT`의 등가 게이트 네 칸이
`IllegalStateException: 원소만 적재한 동네에는 사전 정렬 배열이 없다`로 깨졌다. **원인은 게이트의
대조군이 런타임 기본값에 매여 있던 것이다** — `TownSourceLoader`는 `listSource`를 보고 적재 모양을
정하는데(요청 시점 정렬이면 배열을 만들지 않는다), 대조군은 배열을 쓰는
`TownListReader.page`를 고정으로 불렀다.

의도를 유지하면서 그 매임을 끊었다. 대조군을 만들 때 **적재 모양을 고정한 로더를 각각 세워** 사전
정렬 경로와 요청 시점 정렬 경로를 함께 돌리고, 두 결과가 같은지 먼저 물고 그 하나를 DB 직접 조회의
대조군으로 쓴다. 기대값을 느슨하게 한 것이 아니라 **덮는 범위가 늘었다** — 이제 이 게이트가
"두 캐시 모양이 서로 같다"까지 문다.

기본값 변경 때문에 손댄 테스트는 이 한 파일뿐이다. 나머지 IT는 `solply.place-list.list-source`를
`@DynamicPropertySource`로 직접 지정하거나 이 값과 무관해서 영향이 없었다.

## 2. 실행 환경과 명령

| 항목 | 값 |
|---|---|
| 명령 | `./gradlew clean build -Dorg.gradle.java.home=$JAVA_HOME` (작업 트리 루트) |
| `JAVA_HOME` | `/Users/mkyu/Library/Java/JavaVirtualMachines/temurin-21.0.8/Contents/Home` |
| 컴파일·테스트 JDK | Eclipse Temurin 21.0.8+9-LTS |
| Gradle | 8.14.2 (wrapper) |
| MySQL | Testcontainers `mysql:8.0` + Flyway |
| Docker | 서버 28.0.1 |
| 전체 로그 | `docs/verification/logs/2026-09-21-final-build.log` (ignored) |

측정에 쓰던 컨테이너(`solply-bench-mysql`, `solply-bench-lb`)는 이 Task 동안 그대로 떠 있었다.
종료하지 않았고, Testcontainers는 자기 컨테이너를 따로 띄웠다. 운영/dev 호출, 부하 실행,
commit·push·PR·deploy는 하지 않았다.

## 3. 결과

`./gradlew clean build` — **BUILD SUCCESSFUL**, 프로세스 exit code **0**.

XML 집계(`build/test-results/test/TEST-*.xml` 86개):

| 지표 | 값 |
|---|---|
| tests | 752 |
| failures | 0 |
| errors | 0 |
| skipped | 0 |

`git diff --check` — exit code **0** (공백 오류·충돌 표식 없음).

**이 752는 이번 최종 형상의 새 측정이다.** 이전 단계의 752를 재사용하지 않았다.
이 Task에서 clean build를 세 번 돌렸다.

| 회차 | 형상 | 결과 |
|---|---|---|
| 1 | 기본값 변경 + 주석 1차 정정, 테스트 미수정 | `752 tests completed, 4 failed`, exit 1 — 발췌는 `logs/2026-09-21-final-build-attempt1-failed.md` |
| 2 | 테스트 수정 반영 | BUILD SUCCESSFUL, 752 / 0 / 0 / 0 |
| 3 | 메인 검토의 주석 2건까지 반영한 **최종 형상** | BUILD SUCCESSFUL, 752 / 0 / 0 / 0 — 위 표와 로그가 이 회차의 것이다 |

3회차는 2회차 대비 주석과 SQL 주석만 다르지만, "이 형상에서 재지 않은 수치를 인용한다"를 피하려고
전체를 다시 돌렸다. `2026-09-21-final-build.log`는 3회차 로그다 — 1·2회차는 같은 경로로
리다이렉트해 덮였다.

## 4. 형상 구분 — 측정 이미지와 최종 소스

| 형상 | 식별 | 비고 |
|---|---|---|
| 측정에 쓴 JAR | SHA256 `8aa914d2b85f8592a2189bba77b178d04c0c7a2156c8d54e8fd10dd0b4240e23` | clean이 지우기 전에 `docs/verification/logs/measured-8aa914d2b85f.jar`로 보존(ignored). 측정 이미지 자체는 재빌드·덮어쓰기하지 않았다 |
| 최종 소스의 JAR | SHA256 `3189bb31a484ca4ad465140d31133d1e571e84cfa4023819515fb16b478a7ffe` | `build/libs/solply-server-0.0.1-SNAPSHOT.jar`. plain JAR은 `3e3b1271f3ced655e2c653b1db93f9f2589c33ce824f6210fd127b06ae4f880f` |
| 이 Task의 소스 변경 | `docs/verification/logs/2026-09-21-finalization.patch` | 변경 전 사본은 `docs/verification/logs/pre-finalization/` (ignored) |

**두 JAR은 다른 형상이다.** 측정 수치는 앞의 것으로 채취했고, 뒤의 것은 이 Task의 기본값·주석
변경이 들어간 뒤의 것이다. 뒤의 JAR로는 측정하지 않았다. 캠페인 수치를 이 형상의 실측으로
바꿔 읽지 않는다.

`pre-finalization/`의 네 사본 중 세 개(`PlaceListProperties.java`, `TownVersionService.java`,
`V48__place_list_town_versions.sql`)는 변경 전에 그대로 복사한 것이고,
`TownDbDirectReaderIT.java`는 **변경 뒤에 역방향으로 재구성한 것**이다 — 사본을 먼저 뜨지 않았다.
재구성한 결과와 현재 파일의 diff가 이 Task가 실제로 한 치환과 일치하는지 확인해 patch에 실었다.

## 5. Flyway checksum — 값이 바뀌었다

V48은 SQL 주석만 고쳤지만 **Flyway checksum은 달라진다.** Flyway는 파일 줄 전체로 CRC32를 계산해
주석을 구분하지 않는다. 같은 계산기(`ChecksumCalculator`)로 정정 전후를 재 봤다.

| 형상 | checksum |
|---|---|
| 정정 전 | `-1902597483` |
| 정정 후(최종) | `-1840823740` |

메인 검토로 상단 괄호를 한 번 더 고쳤으므로 이 값은 **마지막 주석 수정 뒤에** 다시 계산한 것이다.
중간 판(상단 괄호 정정 전)의 값 `356419043`은 최종 형상이 아니다.

flyway-core 10.10.0(런타임 관리 버전)과 10.14.0(Gradle 플러그인) 양쪽에서 같은 값이 나왔다.
값이 같을 것이라고 가정하지 않고 실제로 계산한 결과다.

**그래서 측정 DB에는 손대지 않았다.** 그 DB에는 측정 이미지의 V48이 옛 checksum으로 이미 적용돼
있어, 새 소스로 validate하면 어긋난다. 재현 상태를 보존하려고 이 Task에서 migration history
repair나 DB 변경을 하지 않았다. 이미 V48을 적용한 환경에서는 적용 이력과 DDL 일치를 검토한 뒤 원본 migration 유지 또는
통제된 checksum 정리 등 전환 방식을 별도로 결정해야 한다. 자동 repair를 지시하는 것이 아니다.
이 문장은 worker 종료 뒤 메인이 검토하여 정정했다.

새 clean build의 Testcontainers 검증은 빈 DB에 처음부터 적용한 것이라 이 문제와 무관하다.
둘을 같은 사실로 읽지 않는다.

## 6. 도구 검증 로그 보완

측정 worker가 남긴 `results/tool-verification.log`는 03:01의 것이고, 그 뒤 `jfr-window.py`(06:41),
`analyze-round.mjs`(06:15)가 수정됐다. 최신 판의 selftest 출력과 종료코드가 파일로 남아 있지 않아
읽기 전용 검증만 다시 돌려 `docs/verification/logs/2026-09-21-tools-final.log`에 명령·실제 출력·
exit를 남겼다. 원본 캠페인 파일은 편집하지 않았다.

| 검증 | 결과 |
|---|---|
| `bash -n bench/*.sh` (16개) | 전부 exit 0 |
| `node --check` (pick-town.js, analyze-round.mjs, equivalence.mjs, gen-tokens.mjs) | 전부 exit 0 |
| `python3 bench/jfr-window.py --selftest` | 26 통과 / 0 실패, exit 0 |
| `node bench/analyze-round.mjs selftest` | 39/39 통과, exit 0 |
| `node bench/equivalence.mjs selftest` | 45/45 통과, exit 0 |

문법과 fixture 계산만 봤다. HTTP·DB·부하는 돌리지 않았다.

## 7. 이 문서가 말하지 않는 것

- **기본값 선택의 성능 근거를 여기서 대지 않는다.** 두 동네 구조를 가를 수치가 없었다는 것이
  선택의 이유이고, 그 실측은 캠페인 쪽 기록이다.
- **최종 JAR로는 측정하지 않았다.** 이 형상의 CPU·지연·할당 수치는 없다.
- **캐시 예산·선적재·동시 적재 제한은 이 Task에서 정하지 않았다.** 운영 예산 200000은 여전히
  근거 없는 후보값이다.
- **이미 V48을 적용한 환경의 checksum은 정리되지 않았다.** 측정 DB를 보존하려고 일부러 남겼다.
