장소 검색 데이터 동기화 및 엔티티 표준화 전략
1. 개요
   Place 검색 품질을 결정하는 retrievalText의 구성 요소가 변경될 경우, 이를 감지하여 PlaceSearchDocument를 최신화한다. 특히 연관 엔티티(Tag, PlaceReviewSummary)의 변화가 검색 문서에 전파(Propagate)되도록 설계한다.

2. 엔티티 구조 표준화
   BaseTimeEntity 상속: PlaceSearchDocument, PlaceReviewSummary는 BaseTimeEntity를 상속받아 createdAt, updatedAt을 공통 관리한다. (기존 개별 시간 필드 삭제)

PlaceSearchDocument 상태 관리: EmbeddingStatus 필드(INIT, READY, DIRTY, FAILED)를 추가하여 업데이트 주기를 제어한다.

3. 리트리벌 텍스트(retrievalText) 구성 요소 및 트리거 조건
   retrievalText를 구성하는 다음 데이터 중 하나라도 변경되면 해당 장소의 PlaceSearchDocument 상태는 **DIRTY**로 변경되어야 한다.

3.1 트리거 발생 조건 (Update Required When:)
Place 엔티티 수정: name, introduction, checkpoints 리스트 내용이 변경될 때.

PlaceTag 관계 변화: 특정 장소에 태그가 추가되거나 삭제될 때.

Tag 엔티티 정보 수정: 태그의 name이나 특히 검색 의미를 담은 meaning 필드가 수정될 때 (해당 태그를 참조하는 모든 장소가 DIRTY 대상).

PlaceReviewSummary 수정: 리뷰 요약 내용(summaryContent)이 갱신될 때.

4. 프로세스: Two-Track 업데이트 전략
   Track A: 장소 신규 생성 (Fast Track)
   Trigger: Place 신규 저장 완료.

Process: PlaceCreatedEvent를 비동기 리스너가 수신하여 즉시 임베딩 생성 및 PlaceSearchDocument INSERT (상태: READY).

Track B: 데이터 변경 감지 (Slow Track)
Trigger: 위 3.1절에서 정의한 모든 변경 사항.

Action:

단일 장소 변경 시: 해당 장소의 PlaceSearchDocument 상태를 DIRTY로 변경.

전역 태그(Tag) 변경 시: 해당 tagId를 가진 모든 PlaceSearchDocument를 찾아 상태를 DIRTY로 일괄 변경 (Bulk Update).

Process: 정기 배치 스케줄러가 DIRTY/FAILED 문서를 모아 재임베딩 후 UPDATE (상태: READY).

5. Claude Code 작업 요청 가이드
   [Step 1] 엔티티 리팩토링 및 필드 추가
   PlaceSearchDocument, PlaceReviewSummary에 BaseTimeEntity를 적용하고 기존 중복 시간 필드를 제거하세요.

PlaceSearchDocument에 EmbeddingStatus 열을 추가하세요.

[Step 2] 변경 감지 로직(Dirty Flagging) 구현
Place.update() 시 DIRTY 플래그를 세우는 로직을 작성하세요.

핵심: Tag 엔티티의 meaning이 수정될 경우, 해당 태그를 보유한 모든 Place를 찾아 PlaceSearchDocument를 DIRTY로 만드는 서비스 로직 혹은 이벤트를 구현하세요.

PlaceReviewSummary가 갱신될 때도 연관된 PlaceSearchDocument를 DIRTY로 만드세요.

[Step 3] 비동기 리스너 및 배치 스케줄러
신규 생성 이벤트를 처리하는 @Async 리스너를 구현하세요.

DIRTY 상태인 문서들을 찾아 RetrievalTextBuilder로 다시 빌드하고 임베딩 API를 호출하는 @Scheduled 배치 서비스를 구현하세요.