# Ollama setup

The Local Coding Assistant is limited to Ollama models so everything runs locally.

## Prerequisites
- Ollama installed and the daemon running (`ollama serve` is started automatically on most installs).
- Java 21+.

## Model selection
- Default model is `deepseek-coder:33b` configured in `src/main/resources/application.properties`.
- Use `./deepseek.sh` to install a suitable DeepSeek model based on available RAM, or pull one manually:
  ```bash
  ollama pull deepseek-coder:6.7b
  ```
- Point to a different model by updating `embabel.models.default-llm` (and `embabel.models.llms.*` if you define roles).

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
This launches Spring Shell with Embabel agents using your Ollama model.
