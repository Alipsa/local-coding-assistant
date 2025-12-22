# Quickstart Examples

These examples show end-to-end flows for editing, reviewing, searching, and git operations.

## Edit flow
```
/status
/context --file-path src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy \
  --symbol review
/chat --prompt "Rewrite the review header to include a timestamp and keep existing sections."
/applyBlocks --file-path src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy \
  --blocks "<<<<SEARCH\n...\n====\n...\n>>>>"
```

## Review flow
```
/review --paths src/main/groovy \
  --prompt "Focus on error handling, logging, and unexpected exceptions."
/reviewlog --min-severity MEDIUM --limit 3
```

## Search flow
```
/tree --depth 3
/codesearch --query "applyPatch" --paths src/main/groovy
/search --query "Spring Shell command examples" --limit 3
```

## Git flow
```
/status
/diff
/stage --paths src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy
/commit-suggest --hint "UX polish"
/git-push
```

## Run flow
```
/run "./mvnw -q -DskipTests=true package" --timeout-millis 120000
```

## Batch mode flow
```
java -jar local-coding-assistant.jar \
  -c "status; review --paths src/main/groovy; commit-suggest"
```
