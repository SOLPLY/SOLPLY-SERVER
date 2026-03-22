# GitHub 워크플로우 가이드

모든 작업은 아래 순서를 따른다.

```
이슈 생성 → 브랜치 생성 → 구현 → 커밋 → PR 오픈
```

---

## 이슈 생성

**이슈 타입 및 라벨**

| 타입 | 제목 접두사 | 라벨 | 사용 시점 |
|------|------------|------|----------|
| 기능 | `[Feat]` | `💭 FEAT` | 신규 기능, API, 동작 추가 |
| 버그 | `[Fix]` | `🐞 BUG` | 버그, 오류, 잘못된 동작 수정 |
| 리팩토링 | `[Refactor]` | `🪄 REFACTOR` | 동작 변경 없는 구조 개선 |
| 유지보수 | `[Chore]` | `⚙️ CHORE` | 의존성, 빌드 설정, CI/CD |
| 문서 | `[Docs]` | `📖 DOCS` | 문서 추가/수정 |

- Assignee: 항상 `uykm` 자동 지정
- 타입·라벨은 컨텍스트에서 자동 추론하며 사용자에게 묻지 않는다.
- 이슈 본문은 한국어로 작성한다.

**이슈 본문 템플릿**

기능/유지보수/문서:
```
💬 이슈 설명
<설명>

✅ 작업할 내용
- [ ] 항목 1
```

버그:
```
💬 이슈 설명
<설명>

🔨 작업 내용
- [ ] 항목 1
```

리팩토링:
```
🎨 리팩토링 이슈 설명
<설명>

✅ 작업할 내용
- [ ] 항목 1
```

**절차**
1. 타입 결정
2. 한국어로 이슈 초안 작성
3. 사용자에게 초안 보여주고 확인받기
4. `gh issue create --title "..." --label "..." --assignee "uykm" --body "..."` 실행

---

## 브랜치 생성

**형식:** `<type>/#<issue-number>-<short-description>`

```
feat/#339-recommend-place
fix/#412-null-review-summary
refactor/#287-extract-retrieval-text-builder
docs/#315-update-architecture
chore/#398-update-openai-sdk
```

**타입:** `feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `perf`

- 항상 `develop`에서 분기한다.
- 소문자 케밥케이스 사용.

---

## 커밋 작성

**형식:** `#<issue-number> <type>: <한국어 설명>`

```
#339 feat: 임베딩 기반 장소 추천 로직 구현
#412 fix: 리뷰 요약 null 처리 추가
```

이슈와 무관한 작업은 이슈 번호를 생략한다.
```
docs: 아키텍처 문서 추가
```

- `git add .` 지양 — 논리적 변경 단위만 스테이징한다.
- 기능/리팩토링/정리를 한 커밋에 섞지 않는다.
- 커밋 전 빌드 성공 여부를 확인한다.

---

## PR 생성

**제목 형식:** `[<type>] <feature-name>`

```
[feat] 임베딩 기반 장소 추천 API
[fix] 리뷰 요약 null 처리
```

**본문 템플릿:**
```markdown
## 🌳이슈 번호
resolves #<issue-number>

---

## ☀️어떻게 이슈를 해결했나요?
<변경 내용 및 구현 로직>

---

## 🗯️ PR 포인트
<핵심 리뷰 포인트, 설계 결정, 피드백 요청>
```

- Assignee: `uykm`
- Reviewer: `uykm`, `ImHyungsuk`, `88guri`
- Label: 이슈와 동일한 라벨을 붙인다.
- 반드시 이슈를 `resolves #<issue-number>`로 연결한다.
- PR 생성 전 사용자 확인을 받는다.
- PR 생성 전 빌드 성공 여부를 확인한다.
