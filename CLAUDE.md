# CLAUDE.md

## 소통
- 항상 **한국어**로 답변한다.

## 프로젝트
- **SOLPLY (솔플리):** 1인 가구 여가 장소 및 코스 큐레이션 서비스
- **스택:** Java 21 + Spring Boot 3.3.5

## 커맨드
```bash
./gradlew build -x test   # 빌드
./gradlew bootRun          # 실행
./gradlew test             # 테스트
./gradlew compileJava      # Q-Class 생성 (QueryDSL)
```

## 핵심 패턴
- **응답:** `CustomApiResponse<T>` 래퍼 사용
- **예외:** `BusinessException(ErrorCode)` → `GlobalExceptionHandler`
- **인증:** JWT, 컨트롤러에서 `@CurrentUserId`로 주입
- **DB 변경:** Flyway (`V{n}__description.sql`) 신규 생성
- **삭제:** Soft Delete (`is_deleted`, `deleted_at`)

## 개발 워크플로우
이슈 생성 → 브랜치 생성 → 구현 → 커밋 → PR 오픈

## 스킬
- `/review-plan` — 현재 플랜을 GPT 추론 모델에 전송하여 리스크·개선점을 검토받고 한국어로 요약 반환

## 세션 관리
- **시작 시:** [TODO.md](./TODO.md)를 읽고 현재 상태를 파악한다.
- **종료 전:** 완료된 항목을 체크하고, 새로 발견된 작업을 추가한다.
- **병렬 작업:** TODO.md의 묶음 단위로 독립 태스크를 여러 에이전트에 분배한다.

## 참조 문서
- 아키텍처 및 패키지 구조: [docs/architecture.md](docs/architecture.md)
- 코드 컨벤션 및 GitHub 규칙: [docs/conventions.md](docs/conventions.md)
- GitHub 워크플로우 상세: [docs/github-workflow.md](docs/github-workflow.md)
- 현재 작업 목록: [TODO.md](./TODO.md)
