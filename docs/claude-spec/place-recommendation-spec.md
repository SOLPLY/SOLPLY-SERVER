📄 장소 추천 시스템 구현 명세서

1. 개요                                                                                                                                                            
   사용자의 자연어 질문을 분석하여, 특정 동네(townId) 내에서 의도와 가장 유사한 장소 상위 3개를 추천한다.

2. 핵심 설계 원칙
- Logic First: 데이터 생성 자동화보다 추천 엔진 로직을 먼저 완성
- Rule-based Generation: retrieval_text는 LLM이 아닌 템플릿 기반으로 생성
- Vector Similarity: 코사인 유사도로 추천
- Hard Filtering: townId는 DB SQL로 먼저 필터링

3. retrieval_text 템플릿
   {place_name}은 {town}에 위치한 {category}다.
   {intro_text}
   {check_points}
   {tag_based_meaning}
   리뷰에서는 {review_summary}라는 평가가 자주 보인다.

4. 추천 로직 프로세스
   1. Input: 사용자 질문 + townId
   2. Embedding: 질문을 벡터로 변환
   3. Candidate Fetch: townId 기반 장소 벡터 조회
   4. Similarity: 코사인 유사도 계산
   5. Top-K: 상위 3개 추출
   6. Response: 장소 식별자, 이름, 메인 태그, 옵션 태그, 동네명 + 추천 사유(Reason)

5. LLM 이유 생성 (Step 2 이전 필요)
- 유사도로 TOP-3 뽑은 후, 각 장소에 대해 LLM에게 "왜 이 질문에 어울리는지" 한 문장으로 생성 요청
- 입력: 사용자 질문 + 장소의 태그/설명/리뷰 요약

6. 구현 단계
- Step 1 (완료): 인프라, 임베딩 연동, 추천 API
- Step 2 (완료): RetrievalTextBuilder, 자동 업데이트 트리거, 리뷰 요약 배치
