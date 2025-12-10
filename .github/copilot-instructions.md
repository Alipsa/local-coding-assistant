# Copilot Instructions for Local Coding Assistant

## Project Overview
Local-first coding assistant that runs on your machine and talks only to Ollama-served models. The goal is to deliver a CLI experience with editing, review, search, and git-aware tools—similar to ChatGPT Codex, Gemini CLI, and Claude Code — without any cloud dependency. Embabel provides the agent runtime, Spring Boot hosts it, and Spring Shell exposes the commands.

## Technology Stack
- **Language**: Groovy 5.0.3
- **Framework**: Spring Boot 3.5.x
- **Agent Runtime**: Embabel 0.3.0+
- **LLM Backend**: Ollama (local models only, no cloud)
- **Default Model**: `qwen3-coder:30b`
- **JVM**: Java 21
- **Build Tool**: Maven
- **Testing Framework**: Spock 2.3

## Coding Conventions

### General Guidelines
- Use `@CompileStatic` annotation on Groovy classes where possible for better performance and type safety
- Indent with 2 spaces (no tabs)
- Maximum line length is 120 characters
- Follow existing code style and conventions in the codebase

### Code Organization
- Main application code: `src/main/groovy/se/alipsa/lca/`
- Test code: `src/test/groovy/se/alipsa/lca/`
- Resources and prompts: `src/main/resources/`

## Testing Guidelines
- Write unit tests for all new functionality using Spock 2.3
- Test files should be in Groovy (`.groovy` extension)
- Name test files with the `Spec` suffix (e.g., `CodingAssistantAgentSpec.groovy`)
- Follow the existing test patterns in the codebase

## Project Structure
```
src/main/
├── groovy/se/alipsa/lca/        # Main application code
│   ├── agent/                   # Agent implementations
│   ├── api/                     # API controllers
│   └── LocalCodingAssistantApplication.java  # Spring Boot entry point
├── resources/
│   ├── application.properties   # Configuration (Ollama URL, default model)
│   └── prompts/                 # Agent prompts and templates
```

## Key Configuration Files
- `application.properties`: Configure Ollama base URL (`spring.ai.ollama.base-url`) and default LLM (`embabel.models.default-llm`)
- `pom.xml`: Maven dependencies and build configuration

## Development Workflow
1. Ensure Ollama daemon is running locally
2. Use Spring Shell for interactive testing (`./scripts/shell.sh`)
3. All LLM interactions must go through Ollama (no cloud services)
4. Focus on CLI-driven workflows for editing, reviewing, and searching code

## Important Notes
- **Local-first**: Never add dependencies or features that require cloud services
- **Ollama-only**: All AI/LLM functionality must use local Ollama models
- **CLI-focused**: Prioritize command-line interface and developer experience
- **Git-aware**: Tools should be aware of git repository context when relevant
