# Create GitHub Issue

Issues must be written in **Korean**. Use templates from `.github/ISSUE_TEMPLATE`.

## Defaults
- **Assignee**: `uykm` (always set automatically)
- **Type & Label**: infer automatically from context — do not ask the user

## Issue Types

| Type | Prefix | Label | When to use |
|------|--------|-------|-------------|
| Feature | `[Feat]` | `💭 FEAT` | new features, new APIs, new behavior |
| Bug | `[Fix]` | `🐞 BUG` | bugs, errors, incorrect behavior |
| Refactor | `[Refactor]` | `🪄 REFACTOR` | code cleanup, structural improvement |
| Chore | `[Chore]` | `⚙️ CHORE` | dependency updates, build config, CI/CD, maintenance |
| Docs | `[Docs]` | `📖 DOCS` | documentation updates |

## Templates

**Feature:**
```
💬 이슈 설명
<description>

✅ 작업할 내용
- [ ] task 1
```

**Bug:**
```
💬 이슈 설명
<description>

🔨작업 내용
- [ ] task 1
```

**Refactor:**
```
🎨 리팩토링 이슈 설명
<description>

✅ 작업할 내용
- [ ] task 1
```

**Chore:**
```
💬 이슈 설명
<description>

✅ 작업할 내용
- [ ] task 1
```

**Docs:**
```
💬 이슈 설명
<description>

✅ 작업할 내용
- [ ] task 1
```

## Steps
1. Determine type (bug / feature / refactor).
2. Draft the issue in Korean using the matching template.
3. Show draft and confirm before creating.
4. Create with `gh issue create --title "..." --label "..." --assignee "uykm" --body "..."`.

## Safety Checks
- Verify repository owner/name before running `gh issue create`.
- Confirm title prefix, label, and body match the selected template.
