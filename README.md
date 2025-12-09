![Java](https://img.shields.io/badge/java-%23ED8B00.svg?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring](https://img.shields.io/badge/spring-%236DB33F.svg?style=for-the-badge&logo=spring&logoColor=white)

# Local Coding Assistant

Local-first coding assistant that runs on your machine and talks only to Ollama-served models. The goal is to deliver a CLI experience with editing, review, search, and git-aware tools—similar to ChatGPT Codex, Gemini CLI, and Claude Code — without any cloud dependency. Embabel provides the agent runtime, Spring Boot hosts it, and Spring Shell exposes the commands.

## What this project is today
- Spring Boot entry point with Embabel agents enabled (`src/main/java/se/alipsa/lca/LocalCodingAssistantApplication.java`).
- Ollama-first configuration using `qwen3-coder:30b` (see `src/main/resources/application.properties`).
- Helper scripts for launching the shell (`scripts/shell.sh`) and installing a DeepSeek model locally (`deepseek.sh`).
- Documentation stubs for Ollama setup (`docs/llm-docs.md`) and A2A interop (`docs/a2a.md`).

## Getting started
1. Install Java 21 and ensure the Ollama daemon is running.
2. Pull a code-capable model. `./deepseek.sh` will install an appropriate `deepseek-coder` variant based on your RAM, or run `ollama pull deepseek-coder:6.7b`.
3. Start the interactive shell: `./scripts/shell.sh`. The script sets `AGENT_APPLICATION` and launches Spring Shell with Embabel agents loaded.
4. Begin adding agents and tools under `src/main/java/se/alipsa/lca` to shape the coding workflows (editing, reviewing, searching, git operations).

## Configuration
- Update `spring.ai.ollama.base-url` and `embabel.models.default-llm` in `src/main/resources/application.properties` to point at your Ollama host and preferred model.
- Tune agent behavior via:
  - `assistant.llm.model`, `assistant.llm.temperature.craft`, `assistant.llm.temperature.review`
  - `snippetWordCount`, `reviewWordCount`
- For agent-to-agent (A2A) interoperability, follow `docs/a2a.md`.

## Roadmap focus
- Rich editing and review loops driven from the CLI.
- Code search and repository-aware context with git operations.
- Opinionated prompts and workflows tuned for local development sessions.

## License

Apache License, Version 2.0. See `LICENSE` for details.
