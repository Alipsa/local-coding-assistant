# Roadmap

4. Add support for switching models in runtime
    - user can select among existing Ollama models (/models command in lca and manu option in lcaGui)
    - a model change is persistent over restarts
5. Add support for prompt caching
    - Relevant links:
    https://github.com/embabel/embabel-common/issues/77

## Extension support
Perhaps we should consider tools to be the lowest level extension unit.
We have tools (using @LlmTool, in combination with @RequiresConfirmation) today
but they are built into the app. We need a way to dynamically add and disable tools in the application.
Perhaps running lca using the groovy command would enable that since the classloader then
allows for dynamic adding of classes?

```groovy
import se.alipsa.lca.tools.Tool

@Tool("Fetches a user's wallet balance from the database")
def getUserBalance = { String userId ->
   // do stuff
}
// use it:
def response = lca.send(
    model: "gpt-4o",
    messages: ["role": "user", "content": "How much money does user_99 have?"],
    tools: [getUserBalance]
```

which would be equivalent to the following python code:
```python
import openai

# 1. Define the tool inline
my_tools = [{
    "type": "function",
    "function": {
        "name": "getUserBalance",
        "description": "Fetches a user's wallet balance from the database",
        "parameters": {
            "type": "object",
            "properties": {
                "userId": {"type": "string"}
            },
            "required": ["userId"]
        }
    }
}]

# 2. Send it directly to the model
response = openai.chat.completions.create(
    model="gpt-4o",
    messages=[{"role": "user", "content": "How much money does user_99 have?"}],
    tools=my_tools
)
```
6. Externalize the built in support for development tools so they are deployed the same way a user registered tool would be.
    - git support via jgit instead of process runner in GitTool
    - github support (see github.-integration-md for details)
    - file creation, reading, editing
    - file search

7. Add support for skills
    - step 1: add support for SKILL.md
    - step 2: add support for adding groovy scripts referenced by the SKILL.md
    - step 3: add support for the full skills spec: https://agentskills.io/specification

8. add support for local MCP servers
    - step 1: groovy script based mcp's
    - step 2: jvm based code
    - step 3: full support for the MCP spec for local mcp's: https://modelcontextprotocol.io/specification/2025-11-25
9. add support for remote mcp servers (https://modelcontextprotocol.io/specification/2025-11-25)
10. add support for creating subagents
    - define our own standard for subagents we should support 2 ways: groovy script and markdown
   ```groovy
   def agent = lca.createSubagent(
        name: code-reviewer,
        description: "Specialized subagent that reviews code diffs for security vulnerabilities.",
        model: "claude-sonnet-5",
        tools: ["file_reader", "git_diff"],
        mcpServers: ["security-scanner-mcp"]
   )
   lca.registerAgent(agent)
   ```
   ```markdown
   ---
   name: code-reviewer
   description: Specialized subagent that reviews code diffs for security vulnerabilities.
   model: claude-sonnet-5
   tools: [file_reader, git_diff]
   mcpServers: [security-scanner-mcp]
   ---
   You are a strict code reviewer. Your only job is to analyze the provided code changes...
   ```

11. Add support for plugins
    - plugins should be a way to package skills, mcp's  and subagents together into a jar
    - https://awesomeskill.ai/blog/skills-vs-mcp-vs-plugins-vs-subagents
    - create the following plugins
        - maven plugin
        - gradle support
        - extract the github tool into its own plugin