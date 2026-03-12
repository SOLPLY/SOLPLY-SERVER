# Create Pull Request

PR contents must be written in **Korean**.

## Title Format
`[<type>] <feature-name>`

Examples: `[feat] recommend-place-by-embedding`, `[fix] fix-null-review-summary`

## PR Body Template
```
## 🌳이슈 번호
resolves #<issue-number>

---

## ☀️어떻게 이슈를 해결했나요?
<description of changes and implemented logic>

---

## 🗯️ PR 포인트
<key review points, design decisions, or feedback requests>
```

## Steps
1. Identify related issue number.
2. Draft PR title and body.
3. Show draft and confirm before creating.
4. Ensure build succeeds before creating PR.
5. Create with: `gh pr create --title "..." --body "..."`

## Rules
- Always link issue with `resolves #<issue-number>`.
- Title type must match the issue type.
- Do not open PR without user confirmation.
