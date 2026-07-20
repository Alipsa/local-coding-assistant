# Agent Team (Dispatcher / Architect / Engineer / QA Reviewer)

An alternative execution path for `/implement`, gated behind `assistant.team.enabled` (default
`false`). Instead of a single chat turn that produces and applies tool calls in one shot, the
task is broken down and handled by four distinct roles, each a real Embabel `@Agent` with its
own tunable prompt, model, temperature, and timeout — so weaknesses in one role's output can be
diagnosed and fixed independently of the others, rather than all blending into one large prompt.

## Roles

- **Dispatcher** (`DispatcherAgent`) — classifies the task as SIMPLE or COMPLEX with one cheap
  LLM call. SIMPLE tasks skip straight to a single Engineer step; COMPLEX tasks go through the
  full Architect → Engineer → QA pipeline.
- **Architect** (`ArchitectAgent`) — for COMPLEX tasks, produces an ordered, dependency-aware
  implementation plan (`ArchitectPlan`) via Embabel's structured-output support
  (`Ai.createObject`), not hand-rolled JSON parsing. Its prompt requires cross-file planning: a
  step that changes a shared interface or a persisted schema must be accompanied by steps for
  every affected caller/reader, and `acceptanceCriteria` must describe how a Spock test would
  verify the step.
- **Engineer** (`EngineerAgent`) — implements one plan step at a time using the same file-editing
  tool-call syntax (`writeFile`/`replace`/`deleteFile`/`runCommand`) as the rest of the app,
  following the same repository-fit and testability guidance the plain chat `/implement` path
  already applies via `buildCraftCodePrompt`.
- **QA Reviewer** (`TeamReviewerAgent`) — after all plan steps succeed, reviews the accumulated
  diff using the same cross-file-analysis prompt depth as the `/review` command's PR review path
  (`ReviewPromptBuilder`, shared by both). If the review contains a `[High]` severity finding,
  the Engineer gets **one** bounded fix-up pass addressing the findings, followed by a single
  re-review — no further retries regardless of outcome.

## Orchestration

`TeamOrchestrator` is the "dispatcher/PM" — a plain, deterministic Groovy service, not itself an
LLM-driven role. It:

1. Calls `DispatcherAgent.classify(...)`.
2. For COMPLEX tasks, calls `ArchitectAgent.plan(...)`, then topologically sorts the plan's steps
   into dependency-respecting "waves" (steps in the same wave run in parallel via virtual
   threads; steps targeting the same file are rejected as a collision before execution starts).
3. Calls `EngineerAgent.executeStep(...)` once per step, wave by wave, halting on the first
   failing step.
4. Once all waves succeed, calls `TeamReviewerAgent.review(...)` and applies the fix-up-retry
   logic described above.

Each role's `@Action` method is called directly (constructor-injected, like any other Spring
bean) rather than through Embabel's `RunSubagent`/goal-planning machinery — an earlier spike
found `RunSubagent.instance(...)` requires additional "StuckHandler" configuration to resolve a
subagent call synchronously from outside a live `AgentProcess`, which wasn't worth taking on for
this feature. Each role is still a genuine `@Agent`/`@Action`/`@AchievesGoal` class (mirroring
`ReviewAgent`), so Embabel's startup action-graph validation and per-agent observability still
apply, and each role remains independently invocable if a future need arises.

## Configuration

All properties are under the `assistant.team.*` prefix.

| Property | Default | Description |
|---|---|---|
| `enabled` | `false` | Master switch for the team path. |
| `auto-execute` | `true` | If `false`, the Architect's plan is printed and the user is prompted `y/n` before the Engineer runs. |
| `dispatcher-model` / `architect-model` / `engineer-model` / `reviewer-model` | `assistant.llm.model` | Per-role model override. |
| `dispatcher-temperature` | `0.1` | |
| `architect-temperature` | `0.3` | |
| `engineer-temperature` | `0.2` | |
| `reviewer-temperature` | `0.1` | |
| `dispatcher-timeout-seconds` | `30` | |
| `architect-timeout-seconds` | `300` | |
| `engineer-timeout-seconds` | `600` | |
| `reviewer-timeout-seconds` | `300` | |

## Relationship to `/review`

The QA Reviewer role and the `/review` command's PR review path share `ReviewPromptBuilder` for
the actual prompt text, so improvements to review depth benefit both paths rather than drifting
into two separately-tuned prompts.
