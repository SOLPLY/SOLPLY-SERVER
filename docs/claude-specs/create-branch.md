---
name: create-branch
description: Create a git branch following the repository branch naming convention.
---

# Create Branch

This skill defines how branches should be created in this repository.

---

## When to Use

Use this skill when starting work on a GitHub issue.

Before implementing any feature, bug fix, or refactor, create a branch
following the repository branch naming convention.

---

## Branch Format

Branches must follow this format:

<type>/#<issue-number>-<short-description>

Example:

feat/#339-recommend-place-by-embedding

---

## Components

### 1. type

Indicates the type of change.

Allowed values:

- feat — New feature
- fix — Bug fix
- refactor — Code refactoring without behavior change
- docs — Documentation updates
- test — Test-related changes
- chore — Maintenance tasks (build, dependency updates, etc.)
- perf — Performance improvements

---

### 2. issue-number

The GitHub issue number related to the work.

Rules:

- Always include the `#` prefix.
- The branch must reference an existing issue.

Example:

#339

---

### 3. short-description

A short kebab-case description of the change.

Rules:

- Use lowercase letters
- Separate words with `-`
- Keep it concise but meaningful
- Avoid overly long descriptions

Example:

recommend-place-by-embedding

---

## Complete Example

feat/#339-recommend-place-by-embedding

Meaning:

- feat → this branch introduces a new feature
- #339 → related GitHub issue number
- recommend-place-by-embedding → short description of the feature

---

## Additional Examples

fix/#412-handle-null-review-summary  
refactor/#287-extract-retrieval-text-builder  
docs/#315-update-recommendation-architecture  
test/#421-add-similarity-service-tests  
chore/#398-update-openai-sdk

---

## Important Rules

- Always include the issue number.
- Use kebab-case for the description.
- Keep branch names readable and concise.
- The branch type must reflect the main purpose of the change.

---

## Steps

1. Identify the related GitHub issue number.
2. Determine the branch type (feat, fix, refactor, etc.).
3. Write a short kebab-case description of the change.
4. Generate the branch name using the required format.
5. Create the branch.

---

## Example Command

git checkout -b feat/#339-recommend-place-by-embedding