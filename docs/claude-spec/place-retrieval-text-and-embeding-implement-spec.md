장소 검색 데이터 동기화 및 엔티티 표준화 전략

1. 개요
   Place 검색 품질을 결정하는 retrievalText의 구성 요소가 변경될 경우, 이를 감지하여 PlaceSearchDocument를 최신화한다. 특히 연관 엔티티(Tag, PlaceReviewSummary)의 변화가 검색 문서에 전파(Propagate)되도록 설계한다.

2. 엔티티 구조 표준화
   BaseTimeEntity 상속: PlaceSearchDocument, PlaceReviewSummary는 BaseTimeEntity를 상속받아 createdAt, updatedAt을 공통 관리한다. (기존 개별 시간 필드 삭제)

PlaceSearchDocument 상태 관리: EmbeddingStatus 필드(INIT, READY, DIRTY, FAILED)를 추가하여 업데이트 주기를 제어한다.

- INIT: 최초 생성됨 (임베딩 전)
- READY: 최신 임베딩 완료
- DIRTY: 원본 수정으로 인한 업데이트 필요
- FAILED: API 호출 실패 (재시도 대상)

연관 관계: place_search_documents 테이블은 Place에 ON DELETE CASCADE가 설정되어 장소 삭제 시 document도 자동 삭제된다.

3. 리트리벌 텍스트(retrievalText) 구성 요소 및 트리거 조건
   retrievalText를 구성하는 다음 데이터 중 하나라도 변경되면 해당 장소의 PlaceSearchDocument 상태는 **DIRTY**로 변경되어야 한다.

3.1 트리거 발생 조건 (Update Required When:)
Place 엔티티 수정: name, introduction, checkpoints 리스트 내용이 변경될 때.

PlaceTag 관계 변화: 특정 장소에 태그가 추가되거나 삭제될 때.

Tag 엔티티 정보 수정: 태그의 name이나 특히 검색 의미를 담은 meaning 필드가 수정될 때 (해당 태그를 참조하는 모든 장소가 DIRTY 대상).

Tag 비활성화: 태그가 비활성화될 경우, 해당 태그와 모든 하위(자식) 태그를 BFS로 순회하여 subtree 전체에 속한 장소들을 DIRTY로 일괄 변경한다. 태그를 활성화할 경우에는 해당 태그(루트만)에 속한 장소들만 DIRTY로 변경한다.

PlaceReviewSummary 수정: 리뷰 요약 내용(summaryContent)이 갱신될 때.

4. 프로세스: Two-Track 업데이트 전략
   Track A: 장소 신규 생성 (Fast Track)
   Trigger: Place 신규 저장 완료.

Process: PlaceCreatedEvent를 비동기 리스너가 수신하여 즉시 임베딩 생성 및 PlaceSearchDocument INSERT (임베딩 성공시 상태: READY).

Track B: 데이터 변경 감지 (Slow Track)
Trigger: 위 3.1절에서 정의한 모든 변경 사항.

Action:

단일 장소 변경 시: 해당 장소의 PlaceSearchDocument 상태를 DIRTY로 변경.

전역 태그(Tag) 변경 시: 해당 tag(들)를 보유한 모든 PlaceSearchDocument를 @Modifying 벌크 UPDATE로 DIRTY 처리.
이때 @LastModifiedDate는 벌크 쿼리에서 동작하지 않으므로 쿼리에 updatedAt = CURRENT_TIMESTAMP를 명시하여 concurrency guard가 변경을 감지할 수 있게 한다.

Process: 정기 배치 스케줄러(매일 새벽 3시)가 INIT/DIRTY/FAILED 문서를 모아 재임베딩 후 UPDATE (상태: READY).

5. 배치 처리 설계

5.1 대상 조회
배치 대상 placeId 목록은 PlaceSearchDocument 엔티티 전체가 아닌 ID projection 쿼리(SELECT psd.placeId)로 조회하여, MEDIUMBLOB 임베딩 컬럼을 포함한 엔티티 전체가 영속성 컨텍스트에 적재되는 것을 방지한다.

5.2 청크 단위 처리
placeId 목록을 50개 단위 청크로 나누어 각 청크를 별도 트랜잭션으로 processBatch() 호출.
청크 단위 실패 시 해당 청크의 문서만 FAILED 처리하여 나머지 청크에 영향 없음.

5.3 임베딩 중 수정 감지 (Concurrency Guard)
임베딩 API 호출은 수 초가 소요될 수 있어, 그 사이 다른 트랜잭션이 문서를 수정할 수 있다.
이를 감지하기 위해:

1. API 호출 직전 startedAt = LocalDateTime.now() 기록.
2. API 응답 수신 후 entityManager.refresh(doc)로 DB 최신 상태(updatedAt) 재조회.
3. doc.updatedAt > startedAt이면 임베딩 중 수정이 발생한 것으로 판단하여 READY 대신 DIRTY 상태 유지 (다음 배치 사이클에 재임베딩).
4. doc.updatedAt <= startedAt이면 정상적으로 READY로 전환.

6. Claude Code 작업 요청 가이드
   [Step 1] 엔티티 리팩토링 및 필드 추가
   PlaceSearchDocument, PlaceReviewSummary에 BaseTimeEntity를 적용하고 기존 중복 시간 필드를 제거하세요.

PlaceSearchDocument에 EmbeddingStatus 열을 추가하세요.

[Step 2] 변경 감지 로직(Dirty Flagging) 구현
Place.update() 시 DIRTY 플래그를 세우는 로직을 작성하세요.

핵심: Tag 엔티티의 meaning이 수정될 경우, 해당 태그를 보유한 모든 Place를 찾아 PlaceSearchDocument를 DIRTY로 만드는 서비스 로직 혹은 이벤트를 구현하세요. 태그 비활성화 시에는 BFS로 subtree 전체를 순회하여 관련 장소들을 DIRTY 처리하세요.

DIRTY 벌크 UPDATE 쿼리는 updatedAt = CURRENT_TIMESTAMP를 함께 설정하여 concurrency guard가 동작하도록 하세요.

PlaceReviewSummary가 갱신될 때도 연관된 PlaceSearchDocument를 DIRTY로 만드세요.

[Step 3] 비동기 리스너 및 배치 스케줄러
신규 생성 이벤트를 처리하는 @Async 리스너를 구현하세요.

INIT/DIRTY/FAILED 상태인 문서들의 placeId를 ID projection 쿼리로 조회하고, 청크 단위로 RetrievalTextBuilder로 다시 빌드하여 임베딩 API를 호출하는 @Scheduled 배치 서비스를 구현하세요.

임베딩 API 호출 전 startedAt을 기록하고, 응답 후 entityManager.refresh()로 최신 updatedAt을 재조회하여 concurrency guard를 적용하세요.
