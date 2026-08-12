---
name: write-docs
description: Write a new doc or rewrite an existing one under docs/technical_decisions/ or docs/learning/ — dense, engineer-facing, and portable enough that someone who has never opened this repo can follow the decision or concept. Strips implementation detail (class/method names, file paths + line numbers, session artifacts like trace IDs) while keeping concrete decision parameters (thresholds, status codes, costs). Use when asked to write, rewrite, simplify, tighten, or clean up a doc under docs/.
---

Write or rewrite a doc under `docs/technical_decisions/` or `docs/learning/` in this repo's
established style: dense, engineer-facing, and portable — understandable without having read the
code, not just without having read the conversation that produced it.

## 1. Read sibling docs first

Before writing, read 2-3 existing docs in the *same* directory (technical_decisions or learning) to
match structure and tone. Don't invent a new shape per file — see §4 for the two established
shapes.

## 2. Pick the right number, check for collisions

Both directories number files sequentially and independently. Before claiming the next number:

- `ls` the directory to find the highest existing number.
- Grep the repo for any dangling reference to a number that looks reserved but unwritten (a code
  comment or doc pointing at `docs/technical_decisions/N-something.md` that doesn't exist yet) —
  skip that number for anything unrelated, since a reader following that reference would land on
  the wrong content.
- If you spot two files with the same or near-identical content under different numbers while
  working in this area, flag it — it's very likely a leftover from a renumbering mistake. Confirm
  the content actually matches, then remove the duplicate with `git rm`.

## 3. Strip implementation detail — the core rule

This is the most important, most easily-skipped step. A finished doc should be understandable by
someone who has never opened this repo — not because they don't need context, but because the
decision and its reasoning shouldn't depend on this codebase's internal naming.

**Remove:**
- Class/method/function names (`ExtractTokenAuthorizerHandler`, `TracingUtils.currentTraceId()`) —
  describe the *role* instead ("the custom authorizer", "the backend function").
- File paths and line numbers (`functions/src/.../Foo.java:12`) — if the location matters, say so
  in words ("bounded before X runs"); a cited line number just drifts out of date.
- Specific library/tool/vendor names when they're incidental to the point (a framework or utility
  name), unless the doc's whole point is that specific tool's behavior.
- Session-specific artifacts: trace IDs, request IDs, timestamps, exact one-off numbers from a
  single test run — dead data to a future reader, not a reusable fact.
- Narration of how the decision or finding was reached ("we added a helper, then removed it once
  we noticed X") — keep only the final understanding, not the path to it.

**Keep:**
- The actual decision and the concrete reason behind it.
- Numbers that *are* the decision's content: thresholds, status codes, retention windows, cost
  figures, quotas — a reader needs these, and they aren't tied to this codebase's structure.
- Non-obvious constraints and gotchas, stated in plain language.
- Source links for claims that could go stale (pricing, quotas, library behavior).

## 4. Structure

**Technical decision** (`docs/technical_decisions/`):
```
# Decision: <short title>

## Context
<what question or problem forced a decision>

## Options considered
<one entry per option, brief pro/con each>

## Decision
<what was chosen and why, plus how any workaround/mitigation actually works, in plain terms>
```

**Learning doc** (`docs/learning/`):
```
# <Concept title>

<one-line description of what this explains>

---

## <Numbered sections, one concept or claim per section>

## References
<reference-style links: [text][ref] in the body, [ref]: url at the bottom>
```

Keep headings shallow — two levels is usually enough. Prefer bullet lists over prose paragraphs
when enumerating facts, options, or steps.

## 5. Verify before finishing

Re-read the result and confirm:
- No class/method name, file path + line number, or session-specific artifact survived.
- Every remaining claim is either general/portable, or a decision parameter worth keeping as a
  concrete number.
- Nothing load-bearing was cut — a future engineer could still answer "what was decided, why, and
  what's the non-obvious gotcha" from the doc alone.

Docs-only change — no build or test step applies. For a large multi-file doc audit rather than one
or two files, consider delegating to the `docs-writer` subagent instead of working inline.
