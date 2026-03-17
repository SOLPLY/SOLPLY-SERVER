# 컨벤션 가이드

## 코드 작성 규칙

### API 응답
모든 API 응답은 `CustomApiResponse<T>` 래퍼를 사용한다.

### 예외 처리
`BusinessException(ErrorCode)`를 던지고, `GlobalExceptionHandler`에서 일괄 처리한다.

### 인증
JWT 기반. 컨트롤러에서 `@CurrentUserId`로 인증된 유저 ID를 주입받는다.

### QueryDSL
복잡한 쿼리에 사용. `./gradlew compileJava`로 Q-Class를 생성한다.

### DB 스키마 변경
Flyway로 관리. 스키마 변경 시 반드시 `V{n}__description.sql` 파일을 신규 생성한다.

### Soft Delete
`is_deleted`, `deleted_at` 필드를 사용하여 데이터를 보존한다.

---

## 개발 원칙

- 작고 집중된 커밋을 선호한다.
- 기능 개발과 리팩토링을 한 커밋에 섞지 않는다.
- 커밋 전 반드시 빌드 성공 여부를 확인한다.
- 엔티티 스키마 변경 시 Flyway 마이그레이션 파일 생성을 잊지 않는다.

---

## GitHub 워크플로우

상세 규칙: [github-workflow.md](./github-workflow.md)

```text
이슈 생성 → 브랜치 생성 → 구현 → 커밋 → PR 오픈
```

- 이슈 타입: `[Feat]`, `[Fix]`, `[Refactor]`, `[Chore]`, `[Docs]`
- 브랜치: `<type>/#<issue-number>-<short-description>` (예: `feat/#339-recommend-place`)
- 커밋: `#<issue-number> <type>: <한국어 설명>` (예: `#339 feat: 추천 로직 구현`)
- PR 제목: `[<type>] <feature-name>`
