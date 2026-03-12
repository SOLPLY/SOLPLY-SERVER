Branch Naming Guide

When creating a new branch for development, follow the naming convention
below to ensure consistency across the repository.

Branch Format

/#-

Example: feat/#339-recommend-place-by-embedding

Components

1. type

Indicates the type of change.

Common values: - feat – New feature - fix – Bug fix - refactor – Code
refactoring without behavior change - docs – Documentation updates -
test – Test-related changes - chore – Maintenance tasks (build,
dependency updates, etc.) - perf – Performance improvements

2. #

The GitHub issue number related to the work.

Always include the # prefix.

Example: #339

3.

A short kebab-case description of the change.

Rules: - Use lowercase letters - Separate words with “-” - Keep it
concise but meaningful - Avoid overly long names

Example: recommend-place-by-embedding

Complete Example

feat/#339-recommend-place-by-embedding

Meaning: - feat → this branch introduces a new feature - #339 → related
GitHub issue number - recommend-place-by-embedding → short description
of the feature

Additional Examples

fix/#412-handle-null-review-summary
refactor/#287-extract-retrieval-text-builder
docs/#315-update-recommendation-architecture
test/#421-add-similarity-service-tests chore/#398-update-openai-sdk

Important Rules

-   Always include the issue number.
-   Use kebab-case for the description.
-   Keep branch names readable and concise.
-   The branch type should reflect the main purpose of the change.
