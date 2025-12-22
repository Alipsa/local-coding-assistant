# AGENTS.md

`AGENTS.md` lets you define project-specific rules and context for the assistant.
If the file exists in the repository root, its contents are appended to the
system prompt for CLI and REST agent workflows.

Format:
- Follow the AGENTS.md specification from https://agents.md/ and
  https://github.com/agentsmd/agents.md.
- Use plain Markdown with clear sections (rules, constraints, environment).

Example `AGENTS.md`:
```
# Project Rules
- Use Groovy 5.0.3 and @CompileStatic where possible.
- Keep lines under 120 characters and indent with 2 spaces.
- Add Spock 2.4 tests for new behavior.

# Environment
- JVM 21
- No network calls outside localhost
```

Notes:
- The file is read from the current working directory where the process runs.
- If `AGENTS.md` is missing or empty, no extra guidance is added.
