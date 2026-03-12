# Create Commit

## Format
`#<issue-number> <type>: <korean description>`

Example: `#339 feat: 태그별 설명 문구 추가 및 retrieval_text 생성기 구현`

## Rules
1. Write commit messages in Korean.
2. Always include the issue number.
3. Never use `git add .` — stage only files related to the current logical change.
4. Split commits by logical intent; don't mix feature, refactor, and cleanup.
5. Exclude logs, temp files, generated artifacts, and unrelated changes.
6. Verify the build succeeds before committing.
7. Summarize staged changes in 1–2 sentences before committing.
