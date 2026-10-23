---
name: circaso-backend-docs
description: The Circaso backend spec (architecture, decisions, module designs for identity, users and assistant) and the working rules for building it. Use when designing, writing or reviewing any Circaso backend code (Kotlin/Ktor modules, Kandra/ScyllaDB, Redis Streams, gateway, integrations), when adding a module or endpoint, or when a question depends on a documented decision or open question.
---

# Circaso backend docs

The spec for the Circaso backend, exported from the Claude Docs document "Circaso Backend — Architecture & Plan" (2026-10-22). Read the files below as needed; they are large, so open only what the task touches.

## Read in this order

1. `references/architecture.md`: principles, project structure, module anatomy, results and errors, how modules talk, gateway, infrastructure and Kandra, logging, observability, integrations, realtime, conventions.
2. `references/decisions.md`: every decision with its reason, and the open questions.
3. `references/modules/README.md`: module map and the checklist for adding a module.
4. The module you are working on:
   - `references/modules/identity.md` and `references/modules/identity-signin-flow.md` (screens each endpoint serves)
   - `references/modules/users.md`
   - `references/modules/assistant.md` (design only; not to be built until asked)
5. `references/working-rules.md`: build order (M0–M5), how every endpoint is built, easy-to-get-wrong rules, and what "done" means.

## Ground rules

- Routes are grouped by path with `route("/…") { }`; endpoints carry only their own segment, no leading slash (see working-rules.md).

- Pasaka owns the architecture, standards and code review. Never change the architecture in code; if the spec is wrong or missing something, stop and ask or write the question down.
- Open questions in `decisions.md` stay open: build behind an interface or config value and leave `// TODO(decision: <question>)`.
- Report doc gaps in the summary with the exact place.
