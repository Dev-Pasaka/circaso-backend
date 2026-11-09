# Asha's app guide

`app-guide.md` is how Asha knows the Circaso app: where things are and how to do them, step by step, in the user's
words. `AshaPrompt` loads it into her fixed system prompt (text and voice), where the provider's prompt cache keeps it
cheap: it is read from the cache on every call after the first, so its size costs little. If it grows past about
4,000 words, move the less-asked sections behind a lookup tool instead of making the prompt longer.

**Update it whenever a user-facing feature ships or changes** (a new screen, a renamed button, a flow that works
differently, something that was "not available yet" and now is). Write from the app as built, not from the design:
the design canvas is ahead of the app in places. Mark anything not built as "not available yet", so she says so
instead of inventing it. Then bump `AshaPrompt.VERSION`, since the audit trail records which prompt wrote each
answer.

`AppGuideTest` checks that the guide loads, is in both prompts and keeps its key flows.
