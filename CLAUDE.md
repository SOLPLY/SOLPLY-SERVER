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

**플랜 승인 후 반드시 아래 순서를 따른다:**
1. [docs/github-workflow.md](docs/github-workflow.md)를 읽는다.
2. 이슈 초안(제목·본문)을 작성해 사용자에게 보여주고 확인받는다.
3. 확인 후 `gh issue create` 실행 → 이슈 번호 확보
4. `develop`에서 브랜치 생성: `feat/#<number>-<short-description>`
5. TODO.md에 진행 중인 이슈와 태스크 목록을 추가한다.
6. 구현 → 커밋 → PR 오픈 (PR도 사용자 확인 후 생성)

## 스킬
- `/review-plan` — 현재 플랜을 GPT 추론 모델에 전송하여 리스크·개선점을 검토받고 한국어로 요약 반환 (필수: `OPENAI_API_KEY`)
- `load-test` — 부하 테스트 계획·실행·기록. **측정 전에 증명할 주장(문제·가설·선택·주장·성공조건)을 확인받는 게이트를 강제한다.** 성능 측정·벤치마크·A/B 비교 요청 시 자동 발동

## 세션 관리
- **시작 시:** [TODO.md](./TODO.md)를 읽고 현재 상태를 파악한다.
- **종료 전:** 완료된 항목을 체크하고, 새로 발견된 작업을 추가한다.
- **병렬 작업:** TODO.md의 묶음 단위로 독립 태스크를 여러 에이전트에 분배한다.

## 참조 문서
- 아키텍처 및 패키지 구조: [docs/architecture.md](docs/architecture.md)
- 코드 컨벤션 및 GitHub 규칙: [docs/conventions.md](docs/conventions.md)
- GitHub 워크플로우 상세: [docs/github-workflow.md](docs/github-workflow.md)
- 부하 테스트 방법론: [docs/perf/load-test-guide.md](docs/perf/load-test-guide.md)
- 부하 테스트 디렉터리 지도: [load-test/README.md](load-test/README.md)
- 현재 작업 목록: [TODO.md](./TODO.md)
