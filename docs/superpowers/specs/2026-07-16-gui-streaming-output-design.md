# Design: Streaming shell output and chunked LLM turns in the LCA GUI

Date: 2026-07-16
Status: Approved (pending spec review)

## Problem

In the Swing GUI (`lcaGui`), a turn produces no visible output until it is completely
finished. A `!` shell command (e.g. `! mvn test`) shows nothing during the run and then dumps
all output at once, and a multi-step LLM turn (e.g. a route that runs `/plan` then `/review`)
shows nothing until both have completed. The user wants output to appear incrementally —
streamed where possible, or at least delivered in chunks.

## Findings that shape the approach

- **Shell output is genuinely streamable.** `CommandRunner.runStreaming` already invokes a
  `CommandRunner.OutputListener` once per line. Today `ShellCommands.executeShell` only buffers
  those lines into a `StringBuilder` and returns the whole string at the end. The streaming
  already happens under the hood — the GUI just discards the intermediate state.
- **LLM responses are only partially streamable.** The model is called through Embabel's
  blocking `PromptRunner.generateText` / `createObject`. Embabel 0.5.0 exposes a `stream()`
  capability, but (a) it is a Kotlin-idiomatic reactive `StreamingEvent` flow that is awkward to
  consume from `@CompileStatic` Groovy, and (b) most LCA commands post-process the *complete*
  response — `createObject(CodeSnippet)`, grounding checks, tool-call parsing/validation,
  word-limit enforcement, and code-block extraction — none of which can run on a partial
  response. Token-level streaming is therefore explicitly out of scope.
- **The GUI turn is a single blocking call.** `GuiTurnController.process` runs every routed
  command, concatenates the results, and returns one aggregated `TurnResult`, which
  `LcaMainFrame`'s `SwingWorker.done()` renders in one shot.

## Chosen approach

Stream `!` output line-by-line, and deliver LLM turns in chunks by pipeline stage. No Embabel
streaming API is used. This reuses machinery already in the codebase (the per-line
`OutputListener`, `SwingWorker.publish/process`).

## Design

### 1. `TurnSink` — the streaming interface

A small interface the turn pipeline emits events into, instead of returning aggregated text:

- `note(String text)` — a routing / confidence / status line.
- `beginBlock()` / `append(String line)` / `endBlock()` — a live, growing block for streamed
  shell output (rendered as a fenced code block).
- `message(String markdown)` — one completed content chunk (e.g. a single command's LLM result).

`TurnResult` is reduced to control state only — `action` (NONE/EXIT/CLEAR) and `understood`.
The textual content is delivered through the sink as it is produced, not via the return value.

### 2. Shell path — real line streaming

Thread an optional per-line `Consumer<String>` from the GUI down to the streaming executor:

- `ShellCommands.executeShell(command, session, streamToConsole, Consumer<String> lineConsumer)`
  — when `lineConsumer` is non-null, the existing `OutputListener` also calls
  `lineConsumer.accept(line)` for each stdout/stderr line, in addition to capturing it.
- `shellCommandCaptured(command, session, Consumer<String> lineConsumer)` — new overload; the
  existing no-consumer method delegates with `null`.
- `BangCommandHandler.handle(input, session, captureOutput, Consumer<String> lineConsumer)` —
  new overload; existing signature delegates with `null`.
- `GuiTurnController` passes a consumer that pushes each line into the sink's live block
  (`beginBlock` before the run, `append` per line, `endBlock` after).

The REPL/console path is unchanged: it calls the no-consumer overloads and still streams to
stdout via `streamToConsole`. History and conversation logging for the assistant are unchanged —
still recorded once at the end of the command.

### 3. LLM path — chunk by pipeline stage

`GuiTurnController.process(input, TurnSink sink)`:

- Emits the routing note (if any) via `sink.note(...)` first.
- Runs each routed command in turn and emits **that command's** result via `sink.message(...)`
  the moment it completes — rather than concatenating all results and returning once.
- Bang input streams as in section 2 and skips routing.
- Built-ins (`exit`/`quit`/`clear`/`cls` and their slash forms) and explicit `/command`
  dispatch are unchanged except that any output is emitted through the sink; the returned
  `TurnResult` carries the `action`.

Each individual command's LLM call remains atomic (required by the post-processing noted above).
The visible improvement is that multi-command turns appear step-by-step.

### 4. GUI plumbing — background thread to EDT, incremental rendering

- A `SinkEvent` is a small immutable record of one sink call — its kind (`NOTE`, `BLOCK_BEGIN`,
  `BLOCK_APPEND`, `BLOCK_END`, `MESSAGE`) plus its text payload (if any).
- `LcaMainFrame`'s `SwingWorker<TurnResult, SinkEvent>` uses `publish()` for every sink event
  and `process(List<SinkEvent>)` to apply them on the EDT. The `TurnSink` implementation handed
  to `process(input, sink)` simply calls `publish(event)`; `process` dispatches each event to
  `ConversationView`.
- `ConversationView` gains a **live block**: a `liveBlock` buffer held separately from the
  committed `body`. `render()` shows `body + liveBlock`. `beginBlock()` opens a fenced block,
  `append(line)` adds to `liveBlock`, `endBlock()` folds `liveBlock` into `body`.
- **Re-render coalescing:** to avoid a full-HTML `setText` per line during noisy output (e.g. a
  build), re-renders during an open live block are coalesced by a ~60 ms Swing `Timer`; an
  `endBlock` always forces a final flush. `DIRECT_SHELL_MAX_OUTPUT_CHARS` continues to cap total
  output size.
- `SwingWorker.done()` no longer renders content. It finalises only: apply `action`
  (dispose+exit on EXIT, `conversationView.clear()` on CLEAR), refresh footer/header, clear the
  busy state, restore focus. Error handling emits an error note through the sink / a final note.

## Testing (Spock)

- `ShellCommandsSpec` / `BangCommandHandlerSpec`: the per-line consumer receives each line in
  order; the captured return value is unchanged; the console (no-consumer) path is untouched.
- `GuiTurnControllerSpec`: a fake `TurnSink` records events — assert note-then-per-command
  ordering for a multi-command route, and that `!` input streams lines and skips routing
  (`0 * router.routeDetails`). Existing built-in / slash-command / EXIT / CLEAR tests keep
  passing against the sink-based signature.
- `ConversationViewSpec` (new, exercising the buffer logic without a live frame):
  `beginBlock`/`append`/`endBlock` fold correctly and a coalesced flush yields the expected
  final text.

## Scope guard (YAGNI)

- No token-level LLM streaming and no Embabel `stream()` integration.
- No progress bars, spinners per line, or partial-response post-processing.
- Console/REPL behaviour is unchanged; only the GUI gains incremental delivery.

## Conventions (AGENTS.md)

Groovy 5, `@CompileStatic` where possible, 2-space indent, ≤120 cols, British English,
Spock 2.4, JVM 21. Run `./mvnw test` after implementation.
