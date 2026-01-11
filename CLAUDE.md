# Instructions for Claude Code

## Project Context
This is the Local Coding Assistant (LCA) project - a local-first AI coding assistant built with Groovy, Spring Boot, and Embabel.

## Critical: Read AGENTS.md First
**ALWAYS read and follow the AGENTS.md file in the project root before making any code changes.**

The AGENTS.md file contains essential project-specific rules including:
- Programming language and version (Groovy 5.0.3)
- Code style guidelines (2-space indentation, 120 char line length)
- Required annotations (@CompileStatic)
- Testing framework (Spock 2.4)
- Build and test requirements

## Workflow
1. Read AGENTS.md to understand project conventions
2. Follow all rules specified in AGENTS.md
3. After implementing features or bugfixes, run `./mvnw test` to ensure no regressions
4. Write Spock tests for all new functionality

## Important Notes
- This project uses local LLMs only (Ollama with qwen3-coder:30b)
- The project is language-agnostic in its AI prompts but the codebase itself is Groovy
- Use British English spelling in documentation
- Avoid deprecated APIs when possible
