# Tutorial

This tutorial walks through basic usage, creating a new project from scratch, and common workflows
for the Local Coding Assistant.

## Prereqs
- Java 21 installed and available on your PATH.
- Ollama running locally with `qwen3-coder:30b` pulled (run `./models.sh` if needed).
- This repository cloned locally.

## Basic usage
1. Start the shell:
   `./scripts/shell.sh`
2. Verify the model and connectivity:
   `/health`
   `/model --list`
3. Explore the repo:
   `/status`
   `/tree --depth 2`
4. Ask a simple question:
   `/chat --prompt "Summarize the project structure and key folders."`

Tip: Commands can be run with or without the leading `/`.

## Create a new project from scratch
You can build a tiny Groovy project in minutes and use the assistant for guidance.

### Option A: Copy the sample project
1. Copy the sample:
   `cp -R examples/sample-project ../my-sample-project`
2. Update `../my-sample-project/pom.xml` groupId/artifactId as needed.
3. Start the assistant from the new project root:
   `cd ../my-sample-project`
   `../local-coding-assistant/scripts/shell.sh`

### Option B: Create the project manually
1. Create the folder structure:
   `mkdir -p my-project/src/main/groovy/com/example`
   `mkdir -p my-project/src/test/groovy/com/example`
2. Add an `AGENTS.md` with project rules, for example:
   ```
   - Use Groovy 5.0.3 and @CompileStatic when possible.
   - Use 2-space indentation and 120-character max line length.
   - Write Spock 2.4 tests for new functionality.
   ```
3. Add a minimal `pom.xml` (see `examples/sample-project/pom.xml` for a working baseline).
4. Create your first class and test (see `examples/sample-project/src/...`).
5. Run tests:
   `mvn test`
6. Start the assistant from the project root:
   `../local-coding-assistant/scripts/shell.sh`

The assistant automatically picks up `AGENTS.md` and includes it in system prompts.

## Common workflows

### Edit workflow
1. Gather context:
   `/context --file-path src/main/groovy/com/example/Greeter.groovy --symbol greet`
2. Ask for Search-and-Replace blocks:
   `/chat --prompt "Add null handling to greet(). Return 'Hello, World!' when blank."`
3. Preview changes:
   `/applyBlocks --file-path src/main/groovy/com/example/Greeter.groovy --blocks "<blocks>" --dry-run`
4. Apply changes:
   `/applyBlocks --file-path src/main/groovy/com/example/Greeter.groovy --blocks "<blocks>"`
5. Roll back if needed:
   `/revert --file-path src/main/groovy/com/example/Greeter.groovy --dry-run`

### Review workflow
1. Ask for a review:
   `/review --paths src/main/groovy --prompt "Check for edge cases and missing tests."`
2. Filter logged reviews:
   `/reviewlog --min-severity MEDIUM --limit 5`

### Search workflow
1. Inspect the tree:
   `/tree --depth 3`
2. Search locally:
   `/codesearch --query "greet" --paths src/main/groovy`
3. Pack context when needed:
   `/codesearch --query "greet" --paths src/main/groovy --pack --max-chars 2000`

### Git workflow
1. Check status:
   `/status --short-format`
2. Inspect diffs:
   `/diff --stat`
3. Stage changes:
   `/stage --paths src/main/groovy/com/example/Greeter.groovy`
4. Generate a commit message:
   `/commit-suggest --hint "Add greeting fallback"`
5. Push when ready:
   `/git-push`

## Batch mode examples
Example batch command files live in `docs/examples/`:
- `docs/examples/batch-basic.txt`
- `docs/examples/batch-review.txt`
- `docs/examples/batch-ci.txt`

Run them with:
`java -jar local-coding-assistant.jar --batch-file docs/examples/batch-basic.txt`

Batch mode stops on the first failure. Use `--yes` to auto-confirm prompts when running in CI.

## Screenshots and recordings (optional)
If you want to include a terminal recording:
1. Install `asciinema`.
2. Record:
   `asciinema rec docs/examples/recordings/quickstart.cast`
3. Replay locally:
   `asciinema play docs/examples/recordings/quickstart.cast`

Store screenshots under `images/tutorial/` and reference them here once captured.
