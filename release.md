# Release Notes for the local-coding-assistant 

## Version 0.1.0 - Initial Release, 2025-12-10
- Created from the embabel java template project and translated to groovy.
- Setup basic structure for a local coding assistant using Embabel and Spring Boot.
- Agent hardening and prompts
  - 1.1 [x] Refine `se.alipsa.lca.agent.CodingAssistantAgent` personas/prompts toward repo-aware coding assistance (beyond short snippets).
  - 1.2 [x] Ensure actions use injected `Ai` (controller currently passes null) and propagate model/temperature options from config.
  - 1.3 [x] Add guardrails for word counts and result formatting (code + review sections) with tests.
  - 1.4 [x] Document/validate parameters via properties (`snippetWordCount`, `reviewWordCount`, default model).
  - 1.5 [x] Fix the REST controller’s null Ai handling and update the REST tests to cover full flows.