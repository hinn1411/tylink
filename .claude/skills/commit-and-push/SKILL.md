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

## 2. Check the branch matches the change

Branch names in this repo follow `type/kebab-case-description` (e.g. `feature/redirect-url`,
`docs/pre-commit-readme`) — a branch name is a claim about what belongs on it, so verify the
diff from step 1 actually backs that claim before committing to it:

- `master`/`main` has no specific feature purpose — always a mismatch. Never commit
  directly to master/main.
- Otherwise, compare the branch's `description` part (read as a short topic, e.g.
  `redirect-url` → redirect/decode functionality) against the diff (files touched, nature
  of the change). Only flag a *clear* topic divergence (e.g. a redirect-purposed branch
  carrying a list-urls feature diff) — a small adjacent change touched while doing the
  branch's main work is not a mismatch.

If there's a mismatch (including the master/main case), do **not** commit to the current
branch — sync and branch off master instead:
1. `git checkout master`
2. `git pull` — bring local master up to date with `origin/master`.
3. `git checkout -b <type>/<kebab-case-description>` (same type vocabulary as commit
   messages below, short topic inferred from the diff, matching existing naming style,
   e.g. `feature/list-user-urls`).

If step 1 has uncommitted changes and `git checkout master` refuses because they'd be
overwritten, `git stash -u` first and `git stash pop` after creating the new branch.

Tell the user a mismatch was detected and which new branch was created, before proceeding
with the rest of this skill on that new branch.

If there's no mismatch, proceed on the current branch as-is.

## 3. Compose the commit message

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

## 4. Commit

Run `git commit -m "type: description"`. The pre-commit hooks run automatically:
- File-hygiene hooks (trailing whitespace, end-of-file, YAML/JSON/TOML validity, etc.)
  may auto-fix files in place — if the commit fails because a file was modified,
  `git add` the fixed file(s) and commit again with the same message.
- The `conventional-pre-commit` hook validates the message itself and will reject it
  outright (no auto-fix) if the type or format is wrong — fix the message and retry.

## 5. Push

- If the current branch already has an upstream, `git push`.
- If not, `git push -u origin <current-branch-name>` to set the tracking branch. This is
  always the case if step 2 switched to a newly created branch.
- Never force-push unless the user explicitly asked for that.

## 6. Report

Tell the user the final commit hash, the branch pushed, and a one-line summary of what
changed. If step 2 created a new branch, call that out explicitly (old branch, new branch,
why). Do not open a pull/merge request as part of this skill — that step is handled
separately.
