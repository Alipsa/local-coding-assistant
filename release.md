# Release Notes for the local-coding-assistant 

## Version 0.4.0 - Code review and suggestions, In progress

## Version 0.3.0 - Editing and patch application, Spring Shell CLI bridge, 2025-12-11
- 2.1 [x] Add Spring Shell commands that wrap the agent (e.g., `/chat`, `/review`, `/edit`, `/search`) and stream responses.
- 2.2 [x] Support multiline input, conversation/session state, and optional system prompt overrides.
- 2.3 [x] Provide CLI flags for model/temperature/max tokens and persist last-used options per session.
- 2.4 [x] Add smoke tests for command wiring and basic flows.
- 2.5 [x] Implement a "slash command" for input. The Challenge: Pasting multiline code into a CLI is painful.
  - /paste: Enters a mode specifically for pasting large buffers without triggering execution on newlines.
  - /edit: Opens the user's default $EDITOR (vim/nano/code), lets them type the prompt there, and sends it to the agent upon save/close. This is infinitely better than typing prompts in the shell.
  - 
- 3.1 [x] Extend `FileEditingTool` to support diff/patch application with backups and conflict detection.
- 3.2 [x] Enable targeted edits (line ranges, symbols) and contextual prompts for the agent.
- 3.3 [x] Add dry-run/confirm and revert options in the CLI.
- 3.4 [x] Cover patch application logic with unit tests on sample files.

## Version 0.1.0 - Initial Release, 2025-12-10
- Created from the Embabel java template project and translated to groovy.
- Setup basic structure for a local coding assistant using Embabel and Spring Boot.
- Agent hardening and prompts
  - 1.1 [x] Refine `se.alipsa.lca.agent.CodingAssistantAgent` personas/prompts toward repo-aware coding assistance (beyond short snippets).
  - 1.2 [x] Ensure actions use injected `Ai` (controller currently passes null) and propagate model/temperature options from config.
  - 1.3 [x] Add guardrails for word counts and result formatting (code + review sections) with tests.
  - 1.4 [x] Document/validate parameters via properties (`snippetWordCount`, `reviewWordCount`, default model).
  - 1.5 [x] Fix the REST controller’s null Ai handling and update the REST tests to cover full flows.