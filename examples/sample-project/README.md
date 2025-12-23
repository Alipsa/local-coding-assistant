# Sample Project

This is a tiny Groovy 5.0.3 + Spock 2.4 project meant for trying Local Coding Assistant workflows.

## Quickstart
1. From the repo root:
   `cd examples/sample-project`
2. Run tests:
   `mvn test`
3. Start the assistant from this directory:
   `../../scripts/shell.sh`

## Suggested tasks
- Add a `greetAll(List<String> names)` method and tests.
- Add validation to reject names that contain digits.
- Add a new feature flag in `AGENTS.md` and see how it influences prompts.

## LCA commands to try
- `/tree --depth 2`
- `/codesearch --query "greet" --paths src`
- `/review --paths src/main/groovy --prompt "Review for edge cases."`
