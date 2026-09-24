> **이전 설계·작업 이력 — 현재 구현 요구사항이나 완료 판정으로 사용하지 마세요.** 현재 기준: [설계 근거와 진행 상황](../../../blog/2026-09-21-place-list-design-evidence.md).

# 메인 계약 검토 — 구현 중 중간 판정

2026-09-21 최초 구현 중의 검토 기록이다. **아래 목록은 당시 수정 요청이며 현재 미완료 목록이 아니다.** 후속 해결 근거는 [계약 검증](../verification/2026-09-21-town-cache-contract.md)과 [통합 검증](../verification/2026-09-21-comparison-integration.md)에 있다. 최종 선택·잔여 검증 상태는 [메인 종료 기록](/Users/mkyu/Desktop/SOPT/solply-server/docs/handoff/2026-09-21-place-list-completion.md)을 따른다. 최초 지적은 검토 이력으로 보존한다.

## 완료 전 필수 수정

1. **표시 변경 분류**: 실제 `AdminPlaceService.updatePlace`에서 이름/썸네일만 바꾸면 town version은 그대로여야 한다. 현재 `syncPlaceStats`→`markTownsChanged` 무조건 호출은 위반이다. actual facade를 호출한 테스트로 입증한다. JDBC로 표시값만 직접 바꾼 테스트는 이 경로의 증거가 아니다.
2. **동시 이동**: A→B와 A→C가 경합할 때 나중 writer가 실제 출발지 B도 bump해야 한다. 수정 전 place/통계의 최신 상태를 잠금 아래 확보한다. 이미 만들어진 RR read view에서 일반 SELECT만 추가하는 것은 해결이 아니다. 별도 연결·latch로 실제 writer 두 개를 실행해 출발/도착의 commit/rollback을 검증한다.
3. **전체 대기 예산**: request 진입에서 deadline을 만들고 validation DB, connection/queue, metadata 관측, load, 재개 queue 대기를 감싼다. 동기 작업이 끝난 후 future timeout을 붙여서는 connection 대기를 제한하지 못한다. response future 독립 timeout 및 이미 끝난 all-hit에도 예산 적용. timeout/cancel은 shared load를 취소하지 않는다.
4. **resident 구버전 보관 제거**: resident key는 townId 하나, value에 version. 같은 동네 여러 버전 payload를 계속 보관하지 않는다. flight 식별에는 `(town,version)` 가능. 뒤늦은 old load가 newer resident를 덮지 않으며 이미 참조를 확보한 요청은 끝낼 수 있다. 빈 동네 weight≥1.
5. **거리 경로 호환**: 기존 v7 global 커서는 계속 encode/decode 가능하도록 유지. 새 town scope만 v8. 공통 포맷 변경으로 거리 커서를 일괄 만료시키지 않는다. 거리 최적화/벤치는 추가하지 않는다.
6. **실제 요청 정렬 후보**: objects-only 적재는 5축 정렬 배열을 만들지 않는다. 요청마다 필터→합집합 한 번 정렬. presorted와 결과·커서 동일. 정렬 배열은 외부 수정할 수 없게 소유 경계를 제한한다.
7. **flight 등록/실행 경계**: computeIfAbsent mapping 내부 execute와 완료 remove의 동기 재진입/거절에 의존하지 않는다. putIfAbsent로 등록 후 실행하고 성공/실패시 자기 future만 제거. executor 거절·inline 실행·실패 후 retry의 의미 있는 테스트.

## 반드시 보강할 검증

- 같은 RR read view에서 metadata와 source를 읽는 사이 별도 writer가 source+version을 커밋해도 일관된 결과를 반환. 여러 동네 이동 전 A/후 B를 섞어 중복하지 않음.
- actual admin create/delete/move/tag/display 분류 및 원본+version rollback.
- actual full batch를 호출해 수치가 같고 표시만 바뀐 동네도 모두 bump; 배치 실패시 원본+version 모두 rollback. version helper만 호출 후 throw하는 것은 batch 경로 증거가 아님.
- metadata/connection/queue 지연과 hit 경로에서 전체 예산, cancellation 격리, eviction/reload, 늦은 old publish.
- 기존 회귀 테스트 실패를 합의 위반 기대값으로 고쳐 green으로 만들지 않는다.

## 비교 전제와 문서

- DB_DIRECT는 다음 비교 단계에서 연결해야 한다. 네 전략을 모두 같은 정적5축 결과로 검증하기 전 성능 비교를 시작하지 않는다.
- cache maxPlaces=200000, load pool=4/unbounded 등은 측정 근거 없는 임시값. 설정 가능하게 하고 확정 정책으로 표현하지 않는다.
- 이름 표시의 지연은 다음 성공한 배치 **및 이후 성공한 해당 동네 적재**까지다. 배치까지가 고정 최신성 상한이라고 적지 않는다.
- legacy global snapshot은 거리 경로 때문에 남는다. static 후보의 비용 절감과 앱 전체 메모리 절감을 혼동하지 않는다.
- 과거 63,200 데이터는 x10 실험값이며 현재 DB 실측값이 아니다.
- 원본 캠페인 하위는 별도 Opus 도구 작업자가 소유. 앱 구현자는 그 파일을 덮지 않는다.
