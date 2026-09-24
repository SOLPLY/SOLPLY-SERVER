> **이전 설계·작업 이력 — 현재 구현 요구사항이나 완료 판정으로 사용하지 마세요.** 현재 기준: [설계 근거와 진행 상황](../../../blog/2026-09-21-place-list-design-evidence.md).

# 비교 연결 작업 지시 — 메인 검토 경계

정본은 `docs/design/2026-09-21-place-list-town-cache.md`다. 이 문서는 후속 Opus5 구현 작업의 범위다.

## 소유권과 완료 기준

- core 계약 검증과 별도 `TownDbDirectReaderIT`가 끝난 다음 메인이 앱 수정/Gradle 슬롯을 배정한다.
- 구현 worktree의 앱·테스트·빌드 설정 및 `docs/verification/2026-09-21-comparison-integration.md`를 담당한다.
- 원본 checkout의 비교 도구는 다른 담당자 소유다. 필요한 인터페이스 변경은 메인에게 알린다.
- 테스트는 전체 로그와 실제 종료 코드를 남긴다. 마지막 변경 뒤 전체 build 결과 및 XML 집계를 기록한다.
- 커밋·푸시·PR·배포 금지. 운영/dev 호출 및 무관한 컨테이너 변경 금지. 거리순 최적화/벤치 제외.

## 구현할 것

1. `DB_DIRECT`를 실제 정적 5축 HTTP 요청 경로에 연결한다. 기존 검증/사용자 북마크 응답을 재사용한다. reader가 확보한 버전과 표시값으로 응답/커서를 만들고, reader 호출 전부터 동일한 총 요청 대기 예산을 적용한다. 거리·북마크 전용 검색은 기존 경로를 유지한다.
2. 원본 캠페인의 `docs/bench-interface.md` H1/H2를 읽고 실제 계측을 연결한다. 계측 카운터가 요청 수와 적재 수를 혼동하지 않아야 하며, 합류한 대기자마다 적재를 중복 집계하지 않는다. 캐시 winner double-check와 내부 조회가 hit ratio의 분모에 어떻게 들어가는지 명시한다.
3. 준비/배열 계측은 실제 생성·사용을 센다. TOWN_REQUEST_SORT는 여러 동네 합집합 정렬 1회이므로 생성/사용 1회다. presorted는 사용한 동네 배열 단위다. 누계 비율은 통제된 구간의 평균이지 개별 배열 수명별 분포가 아니다. 세워 놓고 사용하지 않은 정렬축도 드러나게 한다.
4. GLOBAL_SNAPSHOT은 기존 v7/global metadata 계약의 현재 기준선이다. 새 town 계약으로 개조하지 않는다. 고정 데이터에서 결과/필터/순서/페이지 동등성을 먼저 검증한다. 기존과 후보 계약 비용 차이는 별도 기록하며 전략 이득이라고 모두 귀속하지 않는다.
5. legacy global snapshot은 모든 arm에서 거리 호환 때문에 상주/기동/폴한다. TOWN/DB arm의 town 준비가 0인 것과 global 준비·폴이 0인 것은 다르다. 실제 global 비용을 모두 기록한다.
6. `/bench/prep/reset`은 DB/source/version을 바꾸지 않는다. GLOBAL은 실제 전량 재구성을 호출 안에서 마치며 준비 소요를 응답한다. TOWN은 해당 항목 무효화만 하고 다음 요청이 적재한다. DB는 noop. dryRun은 무변경이다. 기본 운영 경로에서는 엔드포인트가 사용 불가여야 한다.
7. 단순한 격리 방식을 택한다. 별도 bench source set 또는 bench 프로파일+기본 false property+조건부 보안 통로로 제한한다. 선택과 jar/활성화 경계를 테스트·문서로 남긴다. 운영 설정/ignored 비밀값은 출력하지 않는다.

## 검증

- DB_DIRECT 실제 orchestrator/HTTP 경로의 정적 5축 성공, 필터·두 페이지·표시 필드, 동네 버전/범위 만료 및 예산 검증. standalone reader 테스트만으로 연결 완료라 하지 않는다.
- meter의 실제 증가/미증가, load share 집계, arrays-built/use 해석, prepare reset의 DB/version 무변경과 dryRun 무변경.
- 기본 설정에서 bench mutation endpoint 비활성. 활성 bench에서 도구가 요구하는 실제 응답 필드와 meter 이름 확인.
- HTTP 네 arm 동등성과 실제 성능 수집은 다음 로컬 측정 Task에서 수행한다. 이 Task에서는 구현·테스트만 수행한다.

## 측정 전달물

읽어야 할 도구 경로: `/Users/mkyu/Desktop/SOPT/solply-server/load-test/campaigns/2026-09-21_town-cache-comparison/`.
후속 측정은 이 worktree의 dirty code로 동일 이미지를 빌드해야 한다. 원본 checkout으로 빌드하면 새 구현이 빠진다.
형상은 HEAD뿐 아니라 diff/새 파일 해시, 실제 jar/image ID를 남긴다. 현재 cache max-places=200000 등의 기본값은 미측정 잠정치다.

## 메인 중간 리뷰 — 최종 보고 전 확인

- `SnapshotInstaller.rebuildAndInstall`은 same-version install=false여도 source 읽기와 정렬을 실제 수행한다. `globalBuilt` 및 prepare timer를 설치 성공에만 묶지 않는다. H2 호출의 실제 construction 비용을 집계하고 version 무변경/계측 증가 테스트를 남긴다.
- `SecurityConfig.benchChain`도 controller와 같은 `bench` profile 및 enabled=true 조건으로 제한한다. jar에서 코드가 제거되는 것은 아니므로 문서에서 혼동하지 않는다.
- `TownLoadRegistry`의 resident cache 설명은 townId 키+단조 최신 항목, in-flight 식별만 town/version이라는 실제 구현에 맞춘다.
- `orchestration check` 결과의 body를 tail로 자르지 않는다. 전체 메시지를 읽고 반영 후 delivery ack하며, ack 응답에 새 delivery가 있으면 그것도 처리한다.
