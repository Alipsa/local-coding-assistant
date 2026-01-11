# Local Coding Assistant
## Building AI-Powered Developer Tools with AI

A fully local, privacy-first coding assistant
**Built entirely through "vibe coding" with ChatGPT, Codex & Claude Code**

---

## The Meta-AI Story 🤯

We used **AI coding assistants** to build...

...an **AI coding assistant** that uses...

...an **AI model** to parse natural language...

...which triggers **AI-powered agents**...

...that execute commands using **AI models**!

**"AI all the way down"**

---

## The Problem

### Cloud-Based Tools
- Code sent to external servers
- Privacy concerns
- Requires internet
- API costs
- Data retention policies

### Our Solution
- 100% local execution
- Zero cloud dependency
- Complete privacy
- Offline capable
- No API fees

---

## What is LCA?

A **local-first coding assistant** that provides:

- 🤖 AI-powered code generation and review
- 💬 Natural language command interface
- 🔧 50+ specialized slash commands
- 🔐 Complete privacy (Ollama-only)
- ⚡ CLI and REST API interfaces
- 🎯 Git-aware workflows

---

## Technology Stack

### Core Framework
- **Java 21** + **Groovy 5.0.3**
- **Spring Boot 3.5.7**
- **Embabel 0.3.1** (Agent Framework)
- **JLine 3.x** (Custom REPL)

### AI & Models
- **Ollama** (Local LLM provider)
- **qwen3-coder:30b** (Primary model)
- **gpt-oss:20b** (Fallback)
- **tinyllama** (Intent routing)

**Requirements:** 16+ GB RAM, Java 21+, Ollama

---

## System Architecture

```
┌─────────────────────────────────────────────┐
│         CLI Layer (JLine REPL)              │
│  Natural Language + Slash Commands          │
└─────────────────┬───────────────────────────┘
                  │
┌─────────────────▼───────────────────────────┐
│    Intent Router (tinyllama @ temp=0.0)     │
│  "review my code" → /review --security      │
└─────────────────┬───────────────────────────┘
                  │
┌─────────────────▼───────────────────────────┐
│         Agent Layer (Embabel)               │
│  ChatAgent, CodingAssistant, ReviewAgent    │
└─────────────────┬───────────────────────────┘
                  │
┌─────────────────▼───────────────────────────┐
│         LLM Layer (Ollama)                  │
│  qwen3-coder:30b, gpt-oss:20b               │
└─────────────────────────────────────────────┘
```

---

## 🧠 The Natural Language Layer

### Intent Router Agent

**User says:**
```
"create a metod in StatsCalculator to calculate fibonacci"
```

**Intent Router (tinyllama, temp=0.0) returns:**
```json
{
  "command": "/plan",
  "confidence": 0.92,
  "parameters": {
    "prompt": "create a metod in StatsCalculator to calculate fibonacci"
  }
}
```

**Executes:** `/plan --prompt "create a metod in StatsCalculator to calculate fibonacci"`

*Note: The router preserves the original prompt, including typos!*

---

## 🤖 Agent-Driven Design

Powered by **Embabel Framework**

- **ChatAgent** - Conversational interactions
- **CodingAssistantAgent** - Code generation & editing
- **ReviewAgent** - Code review & security analysis
- **IntentRouterAgent** - Natural language understanding

### Persona System
CODER, ARCHITECT, REVIEWER, SECURITY_REVIEWER

*Personas adapt to your project's language and conventions automatically via AGENTS.md*

```groovy
String code = ai
    .withPromptContributor(Personas.CODER)
    .withLlm(LlmOptions.withTemperature(0.7))
    .generateText(prompt)
```

---

## 🌡️ Multi-Temperature Strategy

| Task Type | Temperature | Rationale |
|-----------|-------------|-----------|
| **Code Generation** | 0.7 | Higher creativity for varied solutions |
| **Code Review** | 0.35 | Deterministic, consistent analysis |
| **Intent Routing** | 0.0 | Perfect determinism for classification |

**Different tasks need different creativity/determinism trade-offs!**

---

## Core Features

### 💬 Chat & Planning
- Multi-turn conversations
- Session management
- Context-aware responses

### 📝 Code Generation
- Search-and-replace blocks
- File editing with backups
- Automatic revert support

### 🔍 Code Review
- Security-focused analysis
- Severity filtering
- Optional SAST integration

### 🔧 Git Integration
- Smart commit messages
- Secret scanning
- Patch management

---

## Example Workflow

**Natural Language:**
```
lca> review my authentication code for security issues
```

**Intent Router translates to:**
```
/review --paths src/auth --security
```

**ReviewAgent returns:**
```
## Findings
🔴 HIGH: Password comparison using == instead of constant-time
   Location: src/auth/Login.groovy:42
   Fix: Use MessageDigest.isEqual() for timing-safe comparison

🟡 MEDIUM: Missing rate limiting on login endpoint
   Location: src/auth/LoginController.groovy:28
   Fix: Add @RateLimited annotation

## Tests Recommended
- Test timing attack resistance
- Verify rate limiting behavior
```

---

## 🔨 Tool Composition Pattern

Agents delegate to specialized tools:

### Core Tools
- FileEditingTool
- GitTool
- CodeSearchTool (ripgrep)
- WebSearchTool

### Safety Tools
- SecretScanner
- ExclusionPolicy
- CommandPolicy
- LogSanitizer

**Benefits:** Reusable, testable, composable

---

## 🔐 Security & Safety

### .aiexclude file
Blocks sensitive files from AI access
```
.env
*.key
credentials.json
secrets/**
```

### Other Safety Features
- **Secret Scanner** - Detects API keys, passwords before commits
- **Command Policy** - Allowlist/denylist for shell commands
- **Confirmation Prompts** - Required for destructive operations
- **Log Sanitization** - Automatic secret redaction

---

## 📋 AGENTS.md Specification

Project-specific rules automatically included in agent prompts:

```markdown
# Project Rules
- Use Python 3.11 with type hints where possible
- Keep lines under 100 characters (PEP 8)
- Use pytest for all new tests
- Follow existing code style and patterns

# Environment
- Python 3.11+
- No external API calls in tests
```

---

## 🌐 REST API

All CLI commands available via REST:

```bash
# Code review
POST /api/cli/review
{
  "prompt": "Check error handling",
  "paths": ["src/main/groovy"],
  "security": true
}

# Chat
POST /api/cli/chat
{
  "prompt": "Explain this function",
  "persona": "ARCHITECT"
}
```

**Optional:** OIDC authentication, rate limiting, API keys

---

## ⚙️ Batch Mode for CI/CD

```bash
# Run multiple commands non-interactively
java -jar local-coding-assistant.jar \
  -c "/status; /review --paths src; /commit-suggest" \
  --yes --batch-json

# Or from a file
java -jar local-coding-assistant.jar \
  --batch-file scripts/ci-review.txt \
  --yes
```

**Exit code 0** on success, non-zero on first failure
**--batch-json** emits JSONL for machine parsing

---

## 💡 Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| **Ollama-Only** | Complete privacy, offline capability, zero API costs |
| **Custom REPL** | Better NL parsing, reduced dependencies |
| **Agent Framework** | Modularity, personas, orchestration |
| **Intent Routing** | Natural UX, discoverability |
| **Multi-Temperature** | Right tool for each task |
| **Tool Composition** | Reusability, testability, flexibility |

---

## 📚 Lessons Learned from "Vibe Coding"

✅ **AI excels at:** Boilerplate, configuration, integration code, tests

✅ **Architecture matters:** Clear structure helps AI understand context

✅ **Iterative refinement:** Start simple, add complexity incrementally

⚠️ **AI challenges:** Cross-file refactoring, performance optimization

⚠️ **Human oversight:** Still needed for design decisions

🎯 **The sweet spot:** Human does architecture, AI does implementation

---

## ♻️ The Recursive Quality

We used **ChatGPT** and **Claude Code**...

...to build a tool that works **just like them**...

...but runs **100% locally**!

*"We've essentially cloned the tools that built themselves"*

---

## 📊 By The Numbers

| Metric | Value |
|--------|-------|
| Slash Commands | **50+** |
| Specialized Agents | **4** |
| AI Models Used | **3** |
| Cloud Dependencies | **0** |

---

## 🎯 Use Cases

🏢 **Enterprise:** Privacy-compliant AI assistance

🔒 **Security-Sensitive:** No code leaves your network

✈️ **Offline Development:** Works without internet

💰 **Cost-Conscious:** No per-token charges

🎓 **Learning:** Understand how AI coding tools work

🔧 **Customization:** Full control over models and behavior

---

## Command Examples

```bash
# Natural language
lca> review my authentication code for security issues

# Or explicit commands
lca> /review --paths src/auth --security --min-severity HIGH

# Planning
lca> /plan --prompt "add pagination to user list API"

# Code search
lca> /codesearch --query "password.*hash" --paths src

# Git workflow
lca> /status
lca> /diff --staged
lca> /commit-suggest --hint "security improvements"
lca> /git-push

# Configuration
lca> /config --local-only true
lca> /model --set qwen3-coder:30b
```

---

## 🚀 Getting Started

### 1. Install Ollama
```bash
curl -fsSL https://ollama.ai/install.sh | sh
```

### 2. Pull models
```bash
ollama pull qwen3-coder:30b
ollama pull gpt-oss:20b
ollama pull tinyllama
```

### 3. Install LCA
```bash
curl -fsSL -o ~/.local/bin/lca \
  https://github.com/Alipsa/local-coding-assistant/releases/latest/download/lca
chmod +x ~/.local/bin/lca
lca
```

---

## 🔮 Future Possibilities

🔌 **IDE Integration:** LSP server for VSCode/IntelliJ

🧪 **Test Generation:** Automated test creation

📊 **Code Metrics:** Complexity analysis and suggestions

🔄 **Refactoring:** Large-scale code transformations

🌍 **Multi-Language:** Support for more languages

🤝 **Team Features:** Shared coding standards, review workflows

---

## 🎯 Key Takeaways

1️⃣ **Local AI is viable** - Privacy without sacrificing capability

2️⃣ **"Vibe coding" works** - But needs human architecture

3️⃣ **Layered AI design** - Different models for different tasks

4️⃣ **Safety is essential** - Secret scanning, exclusions, confirmations

5️⃣ **Agent patterns scale** - Composition over monoliths

---

## 📚 Resources

**🔗 GitHub:** github.com/Alipsa/local-coding-assistant

**📖 Documentation:** See `docs/` folder

**🏗️ Architecture:** `docs/architecture.md`

**⚡ Quickstart:** `docs/quickstart.md`

**📋 Commands:** `docs/commands.md`

**🤖 Embabel Framework:** embabel.org

**🦙 Ollama:** ollama.ai

---

## Thank You!

### Questions?

Built with AI, for AI-assisted development

🤖 + 👨‍💻 = ♾️
