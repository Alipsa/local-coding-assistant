# Ollama setup

The Local Coding Assistant is limited to Ollama models so everything runs locally.

## Prerequisites
- Ollama installed and the daemon running (`ollama serve` is started automatically on most installs).
- Java 21+.

## Model selection
- Default model is `qwen3-coder:30b` configured in `src/main/resources/application.properties`.
  - Fallback model for simpler tasks is `gpt-oss:20b`
- Use `./models.sh` to install the suitable models based on available RAM, or pull them manually:
  ```bash
  ollama pull qwen3-coder:30b && ollama pull gpt-oss:20b
  ```
- Point to a different model by updating `embabel.models.default-llm` (and `embabel.models.llms.*` if you define roles).

### Coding assistant runtime knobs
These properties live in `src/main/resources/application.properties` and drive the agent defaults:

```properties
# Ollama endpoint
spring.ai.ollama.base-url=http://localhost:11434

# Preferred model; defaults to qwen3-coder:30b
embabel.models.default-llm=qwen3-coder:30b

# Coding assistant tuning
assistant.llm.model=${embabel.models.default-llm:qwen3-coder:30b}
assistant.llm.temperature.craft=0.7      # higher for creative code generation
assistant.llm.temperature.review=0.35    # lower for concise, deterministic reviews
assistant.llm.max-tokens=0               # optional ceiling; 0 means unset
assistant.system-prompt=                 # optional extra system guidance for all prompts
snippetWordCount=200                     # narrative guidance limit for crafted code prompts
reviewWordCount=150                      # narrative limit for reviews; code blocks may exceed this
```

**Notes**
- The agent always uses the configured model; set `assistant.llm.model` to override per deployment.
- Temperature values separate generation vs. review behaviors for predictable outputs.
- Word counts bound narrative text only; code blocks are not truncated, but formatting guardrails keep sections (Plan / Implementation / Notes, Findings / Tests) consistent.

## Host configuration
If Ollama runs remotely, change the base URL:
```properties
spring.ai.ollama.base-url=http://<host>:11434
```
Keep ports open between your workstation and the Ollama host.

## Running
Start the interactive shell (after installing a model):
```bash
./scripts/shell.sh
```
This launches the JLine REPL with Embabel agents using your Ollama model.
