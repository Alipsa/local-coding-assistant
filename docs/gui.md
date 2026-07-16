# Swing based alternative to the to the lca cli

## Rationale
- a swing based gui will give us much better input control
- we can also show a lot more contextual information

## Design
- The layout should be roughly like this:

|--------------------------------------------------------------------------------------------------------------------------------------------------------
| Base dir: ~/project/dbt-mcp   |   branch:ALE-1044-return-table-id   |   Main Model: qwen3.6:35b-a3b   |   Small model: gpt-oss:20b                    |
|--------------------------------------------------------------------------------------------------------------------------------------------------------
| (user input)                                                                                                                                          |
|                                                                                                                                                       |
|                                                                                                                                                       |
|                                                                                                                                                       |
|                                                                                                                                                       |
|                                                                                                                                                       |
|                                                                                                                                                       |
|                                                                                                                                                       |
|                                                                                                                                                       |
|                                                                                                                                                       |
|                                                                                                                                                       |
|                                                                                                                                                       |
|-------------------------------------------------------------------------------------------------------------------------------------------------------|
|                                                         | Submit (ctrl+enter) |                                                                       |
|-------------------------------------------------------------------------------------------------------------------------------------------------------|
|(older user input)                                                                                                                                     |
|(older lca reply)                                                                                                                                      |
|                                                                                                                                                       |
|(user input)                                                                                                                                           |
|(lca reply)                                                                                                                                            |
|                                                                                                                                                       |
|                                                                                                                                                       |
|                                                                                                                                                       |
|                                                                                                                                                       |
|                                                                                                                                                       |
|                                                                                                                                                       |
|                                                                                                                                                       |
|                                                                                                                                                       |
|                                                                                                                                                       |
|                                                                                                                                                       |
|                                                                                                                                                       |
|                                                                                                                                                       |
|                                                                                                                                                       |
|                                                                                                                                                       |
|                                                                                                                                                       |
|                                                                                                                                                       |
|                                                                                                                                                       |
|                                                                                                                                                       |
|                                                                                                                                                       |
|                                                                                                                                                       |
|                                                                                                                                                       |
|                                                                                                                                                       |
|                                                                                                                                                       |
|--------------------------------------------------------------------------------------------------------------------------------------------------------
| Context: [░░░░░░░░░░] 8%   |   Autocompact: [███░░░░░░░] 36%   |   Main memory:  43 / 64 Gb   |   GPU memory: 18 / 52 Gb                              |
|--------------------------------------------------------------------------------------------------------------------------------------------------------

- llm responses should be formatted in a similar style to OpenCode (use color, font and space to make the conversation clear)
- use flatlaf for the look and feel
- create a new launch command script called lcaGui to complement lca