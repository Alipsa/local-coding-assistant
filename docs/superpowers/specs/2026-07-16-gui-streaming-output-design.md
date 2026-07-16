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
  `lineConsumer.accept(line)` for each line, in addition to capturing it.
- `shellCommandCaptured(command, session, Consumer<String> lineConsumer)` — new overload; the
  existing no-consumer method delegates with `null`.
- `BangCommandHandler.handle(input, session, captureOutput, Consumer<String> lineConsumer)` —
  new overload; existing signature delegates with `null`.
- `GuiTurnController` drives the block around the run:
  1. `sink.beginBlock()`, then `sink.append("$ " + command)` as the header line (the
     `$ command` header from `formatCapturedShellResult` is only assembled *after* the process
     exits, so the header is emitted here up front rather than streamed from the executor).
  2. passes a `lineConsumer` that forwards each captured line into `sink.append(line)`.
  3. after `executeShell` returns, emits the trailing status footer
     (`[exit N]` / `[exit timeout — failed]`, plus `(output truncated)` when the executor
     truncated) as a final `sink.append(...)`, then `sink.endBlock()`.
  To make the footer reusable line-by-line, `formatCapturedShellResult` is decomposed so its
  header and footer segments are available as small helpers (`shellHeader(command)` /
  `shellFooter(result)`); the existing method keeps composing them for the non-streaming return
  value so REPL output is byte-for-byte unchanged.

**stdout vs stderr:** the executor's `OutputListener` signature is `(String stream, String line)`
and today's console mode uses it to route `"ERR"` lines to `System.err`. The new `lineConsumer`
is intentionally collapsed to `Consumer<String>` — the GUI live block shows both streams as one
interleaved text stream and does **not** distinguish stdout from stderr. Console mode keeps its
existing `System.err` routing; only the GUI consumer drops the distinction. This is a deliberate
simplification, not an oversight.

**In-memory buffer.** `executeShell` today accumulates its own unbounded `captured` StringBuilder
inside the listener (`ShellCommands.groovy:1247-1259`) purely to build the string that
`formatCapturedShellResult` returns — this is separate from both `DIRECT_SHELL_MAX_OUTPUT_CHARS`
(which bounds only `CommandRunner`'s internal `visibleOutput`) and the view-side budget (which
bounds only what reaches the sink), so it too can grow without limit on a verbose command.
Resolution: on the streaming path (a `lineConsumer` is present) the sink is the sole consumer of
line output, so `executeShell` does **not** accumulate `captured` at all — `GuiTurnController`
discards `handle(...)`'s return value (display comes entirely from the sink). The return still
carries the already-capped `result.output` for callers that log it, and history/conversation
logging is unaffected (it uses the capped `result.output`). On the non-streaming captured path
(no `lineConsumer`), `captured` is bounded by the same `DIRECT_SHELL_MAX_OUTPUT_CHARS` cap
(stop-appending past the limit, mirroring `appendVisible`) so it can no longer grow unbounded
there either.

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
  and `process(List<SinkEvent>)` to apply them on the EDT. The `TurnSink` implementation is an
  **anonymous / inner class of the `SwingWorker`** (or holds a reference to it) so it can call
  the worker's `protected publish(...)` — it is deliberately *not* a disconnected top-level
  class. `process` dispatches each event to `ConversationView`.
- **Concurrent producers.** `CommandRunner.runInternal` reads stdout and stderr on two separate
  threads (`CommandRunner.groovy:161-164`), each invoking the same listener, so `lineConsumer`
  (and therefore `sink.append`/`publish`) may be called **concurrently from two background
  threads**. Concurrent `publish()` calls are safe in practice — the JDK's `SwingWorker` funnels
  them through a synchronized `AccumulativeRunnable.add()` — though this is an implementation
  property, not an explicit Javadoc guarantee. The consumer's own state (the truncation budget
  below) must still be thread-safe (e.g. `AtomicInteger`). Per-stream line ordering is preserved;
  **cross-stream (stdout vs stderr) interleaving order is unspecified** and accepted.
- **Consumer-side truncation budget.** The executor fires the listener for *every* line read
  (`CommandRunner.groovy:363`) *before* applying its visible cap (`appendVisible`, `:378`), so
  `DIRECT_SHELL_MAX_OUTPUT_CHARS` bounds only the returned string — it does **not** throttle the
  listener. The `lineConsumer` therefore enforces its **own** independent budget (a line count
  and/or char count, thread-safe): once exceeded it stops forwarding lines to the sink and emits a
  single `… output truncated in view …` marker (once). This prevents a noisy command (e.g.
  `mvn test`) from flooding the EDT/live block regardless of the executor's cap.
- `ConversationView` gains a **live block**: a `liveBlock` buffer held separately from the
  committed `body`. `render()` shows `body + liveBlock`. `beginBlock()` opens a fenced block,
  `append(line)` adds to `liveBlock`, `endBlock()` folds `liveBlock` into `body`.
- **Re-render coalescing:** to avoid a full-HTML `setText` per line during noisy output (e.g. a
  build), re-renders during an open live block are coalesced by a ~60 ms Swing `Timer`; an
  `endBlock` always forces a final flush. (This bounds *render* frequency; the consumer-side
  budget above bounds *how much text* is ever appended.)
- `SwingWorker.done()` no longer renders content. It finalises only: apply `action`
  (dispose+exit on EXIT, `conversationView.clear()` on CLEAR), refresh footer/header, clear the
  busy state, restore focus. Error handling emits an error note through the sink / a final note.

## Testing (Spock)

- `ShellCommandsSpec` / `BangCommandHandlerSpec`: the per-line consumer receives each line
  (per-stream order preserved); the captured return value is unchanged for normal-sized output;
  the console (no-consumer) path still streams to stdout. Add a case asserting the no-consumer
  `captured` buffer stops growing at `DIRECT_SHELL_MAX_OUTPUT_CHARS` for very verbose output.
- **Concurrent streams:** a test where the command produces on **both stdout and stderr** — assert
  every line from each stream is delivered to the consumer (set membership + per-stream ordering),
  and explicitly assert nothing about cross-stream ordering. This is the case a single-stream
  fixture does not exercise.
- **Consumer budget:** a command emitting more lines/chars than the consumer budget — assert
  forwarding stops at the budget and exactly one `… output truncated in view …` marker is emitted,
  independent of `DIRECT_SHELL_MAX_OUTPUT_CHARS`.
- `GuiTurnControllerSpec`: a fake `TurnSink` records events — assert the shell block ordering
  (`beginBlock` → `$ command` header → body lines → `[exit N]` footer → `endBlock`), note-then-
  per-command ordering for a multi-command route, and that `!` input streams lines and skips
  routing (`0 * router.routeDetails`). Existing built-in / slash-command / EXIT / CLEAR tests keep
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
