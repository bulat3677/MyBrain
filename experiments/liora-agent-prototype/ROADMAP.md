# Liora Core Implementation Roadmap

## Operating rules

- Keep experiments isolated from MyBrain production modules until the prototype is reviewed.
- Prefer small, reversible changes with tests.
- Never place API keys, personal data, or real credentials in source code, logs, or fixtures.
- Treat model output and retrieved documents as untrusted input.
- Require explicit policy authorization for every tool call. Destructive or externally visible actions need human approval.
- Record each milestone as confirmed, in progress, blocked/unknown, and next step.
- Review GPL-3.0 obligations before reusing or distributing code outside this repository.

## Phase 0: Agent-loop baseline

**Status:** CI passed for the initial prototype on 2026-10-20.

Included:
- Provider-neutral model contract.
- Tool registry with unique tool names.
- Policy gate before execution.
- Bounded turn and tool-call counts.
- Deterministic unit tests.

Evidence: GitHub Actions workflow `.github/workflows/liora-agent-prototype.yml` ran successfully for the prototype branch. This validates the current unit-test suite, not real-provider behavior or production readiness.

## Phase 1: Harden the core contracts

Implement and test:
- Structured tool arguments with schema validation.
- Stable error categories that do not leak secrets or stack traces to the model.
- Tool-call ID and duplicate-call validation.
- Timeouts/cancellation boundaries and maximum output sizes.
- A deny-by-default policy with explicit approval hooks for sensitive actions.
- Tests for malformed arguments, provider exceptions, multiple tool calls, and boundary conditions.

Acceptance: deterministic tests pass; no real external side effects are needed.

## Phase 2: Add one provider adapter

Choose one provider already supported by the host application or one explicitly configured by the developer. Keep credentials outside the repository, preferably in environment variables or a secret store.

Test in layers:
1. Adapter unit tests using mocked HTTP responses.
2. Contract tests for text response, tool call, tool result, malformed response, timeout, and rate limit.
3. Optional manual smoke test using a developer-owned key.

Acceptance: the adapter maps provider-specific messages and tool calls into the core contract without leaking credentials. Do not claim all providers are supported because one works.

## Phase 3: Tool safety and audit

Implement:
- Tool capability declarations and least-privilege scopes.
- Input validation and output redaction.
- Structured audit events: request ID, tool name, decision, duration, outcome; avoid logging secrets or full private content by default.
- Human confirmation for destructive, financial, account, device-control, or externally visible actions.
- Explicit timeout, retry, and idempotency policies.

Acceptance: tests prove denied tools never execute, approvals are required for sensitive tools, and logs contain no test secrets.

## Phase 4: Memory and RAG

Separate:
- Conversation context (short-lived).
- Project memory (approved, versioned notes).
- Knowledge documents (source-linked retrieval).
- Private/personal data (separate access policy).

Implement source citations, deletion/retention rules, access boundaries, and prompt-injection resistance for retrieved text.

Acceptance: retrieval tests verify relevant source selection, source attribution, permission filtering, and resistance to instructions embedded in retrieved documents.

## Phase 5: Router and integration

Add a routing policy based on task type, provider capability, latency/cost constraints, and availability. Start with deterministic rules before adding model-based routing.

Integrate with application modules only after the standalone core and adapter tests pass. Keep rollback straightforward and do not change the default branch until reviewed.

Acceptance: integration tests cover provider fallback, unavailable providers, denied tools, and safe failure.

## Current checkpoint

- Confirmed: initial agent prototype exists on `liora-core/agent-prototype`.
- Confirmed: GitHub Actions unit-test job passed for the current prototype commit.
- Confirmed: latest CI run passed after Phase 1 hardening changes; unit tests now include provider-error sanitization.
- In progress: Phase 1 contract hardening, including argument schema validation, bounded argument sizes, and approval-hook design.
- Unknown: live-provider tool calling, latency, costs, rate limits, and production behavior have not been tested.
- Next milestone: finish deterministic Phase 1 safety tests. Provider adapter remains deferred until the core contract is reviewed.
