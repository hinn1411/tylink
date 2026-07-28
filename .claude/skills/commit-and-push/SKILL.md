---
name: commit-and-push
description: Stage changes, write a commit message that follows this repo's type-prefixed convention (feature/test/refactor/optimize/fix/docs/chore), commit, and push to the tracking remote branch. Use when the user says "commit and push", "commit this", "push my changes", or similar.
---

Commit and push the current changes in this repo (tylink). This repo enforces commit
message format via a `commit-msg` git hook (`compilerla/conventional-pre-commit`,
configured in `.pre-commit-config.yaml`), so a non-conforming message will be rejected
automatically — but compose it correctly up front rather than relying on trial and error.

## 1. Inspect before staging

Run `git status` and `git diff` (and `git diff --cached` if anything's already staged)
to see exactly what changed. Never blindly run `git add -A` or `git add .` — add
specific files by name. If anything looks like it could contain a secret or credential
(even from an innocuous-looking filename), read its contents before staging it.

## 2. Compose the commit message

Format: `type: description` or `type(scope): description`.

Allowed types (nothing else passes the hook):
- `feature` — new functionality
- `test` — adding/updating tests
- `refactor` — restructuring without behavior change
- `optimize` — performance improvements
- `fix` — bug fixes
- `docs` — documentation only
- `chore` — tooling, config, maintenance

Pick the type that matches the actual diff, not the broadest one that would technically
pass. Keep the description short and focused on *why*, not a restatement of the diff.

## 3. Commit

Run `git commit -m "type: description"`. The pre-commit hooks run automatically:
- File-hygiene hooks (trailing whitespace, end-of-file, YAML/JSON/TOML validity, etc.)
  may auto-fix files in place — if the commit fails because a file was modified,
  `git add` the fixed file(s) and commit again with the same message.
- The `conventional-pre-commit` hook validates the message itself and will reject it
  outright (no auto-fix) if the type or format is wrong — fix the message and retry.

## 4. Push

- If the current branch already has an upstream, `git push`.
- If not, `git push -u origin <current-branch-name>` to set the tracking branch.
- Never force-push unless the user explicitly asked for that.

## 5. Report

Tell the user the final commit hash, the branch pushed, and a one-line summary of what
changed. Do not open a pull/merge request as part of this skill — that step is handled
separately.
