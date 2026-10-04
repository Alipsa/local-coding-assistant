# Ollama setup

The Local Coding Assistant is limited to Ollama models so everything runs locally.

## Prerequisites
- Ollama installed and the daemon running (`ollama serve` is started automatically on most installs).
- Java 21+.

## Model selection
- The default chat model is `qwen3.8-192k:latest`, built from `qwen3.8:27b`.
- Reviews use `qwen3.8-review:latest`; simpler tasks use `gpt-oss-64k:latest`, built from `gpt-oss:20b`.
- `src/main/bin/lca` is the canonical source for model names, context sizes and custom recipes.
  Change those variables there, then run `lca update` or `./models.sh` from source.
- `lca` installs the configured base and custom models automatically. Before running `./run.sh`,
  start Ollama and run `./models.sh`; pulling only the base models does not create the custom models.
- `nomic-embed-text:latest` is also required at startup and installed by both scripts.
- The launcher exports `LCA_CHAT_MODEL`, `LCA_FALLBACK_MODEL`, `LCA_REVIEW_MODEL` and `LCA_EMBEDDING_MODEL`.
  Set these explicitly for source/IDE runs when using a custom configuration; the properties file
  contains fallback values for runs that bypass the launcher.

### Coding assistant runtime knobs
These properties live in `src/main/resources/application.properties` and drive the agent defaults:

```properties
# Ollama endpoint
spring.ai.ollama.base-url=http://localhost:11434

# Model value exported by the launcher, with a fallback for source runs
embabel.models.default-llm=${LCA_CHAT_MODEL:qwen3.8-192k:latest}

# Coding assistant tuning
assistant.llm.model=${embabel.models.default-llm}
assistant.llm.temperature.craft=0.7      # higher for creative code generation
assistant.llm.temperature.review=0.1    # lower for concise, deterministic reviews
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
./run.sh
```
This launches the JLine REPL with Embabel agents using your Ollama model.
