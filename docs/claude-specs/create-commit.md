---
name: create-commit
description: Create commits following the repository commit message convention.
---

# Create Commit

## Commit Format

#<issue-number> <type>: <korean description>

Example:
#339 feat: 태그별 설명 문구 추가 및 retrieval_text 생성기 구현

## Rules

1. Write commit messages in Korean.
2. Always include the issue number.
3. Do not use `git add .` blindly.
4. Review changed files before staging.
5. Stage only files related to the current logical change.
6. Split commits by logical intent, not by number of files.
7. Do not mix refactoring, feature work, and unrelated cleanup in one commit.
8. Exclude logs, temporary files, generated artifacts, and unrelated changes.
9. Prefer small, reviewable commits that are easy to revert.
10. Before committing, summarize the staged changes in 1-2 sentences.