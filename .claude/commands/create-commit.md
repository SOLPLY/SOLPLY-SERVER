# Create Commit

## Format
`#<issue-number> <type>: <korean description>`

Example: `#339 feat: 태그별 설명 문구 추가 및 retrieval_text 생성기 구현`

If the commit is **not related to any specific issue** (e.g. docs, chore, config):
`<type>: <korean description>`

Example: `docs: 슬래시 커맨드 파일 추가`

## Rules
1. Write commit messages in Korean.
2. Include the issue number when the commit is related to a specific issue. Omit it otherwise.
3. Never use `git add .` — stage only files related to the current logical change.
4. Split commits by logical intent; don't mix feature, refactor, and cleanup.
5. Exclude logs, temp files, generated artifacts, and unrelated changes.
6. Verify the build succeeds before committing.
7. Summarize staged changes in 1–2 sentences before committing.
