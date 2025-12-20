![Java](https://img.shields.io/badge/java-%23ED8B00.svg?style=for-the-badge&logo=openjdk&logoColor=white)
![Groovy](https://img.shields.io/badge/groovy-4298B8.svg?style=for-the-badge&logo=apachegroovy&logoColor=white)
![Spring](https://img.shields.io/badge/spring-%236DB33F.svg?style=for-the-badge&logo=spring&logoColor=white)
![Ollama](https://img.shields.io/badge/ollama-000000.svg?style=for-the-badge&logo=ollama&logoColor=white)

# Local Coding Assistant

Local-first coding assistant that runs on your machine and talks only to Ollama-served models. The goal is to
deliver a CLI experience with editing, review, search, and git-aware tools—similar to ChatGPT Codex, Gemini CLI,
and Claude Code — without any cloud dependency. Embabel provides the agent runtime, Spring Boot hosts it, and
Spring Shell exposes the commands.

## What this project is today
- Spring Boot entry point with Embabel agents enabled
  (`src/main/java/se/alipsa/lca/LocalCodingAssistantApplication.java`).
- Ollama-first configuration using `qwen3-coder:30b` (see `src/main/resources/application.properties`).
- Helper scripts for launching the shell (`scripts/shell.sh`) and installing a DeepSeek model locally (`deepseek.sh`).
- Documentation stub for Ollama setup (`docs/llm-docs.md`).

## Getting started
1. Install Java 21 and ensure the Ollama daemon is running.
2. Pull a code-capable model. `./models.sh` will install the appropriate models.
3. Start the interactive shell: `./scripts/shell.sh`. The script sets `AGENT_APPLICATION` and launches Spring Shell
   with Embabel agents loaded.
4. Begin adding agents and tools under `src/main/java/se/alipsa/lca` to shape the coding workflows
   (editing, reviewing, searching, git operations).

## Configuration
- Update `spring.ai.ollama.base-url` and `embabel.models.default-llm` in
  `src/main/resources/application.properties` to point at your Ollama host and preferred model.
- Tune agent behavior via:
  - `assistant.llm.model`, `assistant.llm.temperature.craft`, `assistant.llm.temperature.review`
  - `assistant.llm.max-tokens`, `assistant.system-prompt`
  - `snippetWordCount`, `reviewWordCount`

## Commands overview
Detailed command documentation lives in `docs/commands.md`, with workflows in `docs/workflows.md`.

- `chat` (`/chat`): Send prompts. Options: `--persona`, `--session`, `--model`, `--temperature`,
  `--review-temperature`, `--max-tokens`, `--system-prompt`.
- `review` (`/review`): Review code with structured Findings/Tests output. Options: `--paths`, `--staged`,
  `--min-severity`, `--no-color`, `--log-review`, plus model/temperature overrides.
- `reviewlog` (`/reviewlog`): Show recent review entries. Options: `--min-severity`, `--path-filter`, `--limit`,
  `--page`, `--since`, `--no-color`.
- `search` (`/search`): Web search through the agent tool. Options: `--limit`, `--provider`, `--timeout-millis`,
  `--headless`, `--enable-web-search`.
- `codesearch` (`/codesearch`): Ripgrep-backed repo search. Options: `--paths`, `--context`, `--limit`, `--pack`,
  `--max-chars`, `--max-tokens`.
- `edit` (`/edit`): Open `$EDITOR` to draft prompts. Options: `--seed`, `--send`, `--session`, `--persona`.
- `paste` (`/paste`): Paste multiline input (end with `/end`). Options: `--content`, `--end-marker`, `--send`,
  `--session`, `--persona`.
- `status` (`/status`): Git status. Options: `--short-format`.
- `diff` (`/diff`): Git diff. Options: `--staged`, `--context`, `--paths`, `--stat`.
- `gitapply` (`/gitapply`): Apply a patch via git. Options: `--patch-file`, `--cached`, `--check`, `--confirm`.
- `stage` (`/stage`): Stage files or hunks. Options: `--paths`, `--file`, `--hunks`, `--confirm`.
- `commit-suggest` (`/commit-suggest`): Draft a commit message. Options: `--session`, `--model`, `--temperature`,
  `--max-tokens`, `--hint`.
- `git-push` (`/git-push`): Push with confirmation. Options: `--force`.
- `model` (`/model`): List or set models. Options: `--list`, `--set`, `--session`.
- `health` (`/health`): Check Ollama connectivity.
- `run` (`/run`): Execute a command with timeout and truncation. Options: `--timeout-millis`, `--max-output-chars`,
  `--confirm`, `--agent-requested`.
- `apply` (`/apply`): Apply unified diff patches. Options: `--patch-file`, `--dry-run`, `--confirm`.
- `applyBlocks` (`/applyBlocks`): Apply Search-and-Replace blocks. Options: `--blocks`, `--blocks-file`, `--dry-run`,
  `--confirm`.
- `revert` (`/revert`): Restore from the latest patch backup. Options: `--dry-run`.
- `context` (`/context`): Show targeted edit context. Options: `--start`, `--end`, `--symbol`, `--padding`.
- `tree` (`/tree`): Show repository tree (respects `.gitignore`). Options: `--depth`, `--dirs-only`, `--max-entries`.

## Quickstart examples
Full end-to-end flows are in `docs/quickstart.md`.

Edit flow:
```
/context --file-path src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy \
  --symbol review
/chat --prompt "Update the review output header to include timestamps."
/applyBlocks --file-path src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy \
  --blocks "<blocks>"
```

Review flow:
```
/review --paths src/main/groovy --prompt "Look for error handling and logging gaps"
/reviewlog --min-severity MEDIUM --limit 3
```

Search + git flow:
```
/codesearch --query "applyPatch" --paths src/main/groovy
/diff --staged
/stage --paths src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy
/commit-suggest --hint "UX polish"
```

## Roadmap focus
- Rich editing and review loops driven from the CLI.
- Code search and repository-aware context with git operations.
- Opinionated prompts and workflows tuned for local development sessions.

## License

MIT License. See `LICENSE` for details.
