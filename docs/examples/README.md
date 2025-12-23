# Examples

This folder contains batch mode command files and a small helper script.

## Batch command files
- `batch-basic.txt`: quick sanity checks (status, tree, local search).
- `batch-review.txt`: review and reviewlog example.
- `batch-ci.txt`: CI-friendly sequence for tests and reviews.

## Helper script
`run-batch.sh` is a convenience wrapper around `java -jar` for batch files.

## Usage
Run a batch file:
`java -jar local-coding-assistant.jar --batch-file docs/examples/batch-basic.txt`

Run the helper script:
`docs/examples/run-batch.sh local-coding-assistant.jar docs/examples/batch-review.txt`
