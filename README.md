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
2. Pull a code-capable model. `./models.sh` will install the appropriate models.
3. Start the interactive shell: `./scripts/shell.sh`. The script sets `AGENT_APPLICATION` and launches Spring Shell with Embabel agents loaded.
4. Begin adding agents and tools under `src/main/java/se/alipsa/lca` to shape the coding workflows (editing, reviewing, searching, git operations).

## Configuration
- Update `spring.ai.ollama.base-url` and `embabel.models.default-llm` in `src/main/resources/application.properties` to point at your Ollama host and preferred model.
- Tune agent behavior via:
  - `assistant.llm.model`, `assistant.llm.temperature.craft`, `assistant.llm.temperature.review`
  - `assistant.llm.max-tokens`, `assistant.system-prompt`
  - `snippetWordCount`, `reviewWordCount`
- For agent-to-agent (A2A) interoperability, follow `docs/a2a.md`.

## Spring Shell commands
- `chat` (`/chat`): Send prompts; supports personas (CODER/ARCHITECT/REVIEWER), session-scoped model/temperature/max tokens, and optional system prompt overrides.
- `review` (`/review`): Review code with the configured review temperature/model and structured Findings/Tests output.
- `search` (`/search`): Use the agent’s web search tool; limit results with `--limit`.
- `paste` (`/paste`): Paste multiline input (end with `/end`) and optionally forward it to `chat`.
- `edit` (`/edit`): Open `$EDITOR` with seed text to draft prompts; optionally send the saved text to `chat`.

## Roadmap focus
- Rich editing and review loops driven from the CLI.
- Code search and repository-aware context with git operations.
- Opinionated prompts and workflows tuned for local development sessions.

## License

MIT License. See `LICENSE` for details.
