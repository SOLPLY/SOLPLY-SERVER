📄 장소 추천 시스템 구현 명세서 (Initial Version)

1. 개요
사용자의 자연어 질문을 분석하여, 특정 행정구역(동네) 내에서 의도와 가장 유사한 장소 상위 3개를 추천한다.

2. 핵심 설계 원칙
- Logic First: 데이터 생성 자동화(배치/트리거)보다 검색 및 추천 엔진 로직을 먼저 완성한다.
- Rule-based Generation: 초기 버전의 검색용 텍스트(retrieval_text)는 LLM이 아닌 미리 정의된 템플릿 기반으로 생성한다.
- Vector Similarity: 사용자 질문과 장소별 retrieval_text의 임베딩 값을 비교하여 코사인 유사도로 추천한다.
- Hard Filtering: 지역(Town)은 DB 수준에서 SQL로 먼저 걸러내어 계산 효율을 높인다.

3. 데이터베이스 구조 (Proposed)
- place_review_summary가 업데이트되면 이 테이블의 retrieval_text와 embedding도 갱신되는 구조
place_search_document 테이블
컬럼명 | 타입 | 설명 
place_id FK | place | 테이블 참조
retrieval_text | Text | 템플릿 기반 최종 검색 문서
embedding | Vector | retrieval_text의 임베딩 값
embedding_model | String | 사용된 임베딩 모델명
generated_at | DateTime | 생성 일시

place_review_summary
place_id: (FK) 장소 참조
summary_content: 요약된 리뷰 텍스트 (예: "혼자 작업하기 좋고 커피가 맛있음")
review_count_at_time: 요약 당시의 리뷰 개수 (업데이트 시점 판단용)
updated_at: 마지막 요약 시각

- 임베딩 트리거:
  1. 새로운 장소가 등록될 때 (최초 생성)
  2. 장소의 기본 정보(이름, 소개글, 태그)가 수정될 때 (즉시 업데이트)
  3. 리뷰 요약(place_review_summary)이 갱신될 때 (배치 혹은 이벤트 기반)

- 리뷰 요약 주기: * 초기에는 수동 또는 주 1회 배치로 실행.

4. 검색 문서(retrieval_text) 생성 규칙템플릿 구조
[장소명]은 [동네]에 위치한 [카테고리]다. 
[소개글]. 
[태그 기반 문장들]. 
리뷰에서는 [리뷰 요약]이라는 평가가 자주 보인다.

태그 매핑 매뉴얼 (Code Level)
태그의 키워드를 아래와 같이 자연어 문장으로 변환하여 포함한다.
- 작업: "노트북 작업이나 개인 업무를 하기 좋은 공간이다."
- 독서: "조용히 책을 읽으며 시간을 보내기 좋다.
- "혼밥: "혼자 방문해도 주문하기 편한 메뉴가 있다."
- (기타 태그 규칙 생략 - 기획안 참조)

5. 추천 로직 프로세스 (우선 구현 대상)
   1. Input: 사용자 질문 + townId. 
   2. Embedding: 사용자 질문을 EmbeddingModel을 통해 벡터로 변환. 
   3. Candidate Fetch: SQL을 통해 해당 townId를 가진 장소들의 벡터 데이터를 조회. 
   4. Similarity Calculation: Java 레벨에서 질문 벡터와 각 장소 벡터 간 Cosine Similarity 계산. 
   5. Top-K Selection: 유사도 점수가 가장 높은 상위 3개 장소 추출. 
   6. Response: 추천 장소 리스트(장소 식별자, 장소이름, 메인 태그, 옵션 태그 리스트, 동네이름)와 추천 사유(Reason) 반환.

6. 구현 단계 (Phases)
    [Step 1] 인프라 구축 및 추천 엔진 (현재 목표)
   - place_search_document, place_review_summary 테이블 마이그레이션. 
   - Spring AI (OpenAI) 설정 및 EmbeddingModel 연동. 
   - townId 기반 후보군 조회 및 코사인 유사도 계산 로직 구현. 
   - 추천 API 엔드포인트 완성.

    [Step 2] 데이터 생성 및 자동화 (추후 진행)
   - 태그 매핑 사전 및 RetrievalTextBuilder 구현.
   - 신규 장소 등록/수정 시 임베딩 자동 업데이트 트리거.
   - 정기적인 리뷰 요약(Review Summary) 업데이트 배치 작업.

[Step 2 이전에 해야할 작업 요구사항]
LLM(OpenAI)을 활용한 이유 생성
단순히 유사도 점수만 계산하지 말고, 상위로 뽑힌 TOP-K 장소들에 대해 다시 한번 LLM에게 "왜 이 장소가 이 질문에 어울리는지" 물어보는 방식입니다.

동작 방식:
현재 코드처럼 벡터 유사도로 후보를 3개 뽑습니다.
이후 LLM에게 사용자 질문 + 해당 장소의 태그/설명/리뷰 요약을 전달합니다.
"이 정보를 바탕으로 사용자에게 추천하는 이유를 한 문장으로 써줘"라고 요청합니다.

예시: "조용하고 노트북 하기 좋은 카페" 질문 → "이곳은 '카공' 태그가 있고 실제 리뷰에서도 '콘센트가 많다'는 언급이 많아 조용한 환경을 원하시는 민규 님께 딱 맞습니다."

[retrieval text 형식]
``
{place_name}은 {town}에 위치한 {category}다.
{intro_text}
{check_points}
{tag_based_sentences}
리뷰에서는 {review_summary}라는 평가가 자주 보인다.
``
