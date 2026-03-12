# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 💬 Communication
- 항상 **한국어**로 답변한다.

## 🚀 Project Overview
- **SOLPLY (솔플리):** 1인 가구 여가 장소 및 코스 큐레이션 서비스
- **Stack:** Java 21 + Spring Boot 3.3.5
- **Goal:** 사용자 관심사 기반의 개인화된 활동 추천 제공

## 🛠 Common Commands

### Build & Run
bash
# 빌드 (테스트 제외)
./gradlew build -x test

# 로컬 실행
./gradlew bootRun

### Testing
Bash
# 전체 테스트 실행
./gradlew test

# 특정 테스트 클래스 실행
./gradlew test --tests "org.sopt.solply_server.domain.bookmark.BookmarkEventListenerTest"

# 상세 로그와 함께 테스트 실행
./gradlew test --info

### Database Migrations (Flyway)
Bash
# dev 환경 마이그레이션 실행
./gradlew flywayMigrate -PflywayUrl=... -PflywayUser=... -PflywayPassword=...

# 마이그레이션 정보 확인
./gradlew flywayInfo

### Architecture
Package Structure
Plaintext
org.sopt.solply_server/
├── domain/          # 비즈니스 도메인 (DDD 스타일)
└── global/          # 공통 인프라 및 기술적 관심사

- Domain Modules (domain/): 계층 구조 준수 
  - controller/, service/, entity/, repository/, dto/, cache/ 
  - Domains: admin, auth, bookmark, course, file, place, recommend, review, tag, test, town, user
- Global Infrastructure (global/):
  - config/, exception/, jwt/, dto/, annotation/ 
  - ai/: EmbeddingService, CosineSimilarityUtil, FloatArrayConverter

#  Key Patterns & Rules
- API Response: 모든 응답은 CustomApiResponse<T> 래퍼를 사용한다. 
- Exception Handling: BusinessException(ErrorCode)을 던지며 GlobalExceptionHandler에서 처리한다. 
- Authentication: JWT 기반. 컨트롤러에서 @CurrentUserId로 인증된 유저 ID를 주입받는다. 
- QueryDSL: 복잡한 쿼리에 사용. ./gradlew compileJava로 Q-Class를 생성한다. 
- Database: Flyway 관리. 스키마 변경 시 V{n}__description.sql 파일을 신규 생성한다. 
- Soft Delete: is_deleted, deleted_at 필드를 사용하여 데이터를 보존한다.

### AI & Vector Management
- Vector Storage: 별도의 벡터 DB 대신 MySQL MEDIUMBLOB 컬럼과 FloatArrayConverter를 사용하여 임베딩을 저장한다. 
- Spring AI: 버전 1.0.0-M5 사용. EmbeddingModel.embed(text)는 float[]를 반환한다. 
- Similarity: CosineSimilarityUtil을 통해 서버 사이드에서 유사도를 계산한다.

### Environments & Configuration
Profile,Config File,Notes
(default),application.yml,"MySQL(3308), Redis(6381) on localhost"
dev,application-dev.yml,"환경 변수 활용 (DB_URL, REDIS_HOST, OPENAI_API_KEY 등)"
prod,application-prod.yml,운영 환경 (환경 변수 활용)

Security Note: OPENAI_API_KEY와 같은 민감한 키는 절대 .yml에 하드코딩하지 않고 환경 변수로 관리한다.

### Claude Project Guidelines
Claude는 본 저장소에서 작업할 때 다음 워크플로우와 컨벤션을 반드시 준수해야 합니다.

### Development Workflow
모든 작업은 아래 순서를 따르며, Claude는 커맨드를 사용하여 이를 자율적으로 제안하고 수행한다.

- GitHub Issue 생성 (`/create-issue`)
- 이슈 기반 브랜치 생성 (`/create-branch`)
- 코드 구현
- 커밋 작성 (`/create-commit`)
- Pull Request 오픈 (`/create-pull-request`)

### Rules
- GitHub Issues: .github/ISSUE_TEMPLATE 내 bug.yml, feature.yml, refactor.yml, chore.yml, docs.yml을 준수하며 한국어로 작성한다.
- Branch Naming: <type>/#<issue-number>-<short-description> (예: feat/#339-recommend-place)
- Commit Message: #<issue-number> <type>: <description> (예: #339 feat: 임베딩 로직 구현), 이슈와 무관한 작업은 이슈 번호 생략
- 한국어로 작성, 논리적 단위로 커밋 분리, git add . 지양.
- Pull Request: PR 템플릿 준수. 제목 형식은 [<type>] <feature-name> (예: [feat] 추천 API 구현). 모든 내용은 한국어로 작성한다.

💡 Development Principles
- 작고 집중된 커밋을 선호한다.
- 기능 개발과 리팩토링을 한 커밋에 섞지 않는다.
- 커밋 전 반드시 빌드 성공 여부를 확인한다.
- 엔티티 스키마 변경 시 Flyway 마이그레이션 파일 생성을 잊지 않는다.

🛠 Commands
Claude는 .claude/commands에 정의된 커맨드를 사용하여 이슈 생성, 브랜치 관리, 커밋 및 PR 생성을 수행한다.