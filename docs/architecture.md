# Local Coding Assistant - Architecture Documentation

## Table of Contents
1. [System Overview](#system-overview)
2. [Core Components](#core-components)
3. [Request Flow Diagrams](#request-flow-diagrams)
4. [Configuration & Model Strategy](#configuration--model-strategy)
5. [Architectural Patterns](#architectural-patterns)
6. [Security & Safety](#security--safety)
7. [Key Design Decisions](#key-design-decisions)

---

## System Overview

### Purpose
The **Local Coding Assistant (LCA)** is a local-first, privacy-focused AI coding assistant that runs entirely on your machine. 
It communicates only with Ollama-served models, ensuring zero cloud dependency. 
The project delivers a CLI experience similar to ChatGPT Codex, Gemini CLI, and Claude Code, but with complete local control.

### Key Characteristics
- **Zero Cloud Dependency**: All LLM processing happens locally via Ollama
- **Privacy-First**: No data leaves your machine
- **Interactive Shell**: Rich CLI with 50+ slash commands and natural language support
- **Agent-Driven**: Uses Embabel framework for intelligent task orchestration
- **Production-Ready**: Includes code generation, review, git operations, and safety guardrails

### Tech Stack
- **Language**: Java 21, Groovy 5.0.3
- **Framework**: Spring Boot 3.5.7
- **Agent Framework**: Embabel 0.3.1
- **LLM Provider**: Ollama (local)
- **Build Tool**: Maven 3.9.9+
- **REPL**: Custom JLine 3.x implementation
- **Testing**: Spock 2.4, JUnit 5

---

## Core Components

### 1. Embabel Agent Framework

#### What is Embabel?
Embabel is an AI agent runtime framework that powers the core agentic capabilities of LCA. 
It's a Spring Boot-compatible framework designed to build AI agents with goal-driven actions.

**Key Capabilities**:
- Agent orchestration and lifecycle management
- Persona-based prompt engineering via `RoleGoalBackstory`
- LLM abstraction through fluent `Ai` interface
- Integration with Spring Boot dependency injection

#### Integration
Embabel is activated via the `@EnableAgents` annotation in the main application class:

**File**: `src/main/groovy/se/alipsa/lca/LocalCodingAssistantApplication.groovy:5-6`
```groovy
@SpringBootApplication
@EnableAgents(loggingTheme = LoggingThemes.STAR_WARS)
class LocalCodingAssistantApplication {
    static void main(String[] args) {
        SpringApplication.run(LocalCodingAssistantApplication.class, args)
    }
}
```

This auto-configures Spring Boot to scan and register all `@Agent`-annotated classes as beans.

#### Main Agents

**1. CodingAssistantAgent**
- **File**: `src/main/groovy/se/alipsa/lca/agent/CodingAssistantAgent.groovy`
- **Purpose**: Core code generation, review, and file editing
- **Key Methods**:
  - `craftCode()` - Generate code with personas (CODER, ARCHITECT)
  - `reviewCode()` - Perform code reviews (REVIEWER, SECURITY_REVIEWER)
  - `writeFile()`, `replace()`, `applyPatch()` - File operations

**2. ChatAgent**
- **File**: `src/main/groovy/se/alipsa/lca/agent/ChatAgent.groovy`
- **Purpose**: Conversational interface for multi-turn interactions
- **Key Method**: `respond(Conversation, UserMessage, ChatRequest, Ai)`

**3. ReviewAgent**
- **File**: `src/main/groovy/se/alipsa/lca/agent/ReviewAgent.groovy`
- **Purpose**: Specialized code review with security focus
- **Delegation**: Uses `CodingAssistantAgent.reviewCode()`

**4. IntentRouterAgent**
- **File**: `src/main/groovy/se/alipsa/lca/intent/IntentRouterAgent.groovy`
- **Purpose**: Natural language understanding - maps user input to slash commands
- **Model**: Uses lightweight `tinyllama` for fast classification

#### Embabel API Examples

**Text Generation**:
```groovy
String review = ai
  .withLlm(reviewLlmOptions)
  .withPromptContributor(reviewer)
  .generateText(reviewPrompt)
```

**Structured Object Creation**:
```groovy
CodeSnippet snippet = ai
  .withLlm(craftLlmOptions)
  .withPromptContributor(template.persona)
  .createObject(craftPrompt, CodeSnippet)
```

**Conversational Response**:
```groovy
AssistantMessage reply = ai
  .withLlm(options)
  .withPromptContributor(template.persona)
  .withSystemPrompt(systemPrompt)
  .respond(conversation.messages)
```

#### Maven Dependencies
From `pom.xml`:
- `embabel-agent-starter` (0.3.1) - Core agent framework
- `embabel-agent-starter-shell` (0.3.1) - Shell integration support
- `embabel-agent-starter-ollama` (0.3.1) - Ollama LLM integration
- `embabel-agent-test` (0.3.1, test scope) - Testing utilities

---

### 2. Ollama LLM Provider

#### What is Ollama?
Ollama is a local LLM service that runs models on your machine. 
LCA exclusively uses Ollama for all AI capabilities, ensuring complete privacy and offline operation.

**Endpoint**: `http://localhost:11434`

#### Models Used

| Model             | Purpose                          | Temperature                 | Configuration                  |
|-------------------|----------------------------------|-----------------------------|--------------------------------|
| `qwen3-coder:30b` | Primary code generation & review | 0.7 (craft) / 0.35 (review) | `embabel.models.default-llm`   |
| `gpt-oss:20b`     | Fallback/cheaper model           | 0.35                        | `embabel.models.llms.cheapest` |
| `tinyllama`       | Intent routing (NLU)             | 0.0                         | `assistant.intent.model`       |

#### Integration Architecture

**Configuration**: `src/main/resources/application.properties:2`
```properties
spring.ai.ollama.base-url=http://localhost:11434
embabel.models.default-llm=qwen3-coder:30b
embabel.models.llms.best=qwen3-coder:30b
embabel.models.llms.cheapest=gpt-oss:20b
```

**Model Discovery**:
- **File**: `src/main/groovy/se/alipsa/lca/tools/ModelRegistry.groovy`
- **Mechanism**: Polls `/api/tags` endpoint with 4-second timeout
- **Caching**: Model list cached with 30-second TTL
- **Health Check**: Validates Ollama connectivity before requests

**Communication Flow**:
```
Embabel Ai Interface
  ↓
embabel-agent-starter-ollama
  ↓
Spring AI Ollama Client
  ↓
HTTP POST to http://localhost:11434/api/generate
  ↓
Ollama processes request with selected model
  ↓
Response streamed/returned as JSON
```

#### Model Fallback Strategy
From `ModelRegistry.resolveModel()`:
1. User-specified model (via `--model` flag)
2. Session-configured model
3. Default model (`qwen3-coder:30b`)
4. Fallback model (`gpt-oss:20b`)
5. Any available model from `modelRegistry.listModels()`

---

### 3. Spring Boot Application

#### Entry Point
**File**: `src/main/groovy/se/alipsa/lca/LocalCodingAssistantApplication.groovy`

Spring Boot serves as the application framework, providing:
- Dependency injection for agents, tools, and services
- Configuration management via `application.properties`
- Auto-configuration for Embabel agents
- REST API endpoints (optional)

#### Configuration Management
**File**: `src/main/resources/application.properties`

Key configuration categories:
- **LLM Settings**: Model selection, temperatures, max tokens
- **Intent Routing**: Enabled/disabled, confidence threshold, model
- **REPL**: Prompt format, history file location
- **Security**: Local-only mode, OIDC settings, rate limiting
- **Tools**: Web search, git settings, file exclusions

---

### 4. REPL/Shell Interface

#### Custom JLine REPL
LCA uses a custom JLine-based REPL that replaced the original Spring Shell implementation for better control and natural language interaction.

**Key Components**:

**1. JLineRepl**
- **File**: `src/main/groovy/se/alipsa/lca/repl/JLineRepl.groovy`
- **Features**: Line editing, history, auto-completion
- **Configuration**: `lca.repl.enabled=true` in `application.properties`

**2. ShellCommands**
- **File**: `src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy`
- **Size**: 95KB with 50+ slash commands
- **Key Commands**:
  - `/chat` - Interactive conversation
  - `/plan` - Generate implementation plans
  - `/review` - Code review with security analysis
  - `/applyBlocks` - Apply code changes
  - `/status`, `/diff`, `/stage`, `/commit-suggest` - Git operations
  - `/codesearch` - Repository-wide code search
  - `/config` - Session configuration

**3. IntentRouterAgent**
- **Purpose**: Maps natural language to structured commands
- **Example**: "review my code for bugs" → `/review --security`
- **Confidence Threshold**: 0.8 (configurable)
- **Temperature**: 0.0 for deterministic classification

**4. SessionState**
- **File**: `src/main/groovy/se/alipsa/lca/shell/SessionState.groovy`
- **Per-Session State**:
  - Model selection
  - Temperature settings
  - Conversation history
  - File paths tracked
  - Persona mode

#### Modes

**Interactive Mode** (Default):
```bash
$ java -jar local-coding-assistant-1.1.1.jar
lca> "create a metod in StatsCalculator to calculate fibonacci"
```

**Batch Mode**:
```bash
$ java -jar local-coding-assistant-1.1.1.jar \
  -c "/review --paths src; /diff --staged; /commit-suggest" \
  --yes
```

---

### 5. Tools Layer

Agents delegate to specialized tools for specific operations.

#### Key Tools

**1. FileEditingTool**
- **File**: `src/main/groovy/se/alipsa/lca/tools/FileEditingTool.groovy`
- **Capabilities**: Read, write, search/replace, patch application
- **Safety**: Automatic backups with timestamps, `/revert` support

**2. GitTool**
- **File**: `src/main/groovy/se/alipsa/lca/tools/GitTool.groovy`
- **Operations**: status, diff, stage, commit, push
- **Features**: Secret scanning before commits, confirmation prompts

**3. CodeSearchTool**
- **File**: `src/main/groovy/se/alipsa/lca/tools/CodeSearchTool.groovy`
- **Engine**: Ripgrep for fast regex search
- **Context**: Respects `.aiexclude`, returns context lines

**4. WebSearchTool**
- **File**: `src/main/groovy/se/alipsa/lca/tools/WebSearchTool.groovy`
- **Engines**: HTMLUnit, jsoup fallback
- **Caching**: 600-second TTL for summaries

**5. TokenEstimator**
- **File**: `src/main/groovy/se/alipsa/lca/tools/TokenEstimator.groovy`
- **Purpose**: Context budget management
- **Algorithm**: Approximate BPE token counting

**6. Security Tools**
- **SecretScanner**: `src/main/groovy/se/alipsa/lca/tools/SecretScanner.groovy`
- **ExclusionPolicy**: `src/main/groovy/se/alipsa/lca/tools/ExclusionPolicy.groovy`
- **CommandPolicy**: `src/main/groovy/se/alipsa/lca/tools/CommandPolicy.groovy`

**7. Additional Utilities**
- **ContextBudgetManager**: Token allocation
- **ContextPacker**: Efficient context formatting
- **TreeTool**: Repository structure visualization
- **ModelRegistry**: Ollama model discovery
- **LogSanitizer**: Secret redaction from logs

---

## Request Flow Diagrams

### 1. Chat Command Flow

```
User: /chat --prompt "explain this function"
  │
  ├─→ ShellCommands.chat()
  │     │
  │     ├─→ ensureOllamaHealth()
  │     │     └─→ HTTP GET http://localhost:11434/api/tags
  │     │
  │     ├─→ resolveModel(model)
  │     │     └─→ ModelRegistry: qwen3-coder:30b or fallback
  │     │
  │     ├─→ sessionState.update(model, temperature)
  │     │
  │     └─→ agentPlatform.executeProcess("lca-chat")
  │           │
  │           └─→ ChatAgent.respond()
  │                 │
  │                 ├─→ Build conversation context
  │                 │
  │                 └─→ ai.withLlm(options)
  │                       .withPromptContributor(persona)
  │                       .withSystemPrompt(systemPrompt)
  │                       .respond(messages)
  │                       │
  │                       └─→ Embabel → Ollama HTTP POST
  │                             │
  │                             └─→ http://localhost:11434/api/generate
  │                                   {
  │                                     "model": "qwen3-coder:30b",
  │                                     "prompt": "[combined prompt]",
  │                                     "temperature": 0.7
  │                                   }
  │
  └─→ Response parsed as AssistantMessage
        │
        ├─→ Add to conversation history
        │
        └─→ Display formatted output with ShellOutputStyler
```

**Key Files**:
- `src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy:320+`
- `src/main/groovy/se/alipsa/lca/agent/ChatAgent.groovy`

---

### 2. Intent Routing Flow

```
User: "create a metod in StatsCalculator to calculate fibonacci" (natural language)
  │
  ├─→ IntentCommandRouter.route()
  │     │
  │     └─→ IntentRouterAgent.route()
  │           │
  │           ├─→ Build routing prompt with:
  │           │     - Available commands (/chat, /plan, /review, etc.)
  │           │     - Expected JSON format
  │           │     - User input
  │           │
  │           ├─→ ai.withLlm(LlmOptions
  │           │       .withModel("tinyllama")
  │           │       .withTemperature(0.0))
  │           │     .generateText(routingPrompt)
  │           │       │
  │           │       └─→ Ollama processes with tinyllama
  │           │
  │           └─→ Response: {
  │                 "command": "/plan",
  │                 "confidence": 0.92,
  │                 "parameters": {
  │                   "prompt": "create a metod in StatsCalculator to calculate fibonacci"
  │                 }
  │               }
  │
  ├─→ IntentRouterParser.parse(response)
  │     │
  │     └─→ Extract command, confidence, parameters
  │
  ├─→ Confidence check (>0.8 threshold)
  │     │
  │     └─→ If passed: Execute mapped command
  │         If failed: Fallback to /chat
  │
  └─→ Execute /plan --prompt "create a metod in StatsCalculator to calculate fibonacci"
```

**Key Files**:
- `src/main/groovy/se/alipsa/lca/intent/IntentRouterAgent.groovy`
- `src/main/groovy/se/alipsa/lca/intent/IntentCommandRouter.groovy`
- `src/main/groovy/se/alipsa/lca/intent/IntentRouterParser.groovy`

**Configuration**: `src/main/resources/application.properties:42-44`
```properties
assistant.intent.enabled=true
assistant.intent.model=tinyllama
assistant.intent.confidence-threshold=0.8
```

---

### 3. Code Review Flow

```
User: /review --paths src/main/groovy --security
  │
  ├─→ ShellCommands.review()
  │     │
  │     ├─→ buildReviewPayload()
  │     │     │
  │     │     ├─→ Read specified file paths
  │     │     ├─→ Get git diff (if --staged)
  │     │     ├─→ Apply ExclusionPolicy (.aiexclude)
  │     │     └─→ Pack into context with ContextPacker
  │     │
  │     ├─→ Select REVIEWER or SECURITY_REVIEWER persona
  │     │
  │     └─→ ReviewAgent.review()
  │           │
  │           └─→ CodingAssistantAgent.reviewCode()
  │                 │
  │                 ├─→ Build review prompt:
  │                 │     - Code context
  │                 │     - Security focus (if --security)
  │                 │     - Expected format (Findings + Tests)
  │                 │
  │                 └─→ ai.withLlm(LlmOptions
  │                       .withModel("qwen3-coder:30b")
  │                       .withTemperature(0.35))  // Lower for determinism
  │                     .withPromptContributor(SECURITY_REVIEWER)
  │                     .generateText(reviewPrompt)
  │                       │
  │                       └─→ Ollama generates review
  │
  ├─→ Parse response into structured findings:
  │     - Severity (HIGH/MEDIUM/LOW)
  │     - Location (file:line)
  │     - Description
  │     - Suggested fix
  │
  ├─→ Filter by minSeverity (if specified)
  │
  ├─→ Log to reviewLog file (if logReview=true)
  │
  └─→ Display color-coded output
        - RED: HIGH severity
        - YELLOW: MEDIUM severity
        - CYAN: LOW severity
```

**Key Files**:
- `src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy:539+`
- `src/main/groovy/se/alipsa/lca/agent/ReviewAgent.groovy`
- `src/main/groovy/se/alipsa/lca/agent/CodingAssistantAgent.groovy`

---

## Configuration & Model Strategy

### Model Configuration

**Primary Models**: `src/main/resources/application.properties:12-17`
```properties
embabel.models.default-llm=qwen3-coder:30b
embabel.models.llms.best=qwen3-coder:30b
embabel.models.llms.cheapest=gpt-oss:20b

assistant.llm.model=${embabel.models.default-llm:qwen3-coder:30b}
assistant.llm.fallback-model=${embabel.models.llms.cheapest:gpt-oss:20b}
```

### Temperature Strategy

Different tasks require different levels of creativity vs. determinism:

| Task Type                   | Temperature | Rationale                                               |
|-----------------------------|-------------|---------------------------------------------------------|
| **Code Generation (Craft)** | 0.7         | Higher creativity for varied, innovative solutions      |
| **Code Review**             | 0.35        | More deterministic for consistent, reliable analysis    |
| **Intent Routing**          | 0.0         | Perfect determinism for reliable command classification |

**Configuration**: `src/main/resources/application.properties:26-27`
```properties
assistant.llm.temperature.craft=0.7
assistant.llm.temperature.review=0.35
```

### Ollama Connection Settings

**Endpoint Configuration**:
```properties
spring.ai.ollama.base-url=http://localhost:11434
```

**Health Check**:
- Endpoint: `/api/tags`
- Timeout: 4 seconds
- Retry: Falls back to alternative models if primary unavailable

**Model Discovery**:
- Cache TTL: 30 seconds (configurable)
- Automatic refresh on cache expiry
- Validates model availability before requests

---

## Architectural Patterns

### 1. Layered Architecture

The system follows a clean layered architecture:

```
┌─────────────────────────────────────────────┐
│         CLI Layer                           │
│  (ShellCommands, JLineRepl, Batch Runner)   │
└─────────────────┬───────────────────────────┘
                  │
┌─────────────────▼───────────────────────────┐
│         Agent Layer                         │
│  (CodingAssistantAgent, ChatAgent,          │
│   ReviewAgent, IntentRouterAgent)           │
└─────────────────┬───────────────────────────┘
                  │
┌─────────────────▼───────────────────────────┐
│         Framework Layer                     │
│  (Embabel Ai Interface, AgentPlatform,      │
│   Persona System, LlmOptions)               │
└─────────────────┬───────────────────────────┘
                  │
┌─────────────────▼───────────────────────────┐
│         Provider Layer                      │
│  (Spring AI, Ollama HTTP Client)            │
└─────────────────┬───────────────────────────┘
                  │
┌─────────────────▼───────────────────────────┐
│         Service Layer                       │
│  (Ollama Daemon - localhost:11434)          │
└─────────────────────────────────────────────┘
```

**Benefits**:
- Clear separation of concerns
- Easy to test individual layers
- Can swap Ollama for other providers with minimal changes to Agent Layer

---

### 2. Dependency Injection

Embabel agents and tools are Spring beans, enabling clean dependency management:

**Agent Registration**:
```groovy
@Agent(name = "lca-chat", description = "Conversation-based coding assistant")
class ChatAgent {
    // Automatically registered as Spring bean via @EnableAgents
}
```

**Tool Injection**:
```groovy
@Agent(name = "lca-assistant")
class CodingAssistantAgent {
    @Autowired
    private FileEditingTool fileEditingTool

    @Autowired
    private GitTool gitTool
}
```

**Agent Resolution**:
```groovy
// In ShellCommands
AgentProcess process = agentPlatform.plan(
    new SimpleContext("lca-chat"),
    new ProcessOptions()
)
```

---

### 3. Persona-Based Prompting

Instead of raw prompts, LCA uses `RoleGoalBackstory` objects to define agent behavior:

**Persona Definitions**: `src/main/groovy/se/alipsa/lca/agent/Personas.groovy`
```groovy
static final RoleGoalBackstory CODER = RoleGoalBackstory
    .withRole("Repository-Aware Software Engineer")
    .andGoal("Deliver production-ready code that aligns with the project structure, conventions, and tests")
    .andBackstory("Builds software following project-specific coding standards")

static final RoleGoalBackstory SECURITY_REVIEWER = RoleGoalBackstory
    .withRole("Security Reviewer")
    .andGoal("Identify security vulnerabilities, unsafe defaults, and data handling risks")
    .andBackstory("Focuses on OWASP-style risks, secrets handling, and unsafe system interactions")
```

**Usage**:
```groovy
String code = ai
    .withPromptContributor(Personas.CODER)
    .generateText(prompt)
```

Embabel automatically enriches system prompts with role/goal context.

---

### 4. Context Management

LCA carefully manages LLM context to stay within token limits:

**Components**:

1. **TokenEstimator**: Approximates BPE token count
2. **ContextBudgetManager**: Allocates tokens across sources
3. **ContextPacker**: Formats file content efficiently
4. **Conversation History**: Stored per session in `SessionState`

**Budget Allocation Strategy**:
```groovy
Total Budget: 100,000 tokens (model dependent)
  - System Prompt: ~500 tokens
  - Conversation History: 20,000 tokens (last N messages)
  - File Context: 60,000 tokens
  - Web Search Results: 10,000 tokens
  - Reserved for Response: 9,500 tokens
```

**Caching**:
- Model list: 30-second TTL
- Web search results: 600-second TTL
- Conversation history: In-memory per session

---

### 5. Tool Composition

Agents don't implement low-level operations directly—they delegate to specialized tools:

**Example: Code Review Workflow**
```
ReviewAgent
  └─→ CodingAssistantAgent.reviewCode()
        ├─→ CodeSearchTool.search() - Find relevant code
        ├─→ FileEditingTool.read() - Read files
        ├─→ ExclusionPolicy.filter() - Apply .aiexclude rules
        ├─→ ContextPacker.pack() - Format for LLM
        ├─→ Ai.generateText() - Get review from LLM
        └─→ SecretScanner.scan() - Validate before commit
```

**Benefits**:
- Reusable tools across agents
- Easy to test tools independently
- Clear separation between orchestration (agents) and execution (tools)

---

## Security & Safety

### File Exclusions

**Mechanism**: `.aiexclude` file (gitignore syntax)

**File**: `src/main/groovy/se/alipsa/lca/tools/ExclusionPolicy.groovy`

**Example `.aiexclude`**:
```
.env
*.key
credentials.json
secrets/**
node_modules/**
```

Blocks sensitive files from being read, searched, or modified by agents.

---

### Secret Scanning

**File**: `src/main/groovy/se/alipsa/lca/tools/SecretScanner.groovy`

**Detection Patterns**:
- API keys (regex patterns for AWS, GCP, GitHub, etc.)
- Private keys (RSA, SSH)
- Passwords in plaintext
- Connection strings with credentials

**Integration Points**:
- Before file edits via `FileEditingTool`
- Before git commits via `GitTool`
- Before applying patches

---

### Command Policy

**File**: `src/main/groovy/se/alipsa/lca/tools/CommandPolicy.groovy`

**Allowlist/Denylist**:
```properties
# Allowed commands
assistant.command.allowlist=mvn*,git*,gradle*,npm*

# Denied commands
assistant.command.denylist=rm -rf,dd,mkfs
```

Prevents destructive or unauthorized shell commands.

---

### Local-Only Mode

**Configuration**: `src/main/resources/application.properties:8`
```properties
assistant.local-only=true
```

When enabled:
- Blocks all external HTTP requests (except Ollama)
- Disables web search
- Prevents cloud LLM fallback

---

### Additional Safety Features

**1. Confirmation Prompts**: Destructive operations require user confirmation:
```bash
lca> /git-push --force
⚠️  This will force-push to remote. Continue? [y/N]
```

**2. Log Sanitization**: `LogSanitizer` redacts secrets from application logs

**3. OIDC/JWT Support**: REST API supports optional authentication:
```properties
assistant.api.oidc.enabled=true
assistant.api.oidc.issuer=https://your-issuer.com
```

**4. Rate Limiting**: REST API includes per-IP/user rate limiting

**5. Static Analysis**: Optional semgrep integration via `SastTool`

---

## Key Design Decisions

### 1. Ollama-Only Architecture

**Decision**: Exclusively use local Ollama models, no cloud providers.

**Rationale**:
- Privacy: No code or data leaves the developer's machine
- Offline capability: Works without internet connection
- Cost: No API fees
- Control: Complete control over model versions and behavior

**Trade-offs**:
- Requires local GPU/CPU for acceptable performance
- Model quality limited to what runs locally
- Developer must manage Ollama installation

---

### 2. Custom REPL vs. Spring Shell

**Decision**: Replaced Spring Shell with custom JLine REPL.

**Rationale**:
- Better control over natural language input parsing
- More flexible prompt customization
- Reduced dependency footprint
- Supports intent routing more naturally

**Implementation**: `src/main/groovy/se/alipsa/lca/repl/JLineRepl.groovy`

---

### 3. Agent-Driven Architecture

**Decision**: Use Embabel agent framework instead of monolithic service.

**Rationale**:
- Modularity: Agents can be composed and reused
- Personas: Clean separation of concerns (CODER vs. REVIEWER)
- Orchestration: AgentPlatform handles complex workflows
- Testability: Agents are isolated Spring beans

**Trade-offs**:
- Additional framework dependency (Embabel)
- Learning curve for agent concepts

---

### 4. Intent Routing Layer

**Decision**: Add NLU layer to map natural language to structured commands.

**Rationale**:
- User experience: More natural interaction ("review my code" vs. "/review --paths .")
- Flexibility: LLM can understand intent variations
- Discoverability: Users don't need to memorize slash commands

**Implementation**:
- Lightweight `tinyllama` model for fast classification
- Temperature 0.0 for deterministic routing
- Confidence threshold to prevent misrouting

**Trade-offs**:
- Additional LLM call overhead
- Potential for misclassification (mitigated by confidence threshold)

---

### 5. Multi-Temperature Strategy

**Decision**: Use different temperatures for different task types.

**Temperatures**:
- Craft (0.7): Creative code generation
- Review (0.35): Consistent analysis
- Intent (0.0): Deterministic classification

**Rationale**:
- Code generation benefits from variety and creativity
- Code review requires consistency and reliability
- Intent routing must be predictable

---

### 6. Session Isolation

**Decision**: Per-session state management via `SessionState`.

**Rationale**:
- Parallel conversations: Multiple sessions with different contexts
- Configuration flexibility: Each session can use different models/temperatures
- Conversation history: Isolated per session for context management

**Implementation**: `src/main/groovy/se/alipsa/lca/shell/SessionState.groovy`

---

### 7. Tool Composition Over Inheritance

**Decision**: Agents delegate to specialized tools instead of inheriting capabilities.

**Rationale**:
- Reusability: Tools can be shared across agents
- Testability: Tools are independently testable
- Separation of concerns: Agents orchestrate, tools execute
- Flexibility: Easy to swap or extend tool implementations

**Example Tools**:
- `FileEditingTool`, `GitTool`, `CodeSearchTool`, `SecretScanner`

---

## Getting Started

### Prerequisites
1. **Java 21+**: `java -version`
2. **Ollama**: Install from [ollama.ai](https://ollama.ai)
3. **Models**: Pull required models:
   ```bash
   ollama pull qwen3-coder:30b
   ollama pull gpt-oss:20b
   ollama pull tinyllama
   ```

### Building
```bash
mvn clean package
```

### Running
```bash
# Interactive mode
java -jar target/local-coding-assistant-1.1.1.jar

# Batch mode
java -jar target/local-coding-assistant-1.1.1.jar \
  -c "/review --paths src; /commit-suggest" \
  --yes
```

### Configuration
Edit `src/main/resources/application.properties` or provide environment variables:
```bash
export SPRING_AI_OLLAMA_BASE_URL=http://localhost:11434
export EMBABEL_MODELS_DEFAULT_LLM=qwen3-coder:30b
```

---

## References

### Critical Files
- `src/main/groovy/se/alipsa/lca/LocalCodingAssistantApplication.groovy` - Application entry point
- `src/main/groovy/se/alipsa/lca/agent/CodingAssistantAgent.groovy` - Core agent
- `src/main/groovy/se/alipsa/lca/agent/ChatAgent.groovy` - Conversation agent
- `src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy` - REPL command handler
- `src/main/groovy/se/alipsa/lca/intent/IntentRouterAgent.groovy` - NLU routing
- `src/main/groovy/se/alipsa/lca/tools/ModelRegistry.groovy` - Ollama integration
- `src/main/resources/application.properties` - Configuration

### Documentation
- [README.md](../README.md) - Project overview
- [docs/commands.md](commands.md) - Command reference
- [docs/quickstart.md](quickstart.md) - Getting started guide

### External Resources
- [Embabel Documentation](https://embabel.org) - Agent framework docs
- [Ollama Documentation](https://ollama.ai/docs) - LLM provider docs
- [Spring Boot Documentation](https://spring.io/projects/spring-boot) - Framework docs
