# Intent Router Plan

## Goals
- Add a first-class `/plan` command.
- Introduce a lightweight intent router for natural language input.
- Do not apply routing in batch mode.
- If input starts with `/`, skip routing and execute as-is.

## Plan
1. Add a first-class `/plan` shell command
   - Implement in `src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy`.
   - Route to the chat agent with a fixed planning prompt and the same session/options handling as `/chat`.
   - Return a structured, numbered list where each step begins with a command and a short explanation.
   - Keep the planning prompt tight and list the allowed commands; no execution should happen from `/plan`.
   - Consider a small internal helper method so `/plan` can be unit-tested with a mocked agent call.
   - Ensure Groovy 5.0.3 with `@CompileStatic` and follow existing style.

2. Create the intent router component
   - Add a small `IntentRouterAgent` to call a lightweight LLM and return a strict JSON result.
   - Provide a parser/validator that only allows known `/` commands and rejects unknowns.
   - Use a confidence threshold (for example 0.8) to fall back to `/chat` when the parse is uncertain.
   - Include an optional explanation field to help with debugging and logging.
   - Use a strict system prompt that only allows JSON output and lists permitted commands.
   - Prefer a very small routing model (for example `phi3`, `tinyllama`, or `qwen2:0.5b`) if available;
     fall back to `gpt-oss:20b` when the lightweight model is not installed.
   - Define a typed result schema, e.g. `IntentRouterResult` with `commands`, `confidence`, `explanation`,
     where each command has `name` and `args`.
   - On invalid JSON, retry once with a stricter prompt and `temperature=0`; on repeated failure, use `/chat`.
   - If the router is unsure, prefer fewer commands with lower confidence.

3. Wire routing into interactive input only
   - Update `CommandInputNormaliser`/`SlashCommandShellRunner` to call the router only when:
     - The input does not start with `/`.
     - The input is not auto-paste content.
     - The shell is in interactive mode (not batch).
   - Keep batch mode unchanged.
   - If the router proposes commands that may be destructive (for example `/edit`, `/apply`, `/gitapply`,
     `/git-push`, `/run`), prompt for confirmation before execution unless the command already enforces it.
   - Show a short preview when routing yields multiple commands so the user can see the interpretation.
   - Add an explicit `/route` command for forcing routing of a specific input (useful for debugging).
   - Keep the original user message available for `/plan` context if the router chains `/review` then `/plan`.

4. Configuration and model selection
   - Add properties:
     - `assistant.intent.enabled`
     - `assistant.intent.model`
     - `assistant.intent.fallback-model`
     - `assistant.intent.temperature`
     - `assistant.intent.max-tokens`
     - `assistant.intent.allowed-commands`
     - `assistant.intent.destructive-commands`
     - `assistant.intent.confidence-threshold`
   - Default to `gpt-oss:20b`, with override support.
   - Default router temperature to 0.0 and a small max-tokens value (for example 128-256).

5. Tests
   - Spock tests for routing decisions, JSON validation, and confidence fallbacks.
   - Tests ensuring `/`-prefixed input bypasses routing.
   - Tests confirming batch mode does not route.
   - Unit tests for JSON parsing/validation with mocked router responses (valid, invalid, unknown commands).
   - Tests for destructive command confirmation and cancellation behaviour.

6. Documentation
   - Update `docs/commands.md` and `docs/workflows.md` with `/plan` and routing behaviour.
   - Add configuration notes in `README.md`.
   - Include a small example showing natural language -> routed commands and the confirmation flow.
