# Create GitHub Issue

Create GitHub issues by following the repository's issue form templates in `.github/ISSUE_TEMPLATE`.

## Language Rule

All GitHub issues must be written in **Korean**.

The following parts must be written in Korean:

- Issue title
- Issue description
- Task checklist

Do not write issues in English unless explicitly requested.

The issue structure must still follow the templates defined in `.github/ISSUE_TEMPLATE`.

## When to Use

Use this skill whenever creating a new GitHub issue from the terminal.

This repository uses the following issue form templates:

- `bug.yml`
- `feature.yml`
- `refactor.yml`

The issue must follow the matching template structure as closely as possible in the title, labels, and body.

## Template Source

Read and follow the issue form definitions in:

- `.github/ISSUE_TEMPLATE/bug.yml`
- `.github/ISSUE_TEMPLATE/feature.yml`
- `.github/ISSUE_TEMPLATE/refactor.yml`

## Issue Type Rules

### 1. Bug
Use the bug template when the issue is about:
- a bug
- an error
- incorrect behavior
- something broken
- an unexpected result that should be fixed

Use:
- title prefix: `[Fix]`
- label: `fix`

Required sections in body:
- `💬 이슈 설명`
- `🔨작업 내용`

### 2. Feature
Use the feature template when the issue is about:
- a new feature
- a new API
- new behavior
- a new capability
- a planned implementation

Use:
- title prefix: `[Feat]`
- label: `feature`

Required sections in body:
- `💬 이슈 설명`
- `✅ 작업할 내용`

### 3. Refactor
Use the refactor template when the issue is about:
- code cleanup
- structural improvement
- design improvement without changing intended behavior
- moving responsibilities
- improving readability or maintainability

Use:
- title prefix: `[Refactor]`
- label: `refactor`

Required sections in body:
- `🎨 리팩토링 이슈 설명`
- `✅ 작업할 내용`

## Rules

1. Always choose exactly one issue type: `bug`, `feature`, or `refactor`.
2. Always match the title prefix to the selected template.
3. Always apply the correct label for the selected template.
4. Always write the issue body in Korean.
5. Always preserve the body section headings defined by the template.
6. Convert the discussed requirements into a clear checklist.
7. Do not create the issue immediately without first showing a draft.
8. Before creating the issue, confirm:
    - target repository
    - issue type
    - title
    - labels
    - body
9. Use `gh issue create` to create the final issue.
10. If the current repository is unclear, check it first before creating the issue.

## Draft Format

### Bug Draft Format

Title:
[Fix] <bug title>

Labels:
fix

Body:
💬 이슈 설명
<bug description>

🔨작업 내용
- [ ] 작업 1
- [ ] 작업 2
- [ ] 작업 3

### Feature Draft Format

Title:
[Feat] <feature title>

Labels:
feature

Body:
💬 이슈 설명
<feature description>

✅ 작업할 내용
- [ ] 작업 1
- [ ] 작업 2
- [ ] 작업 3

### Refactor Draft Format

Title:
[Refactor] <refactor title>

Labels:
refactor

Body:
🎨 리팩토링 이슈 설명
<refactor description>

✅ 작업할 내용
- [ ] 작업 1
- [ ] 작업 2
- [ ] 작업 3

## Steps

1. Read the relevant discussion or user request.
2. Determine whether the issue is a bug, feature, or refactor.
3. Read the matching template from `.github/ISSUE_TEMPLATE`.
4. Draft the issue in Korean using the template structure.
5. Show the draft before creating it.
6. Confirm the target repository.
7. Create the issue using `gh issue create`.

## Command Pattern

Use a command like this after confirmation:

gh issue create \
--title "<final title>" \
--label "<final label>" \
--body "<final body>"

## Safety Checks

Before running `gh issue create`, always:
- check the current repository
- verify the repository owner/name
- verify the title prefix
- verify the label
- verify that the body matches the selected template

## Example Feature Issue

Title:
[Feat] retrieval_text 자동 생성 로직 구현

Labels:
feature

Body:
💬 이슈 설명
장소 추천 시스템에서 사용할 retrieval_text를 템플릿 기반으로 자동 생성하는 로직을 구현한다.
소개글, 태그, 리뷰 요약을 조합해 검색용 문서를 만들고, 이후 임베딩 생성의 입력으로 사용한다.

✅ 작업할 내용
- [ ] 태그 설명 매핑 규칙 정의
- [ ] retrieval_text 생성 템플릿 설계
- [ ] place_search_document 저장 구조 추가
- [ ] 샘플 데이터 기준 생성 결과 검증

## Example Refactor Issue

Title:
[Refactor] 추천 로직과 임베딩 생성 책임 분리

Labels:
refactor

Body:
🎨 리팩토링 이슈 설명
장소 추천 서비스 내부에서 retrieval_text 생성, 임베딩 생성, 유사도 계산 책임이 한 클래스에 섞여 있다.
역할을 분리해 유지보수성과 테스트 용이성을 높인다.

✅ 작업할 내용
- [ ] retrieval_text 생성 책임 분리
- [ ] embedding 생성 책임 분리
- [ ] similarity 계산 모듈 분리
- [ ] 관련 테스트 코드 정리