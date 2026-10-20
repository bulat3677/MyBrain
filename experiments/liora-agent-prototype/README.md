# Liora Core Agent Prototype

This is an isolated, provider-neutral prototype. It does not change MyBrain's existing application modules.

## Included

- `ModelProvider`: provider adapter contract.
- `ToolRegistry`: unique tool names and model-facing descriptors.
- `ToolPolicy`: execution authorization boundary.
- `AgentLoop`: bounded model/tool/result loop.
- Tests for tool round-trip, unknown tools, policy denial, call limits and blank input.

## Run

From this directory, with JDK 17+ and Gradle installed:

```bash
gradle test
```

No live model credentials are required. Tests use a deterministic fake provider.

## Current prototype limits

- Tool arguments are a simple `Map<String, String>`, not a full JSON Schema.
- Provider calls are synchronous.
- There is no persistent audit store, retry policy, cancellation, streaming, or human-approval workflow yet.
- The policy is injected by the caller. Production code must use a deny-by-default policy and authorize each action independently of model output.

This directory is intentionally standalone so the core contracts can be tested before wiring a provider adapter or app-specific data access.
