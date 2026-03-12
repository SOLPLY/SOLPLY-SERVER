---
name: create-issue
description: Create a GitHub issue following the repository issue templates in .github/ISSUE_TEMPLATE.
---

# Create Pull Request

This skill defines how pull requests should be created in this repository.

---

# Language Rule

All pull request contents must be written in **Korean**.

The following parts must be written in Korean:

- PR title
- PR description
- PR discussion points

---

# PR Title Rule

The PR title must follow this format:

[convention] feature-name

The convention must match the issue type.

Examples

[feat] searchPublicCourse  
[fix] fix-login-error  
[refactor] separate-recommendation-service

The PR title should match the related issue title as closely as possible.

---

# PR Template

The pull request body must follow this structure.

## 🌳이슈 번호

resolves #<issue-number>

---

## ☀️어떻게 이슈를 해결했나요?

Describe how the issue was solved.

Explain the implemented logic or changes.

- 구현 내용

---

## 🗯️ PR 포인트

Write anything the reviewer should pay special attention to.

Examples

- 특정 로직에 대한 리뷰 요청
- 설계 방향에 대한 피드백 요청
- 성능 관련 검토 요청

- 리뷰 포인트

---

# Rules

1. Always connect the PR with the related issue using:

resolves #<issue-number>

2. PR title must follow the convention format.

3. PR contents must be written in Korean.

4. Ensure the implementation description reflects the actual code changes.

5. Highlight important review points in the PR 포인트 section.

6. Do not open a PR without confirming the final draft.

---

# PR Creation Steps

1. Identify the related issue number.
2. Generate the PR title using the convention rule.
3. Fill the PR template sections.
4. Show the PR draft to the user.
5. After confirmation, create the PR.

---

# GitHub CLI Command

Use the following command to create the PR:

gh pr create \
--title "<PR title>" \
--body "<PR body>"

---

# Example PR

Title

[feat] retrieval-text-generator

Body

## 🌳이슈 번호

resolves #339

---

## ☀️어떻게 이슈를 해결했나요?

retrieval_text 자동 생성 로직을 구현했습니다.

태그 설명 매핑을 통해 장소 특징을 자연어로 변환하고
템플릿 기반으로 retrieval_text를 생성하도록 구현했습니다.

- 태그 설명 매핑 추가
- retrieval_text 생성기 구현
- 추천 시스템에서 사용할 검색 문서 구조 정의

---

## 🗯️ PR 포인트

- retrieval_text 템플릿 구조에 대한 피드백 요청

Before creating a PR, ensure the project builds successfully and tests pass.