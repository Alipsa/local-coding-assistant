# GUI Streaming Output Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Swing GUI show `!` shell command output line-by-line as it is produced, and deliver multi-step LLM turns in chunks (routing note, then each command result as it completes).

**Architecture:** A `TurnSink` interface receives streaming events from `GuiTurnController.process(input, sink)`. The shell executor already fires a per-line `OutputListener`; a per-line `Consumer<String>` is threaded down to it and forwarded into the sink under a thread-safe view-side truncation budget. The `SwingWorker` in `LcaMainFrame` *is* the sink: its `TurnSink` methods run on background thread(s) and call `publish(SinkEvent)`; `process(List<SinkEvent>)` applies them to `ConversationView` on the EDT. No Embabel/token streaming is used.

**Tech Stack:** Groovy 5, Spring Boot, Swing (`SwingWorker`, `javax.swing.Timer`), Spock 2.4, JVM 21.

## Global Constraints

- Groovy 5, `@CompileStatic` on every new/edited class where practical (matches surrounding code).
- 2-space indentation, ≤120 column lines.
- British English in documentation/comments.
- Spock 2.4 for tests; run `./mvnw test` after each task.
- Local behaviour only: the REPL/console path (`shellCommand`, `streamToConsole`) must remain byte-for-byte unchanged.
- Session id for the GUI is the literal `"default"`.
- View-side truncation budget: `MAX_VIEW_LINES = 5000` lines per shell block.
- Existing shell caps are unchanged: `DIRECT_SHELL_MAX_OUTPUT_CHARS = 8000`, `DIRECT_SHELL_TIMEOUT_MILLIS = 60000`.

---

## File Structure

**New files**
- `src/main/groovy/se/alipsa/lca/gui/SinkEvent.groovy` — immutable event (kind + text) published from the worker.
- `src/main/groovy/se/alipsa/lca/gui/TurnSink.groovy` — interface the turn pipeline emits into.
- `src/main/groovy/se/alipsa/lca/gui/SinkEventDispatcher.groovy` — pure mapping of a `SinkEvent` onto a `ConversationView` call.
- `src/test/groovy/se/alipsa/lca/gui/SinkEventSpec.groovy`
- `src/test/groovy/se/alipsa/lca/gui/SinkEventDispatcherSpec.groovy`
- `src/test/groovy/se/alipsa/lca/gui/ConversationViewSpec.groovy`

**Modified files**
- `src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy` — decompose `formatCapturedShellResult` into `shellHeader`/`shellFooter`; add streaming `executeShell`/`shellCommandCaptured` overloads; cap the non-streaming `captured` buffer.
- `src/main/groovy/se/alipsa/lca/shell/BangCommandHandler.groovy` — add a `Consumer<String>` overload and a `strip` helper.
- `src/main/groovy/se/alipsa/lca/gui/ConversationView.groovy` — add `beginBlock`/`appendBlock`/`endBlock` live block + coalescing timer + `snapshotHtml` (test hook).
- `src/main/groovy/se/alipsa/lca/gui/GuiTurnController.groovy` — `process(input, TurnSink)`; emit note/message/block events; static `budgetedForwarder`.
- `src/main/groovy/se/alipsa/lca/gui/TurnResult.groovy` — reduce to control state (`understood`, `action`).
- `src/main/groovy/se/alipsa/lca/gui/LcaMainFrame.groovy` — `StreamingWorker extends SwingWorker<TurnResult, SinkEvent> implements TurnSink`.
- `src/test/groovy/se/alipsa/lca/shell/ShellCommandsSpec.groovy`, `src/test/groovy/se/alipsa/lca/shell/BangCommandHandlerSpec.groovy`, `src/test/groovy/se/alipsa/lca/gui/GuiTurnControllerSpec.groovy` — updated/extended.

Tasks 1–6 are purely additive (the project keeps compiling and all tests keep passing after each). Task 7 changes the `process` signature and `TurnResult` shape and updates every caller in one step.

---

## Task 1: `SinkEvent` and `TurnSink` types

**Files:**
- Create: `src/main/groovy/se/alipsa/lca/gui/SinkEvent.groovy`
- Create: `src/main/groovy/se/alipsa/lca/gui/TurnSink.groovy`
- Test: `src/test/groovy/se/alipsa/lca/gui/SinkEventSpec.groovy`

**Interfaces:**
- Produces: `SinkEvent(SinkEvent.Kind kind, String text)` with enum `Kind { NOTE, BLOCK_BEGIN, BLOCK_APPEND, BLOCK_END, MESSAGE }` and getters `getKind()`, `getText()`. `TurnSink` with `void note(String)`, `void beginBlock()`, `void append(String)`, `void endBlock()`, `void message(String)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/groovy/se/alipsa/lca/gui/SinkEventSpec.groovy`:

```groovy
package se.alipsa.lca.gui

import spock.lang.Specification

class SinkEventSpec extends Specification {

  def "carries kind and text"() {
    when:
    SinkEvent e = new SinkEvent(SinkEvent.Kind.BLOCK_APPEND, "line one")

    then:
    e.kind == SinkEvent.Kind.BLOCK_APPEND
    e.text == "line one"
  }

  def "text may be null for block boundaries"() {
    expect:
    new SinkEvent(SinkEvent.Kind.BLOCK_BEGIN, null).text == null
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=SinkEventSpec`
Expected: FAIL/compile error — `unable to resolve class se.alipsa.lca.gui.SinkEvent`.

- [ ] **Step 3: Write the types**

Create `src/main/groovy/se/alipsa/lca/gui/SinkEvent.groovy`:

```groovy
package se.alipsa.lca.gui

import groovy.transform.CompileStatic
import groovy.transform.Immutable

/**
 * One streaming event produced during a GUI turn, published from the background worker and
 * applied to the {@link ConversationView} on the EDT by {@link SinkEventDispatcher}.
 */
@Immutable
@CompileStatic
class SinkEvent {
  enum Kind { NOTE, BLOCK_BEGIN, BLOCK_APPEND, BLOCK_END, MESSAGE }
  Kind kind
  String text
}
```

Create `src/main/groovy/se/alipsa/lca/gui/TurnSink.groovy`:

```groovy
package se.alipsa.lca.gui

import groovy.transform.CompileStatic

/**
 * Streaming target for one GUI turn. Implementations marshal these calls onto the EDT; callers
 * (the turn pipeline) may invoke {@code append} concurrently from more than one background thread
 * (stdout and stderr reader threads), so implementations must tolerate concurrent calls.
 */
@CompileStatic
interface TurnSink {
  /** A routing / confidence / status / error line shown above content. */
  void note(String text)
  /** Open a live block (rendered as a fenced code block) for streamed shell output. */
  void beginBlock()
  /** Append one line to the currently open live block. */
  void append(String line)
  /** Close the live block, committing it to the transcript. */
  void endBlock()
  /** A completed content chunk (e.g. one command's result), rendered as Markdown. */
  void message(String markdown)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=SinkEventSpec`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/groovy/se/alipsa/lca/gui/SinkEvent.groovy \
        src/main/groovy/se/alipsa/lca/gui/TurnSink.groovy \
        src/test/groovy/se/alipsa/lca/gui/SinkEventSpec.groovy
git commit -m "feat(gui): add TurnSink interface and SinkEvent type for streaming turns"
```

---

## Task 2: Decompose `formatCapturedShellResult` into `shellHeader` / `shellFooter`

**Files:**
- Modify: `src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy` (`formatCapturedShellResult`, ~line 1277)
- Test: `src/test/groovy/se/alipsa/lca/shell/ShellCommandsSpec.groovy`

**Interfaces:**
- Produces: `private static String shellHeader(String command)` → `"$ " + command`; `private static String shellFooter(CommandRunner.CommandResult result)` → e.g. `"[exit 0]"`, `"[exit 1 — failed]"`, `"[exit timeout — failed]"`, with `" (output truncated)"` appended when `result.truncated`.

- [ ] **Step 1: Write the failing test**

Add to `src/test/groovy/se/alipsa/lca/shell/ShellCommandsSpec.groovy` (inside the existing class). These call the private helpers via Groovy dynamic dispatch on the metaclass — because `ShellCommandsSpec` already constructs a `ShellCommands`, reuse that instance (assume it is named `shellCommands`; if the existing spec uses another name, match it):

```groovy
  def "shellHeader renders the command with a prompt prefix"() {
    expect:
    ShellCommands.shellHeader("git status") == '$ git status'
  }

  def "shellFooter renders exit status and truncation"() {
    given:
    def ok = new se.alipsa.lca.tools.CommandRunner.CommandResult(
      exitCode: 0, success: true, timedOut: false, truncated: false)
    def failTrunc = new se.alipsa.lca.tools.CommandRunner.CommandResult(
      exitCode: 2, success: false, timedOut: false, truncated: true)
    def timedOut = new se.alipsa.lca.tools.CommandRunner.CommandResult(
      exitCode: -1, success: false, timedOut: true, truncated: false)

    expect:
    ShellCommands.shellFooter(ok) == '[exit 0]'
    ShellCommands.shellFooter(failTrunc) == '[exit 2 — failed] (output truncated)'
    ShellCommands.shellFooter(timedOut) == '[exit timeout — failed]'
  }
```

> Note: `shellHeader`/`shellFooter` must be package-private or public static (drop `private`) so the spec in the same package can call them. Use no access modifier (Groovy defaults to public) but keep them `static`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=ShellCommandsSpec`
Expected: FAIL — `No signature of method: ...ShellCommands.shellHeader`.

- [ ] **Step 3: Refactor the formatter**

In `src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy`, replace the existing `formatCapturedShellResult` method (currently ~lines 1277–1298) with the decomposed version:

```groovy
  static String shellHeader(String command) {
    '$ ' + command
  }

  static String shellFooter(CommandRunner.CommandResult result) {
    if (result == null) {
      return "(no result)"
    }
    StringBuilder builder = new StringBuilder("[exit ")
    if (result.timedOut) {
      builder.append("timeout")
    } else {
      builder.append(result.exitCode)
    }
    builder.append(result.success ? "]" : " — failed]")
    if (result.truncated) {
      builder.append(" (output truncated)")
    }
    builder.toString()
  }

  private static String formatCapturedShellResult(String command, CommandRunner.CommandResult result, String output) {
    StringBuilder builder = new StringBuilder()
    builder.append(shellHeader(command)).append("\n")
    if (result == null) {
      return builder.append("(no result)").toString()
    }
    String out = output != null ? output.stripTrailing() : ""
    if (!out.isEmpty()) {
      builder.append(out).append("\n")
    }
    builder.append(shellFooter(result))
    builder.toString().stripTrailing()
  }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -Dtest=ShellCommandsSpec`
Expected: PASS (existing tests + 2 new). The composed output is unchanged from before the refactor.

- [ ] **Step 5: Commit**

```bash
git add src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy \
        src/test/groovy/se/alipsa/lca/shell/ShellCommandsSpec.groovy
git commit -m "refactor(shell): split shell header/footer out of formatCapturedShellResult"
```

---

## Task 3: Streaming `executeShell` overload + bounded `captured` buffer

**Files:**
- Modify: `src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy` (`executeShell` ~1240, `shellCommandCaptured` ~1236; add `import java.util.function.Consumer`)
- Test: `src/test/groovy/se/alipsa/lca/shell/ShellCommandsSpec.groovy`

**Interfaces:**
- Consumes: `CommandRunner.OutputListener` (`void onLine(String stream, String line)`), `CommandRunner.runStreaming(String, long, int, OutputListener)`, `shellHeader`/`shellFooter` (Task 2).
- Produces:
  - `private ShellExecution executeShell(String command, String session, boolean streamToConsole, Consumer<String> lineConsumer)` — when `lineConsumer != null`, forwards each line to it, does **not** accumulate `captured`, does **not** call the formatters, and returns `new ShellExecution(footer, footer)` where `footer = shellFooter(result)`.
  - `private ShellExecution executeShell(String command, String session, boolean streamToConsole)` → delegates with `null`.
  - `String shellCommandCaptured(String command, String session, Consumer<String> lineConsumer)` → `executeShell(command, session, false, lineConsumer).captured`.

- [ ] **Step 1: Write the failing test**

Add to `src/test/groovy/se/alipsa/lca/shell/ShellCommandsSpec.groovy`:

```groovy
  def "shellCommandCaptured streams each line to the consumer and returns only the footer"() {
    given:
    List<String> lines = []

    when:
    String footer = shellCommands.shellCommandCaptured("printf 'a\\nb\\nc\\n'", "default",
      { String line -> lines << line } as java.util.function.Consumer)

    then:
    lines == ["a", "b", "c"]
    footer.startsWith("[exit 0]")
    !footer.contains("\$ printf")   // body/header are NOT in the streamed return value
  }

  def "non-streaming shellCommandCaptured still includes header, body and footer"() {
    when:
    String captured = shellCommands.shellCommandCaptured("printf 'x\\n'", "default")

    then:
    captured.contains('$ printf')
    captured.contains("x")
    captured.contains("[exit 0]")
  }

  def "streams lines from both stdout and stderr to the consumer"() {
    given:
    List<String> lines = Collections.synchronizedList([] as List<String>)

    when:
    // o1/o2 on stdout, e1/e2 on stderr — read by two separate threads inside CommandRunner.
    shellCommands.shellCommandCaptured("printf 'o1\\no2\\n'; printf 'e1\\ne2\\n' 1>&2", "default",
      { String line -> lines << line } as java.util.function.Consumer)

    then:
    // Every line from each stream is delivered (set membership); per-stream order is preserved;
    // cross-stream interleaving order is unspecified, so it is NOT asserted.
    lines.toSet() == ["o1", "o2", "e1", "e2"].toSet()
    lines.indexOf("o1") < lines.indexOf("o2")
    lines.indexOf("e1") < lines.indexOf("e2")
  }
```

> These run a real `bash -lc` via the existing `CommandRunner` the spec already wires up. If `ShellCommandsSpec` does not already construct a usable `ShellCommands` with a real `CommandRunner`/`CommandPolicy`, add a `@Requires({ os.macOs || os.linux })` guard and build one in `setup()` mirroring the existing shell tests in this file.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=ShellCommandsSpec`
Expected: FAIL — no 3-arg `shellCommandCaptured` overload.

- [ ] **Step 3: Add the overloads and cap the buffer**

In `ShellCommands.groovy` add near the other imports:

```groovy
import java.util.function.Consumer
```

Replace `shellCommandCaptured` (single method ~1236) with two overloads:

```groovy
  String shellCommandCaptured(String command, String session) {
    executeShell(command, session, false, null).captured
  }

  String shellCommandCaptured(String command, String session, Consumer<String> lineConsumer) {
    executeShell(command, session, false, lineConsumer).captured
  }
```

Replace the whole `executeShell` method (~1240–1275) with a 4-arg version plus a 3-arg delegate:

```groovy
  private ShellExecution executeShell(String command, String session, boolean streamToConsole) {
    executeShell(command, session, streamToConsole, null)
  }

  private ShellExecution executeShell(String command, String session, boolean streamToConsole,
                                      Consumer<String> lineConsumer) {
    String trimmed = requireNonBlank(command, "command").trim()
    CommandPolicy.Decision decision = commandPolicy.evaluate(trimmed)
    if (!decision.allowed) {
      String blocked = decision.message ?: "Command blocked by policy."
      return new ShellExecution(blocked, blocked)
    }
    boolean streaming = lineConsumer != null
    StringBuilder captured = streaming ? null : new StringBuilder()
    CommandRunner.OutputListener listener = { String stream, String line ->
      if (streamToConsole) {
        if ("ERR" == stream) {
          System.err.println(line)
          System.err.flush()
        } else {
          println(line)
        }
      }
      if (streaming) {
        lineConsumer.accept(line)
      } else {
        synchronized (captured) {
          if (captured.length() < DIRECT_SHELL_MAX_OUTPUT_CHARS) {
            captured.append(line).append(System.lineSeparator())
          }
        }
      }
    } as CommandRunner.OutputListener
    CommandRunner.CommandResult result = commandRunner.runStreaming(
      trimmed,
      DIRECT_SHELL_TIMEOUT_MILLIS,
      DIRECT_SHELL_MAX_OUTPUT_CHARS,
      listener
    )
    String summary = summarizeOutput(result?.output, 5, DIRECT_SHELL_SUMMARY_MAX_CHARS)
    sessionState.appendHistory(
      session,
      "Shell command: ${trimmed}",
      "Exit ${result?.timedOut ? 'timeout' : result?.exitCode}; ${summary}"
    )
    appendShellCommandToConversation(session, trimmed, result)
    if (streaming) {
      // The body has already been delivered line-by-line via lineConsumer; do not build the
      // full-body captured/summary strings that nobody would read. Return just the footer so the
      // caller can close the streamed block.
      String footer = shellFooter(result)
      return new ShellExecution(footer, footer)
    }
    new ShellExecution(
      formatDirectShellResult(trimmed, result),
      formatCapturedShellResult(trimmed, result, captured.toString())
    )
  }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -Dtest=ShellCommandsSpec`
Expected: PASS (new + existing). `shellCommand` (REPL) is untouched: it calls the 3-arg `executeShell(..., true)` → delegates with `null` → non-streaming → `formatDirectShellResult`.

- [ ] **Step 5: Commit**

```bash
git add src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy \
        src/test/groovy/se/alipsa/lca/shell/ShellCommandsSpec.groovy
git commit -m "feat(shell): stream captured output per line and bound the captured buffer"
```

---

## Task 4: `BangCommandHandler` streaming overload

**Files:**
- Modify: `src/main/groovy/se/alipsa/lca/shell/BangCommandHandler.groovy`
- Test: `src/test/groovy/se/alipsa/lca/shell/BangCommandHandlerSpec.groovy`

**Interfaces:**
- Consumes: `ShellCommands.shellCommandCaptured(String, String, Consumer<String>)` (Task 3).
- Produces: `String handle(String input, String session, boolean captureOutput, Consumer<String> lineConsumer)`; `String strip(String input)` (command after the leading `!`, trimmed). Existing `handle(input, session, captureOutput)` delegates with `null`.

- [ ] **Step 1: Write the failing test**

Add to `src/test/groovy/se/alipsa/lca/shell/BangCommandHandlerSpec.groovy`:

```groovy
  def "strip returns the command after the bang"() {
    expect:
    handler.strip("!  git status ") == "git status"
  }

  def "the streaming overload passes the line consumer to shellCommandCaptured"() {
    given:
    def consumer = { String l -> } as java.util.function.Consumer

    when:
    handler.handle("! ls", "s1", true, consumer)

    then:
    1 * shellCommands.shellCommandCaptured("ls", "s1", consumer) >> "[exit 0]"
  }

  def "the streaming overload reports usage for a bare bang without calling the shell"() {
    when:
    String out = handler.handle("!   ", "s1", true, { String l -> } as java.util.function.Consumer)

    then:
    0 * shellCommands.shellCommandCaptured(_, _, _)
    out == "Usage: ! <shell command>"
  }
```

> Match the existing field names in `BangCommandHandlerSpec` (assumed `handler` and the `shellCommands` Mock). If they differ, adjust.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=BangCommandHandlerSpec`
Expected: FAIL — no 4-arg `handle` / no `strip`.

- [ ] **Step 3: Add the overload and helper**

In `BangCommandHandler.groovy` add the import:

```groovy
import java.util.function.Consumer
```

Replace the existing `handle(String input, String session, boolean captureOutput)` method body with a delegating overload plus the full 4-arg version, and add `strip`:

```groovy
  String handle(String input, String session, boolean captureOutput) {
    handle(input, session, captureOutput, null)
  }

  String handle(String input, String session, boolean captureOutput, Consumer<String> lineConsumer) {
    if (!isBang(input)) {
      return ""
    }
    String command = strip(input)
    if (command.isEmpty()) {
      return "Usage: ! <shell command>"
    }
    String sessionId = session ?: "default"
    if (captureOutput) {
      return shellCommands.shellCommandCaptured(command, sessionId, lineConsumer)
    }
    // Console path streams live to stdout; the line consumer is not used there.
    shellCommands.shellCommand(command, sessionId)
  }

  /** The shell command after the leading {@code !}, trimmed. */
  String strip(String input) {
    input.trim().substring(BANG.length()).trim()
  }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -Dtest=BangCommandHandlerSpec`
Expected: PASS (new + existing, including the existing 3-arg tests which now route through the delegate).

- [ ] **Step 5: Commit**

```bash
git add src/main/groovy/se/alipsa/lca/shell/BangCommandHandler.groovy \
        src/test/groovy/se/alipsa/lca/shell/BangCommandHandlerSpec.groovy
git commit -m "feat(shell): add streaming (line-consumer) overload to BangCommandHandler"
```

---

## Task 5: `ConversationView` live block + coalescing

**Files:**
- Modify: `src/main/groovy/se/alipsa/lca/gui/ConversationView.groovy`
- Test: `src/test/groovy/se/alipsa/lca/gui/ConversationViewSpec.groovy`

**Interfaces:**
- Produces: `void beginBlock()`, `void appendBlock(String line)`, `void endBlock()`, and package-private `String snapshotHtml()` returning the current full document (committed body + any open live block). Existing `addUserMessage`/`addAssistantMessage`/`addNote`/`clear` unchanged.

- [ ] **Step 1: Write the failing test**

Create `src/test/groovy/se/alipsa/lca/gui/ConversationViewSpec.groovy`:

```groovy
package se.alipsa.lca.gui

import spock.lang.Specification

class ConversationViewSpec extends Specification {

  ConversationView view = new ConversationView(new MarkdownRenderer())

  def "an open block shows appended lines, escaped"() {
    when:
    view.beginBlock()
    view.appendBlock('$ echo <hi>')
    view.appendBlock("<hi>")

    then:
    String html = view.snapshotHtml()
    html.contains("&lt;hi&gt;")      // HTML-escaped
    !html.contains("<hi>")           // raw angle brackets never leak into the document
  }

  def "endBlock commits the block so later content follows it"() {
    when:
    view.beginBlock()
    view.appendBlock("line-1")
    view.endBlock()
    view.addNote("after")

    then:
    String html = view.snapshotHtml()
    html.contains("line-1")
    html.indexOf("line-1") < html.indexOf("after")
  }

  def "clear removes committed blocks"() {
    given:
    view.beginBlock()
    view.appendBlock("gone")
    view.endBlock()

    when:
    view.clear()

    then:
    !view.snapshotHtml().contains("gone")
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=ConversationViewSpec`
Expected: FAIL — no `beginBlock`/`snapshotHtml`.

- [ ] **Step 3: Implement the live block**

Edit `src/main/groovy/se/alipsa/lca/gui/ConversationView.groovy`. Add imports at the top with the others:

```groovy
import javax.swing.Timer
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
```

Add fields alongside the existing `body`:

```groovy
  private final StringBuilder liveBlock = new StringBuilder()
  private boolean blockOpen = false
  private final Timer coalesceTimer
```

In the constructor, after `pane.setBackground(...)` and before `render()`, initialise the timer (non-repeating, ~60 ms):

```groovy
    coalesceTimer = new Timer(60, { ActionEvent e -> render() } as ActionListener)
    coalesceTimer.setRepeats(false)
```

Add the block methods (place them after `addAssistantMessage`):

```groovy
  /** Open a live, growing block rendered as a fenced code region. */
  void beginBlock() {
    liveBlock.setLength(0)
    blockOpen = true
    renderCoalesced()
  }

  /** Append one line to the open live block (opening one if needed). */
  void appendBlock(String line) {
    if (!blockOpen) {
      beginBlock()
    }
    liveBlock.append(MarkdownRenderer.escapeHtml(line ?: "")).append("\n")
    renderCoalesced()
  }

  /** Commit the live block to the transcript and flush immediately. */
  void endBlock() {
    if (blockOpen && liveBlock.length() > 0) {
      body.append("<div class='msg-assistant'><div class='role'>lca</div><pre>")
        .append(liveBlock).append("</pre></div>")
    }
    blockOpen = false
    liveBlock.setLength(0)
    coalesceTimer.stop()
    render()
  }
```

Update `clear()` to also drop any open block:

```groovy
  void clear() {
    body.setLength(0)
    liveBlock.setLength(0)
    blockOpen = false
    render()
  }
```

Replace `render()` with a version built from a pure document builder, and add `snapshotHtml`/`renderCoalesced`:

```groovy
  /** Coalesce bursts of appends into at most one render per timer window. */
  private void renderCoalesced() {
    if (!coalesceTimer.isRunning()) {
      coalesceTimer.start()
    }
  }

  /** The full HTML document for the current state (committed body + any open live block). */
  String snapshotHtml() {
    StringBuilder live = new StringBuilder()
    if (blockOpen && liveBlock.length() > 0) {
      live.append("<div class='msg-assistant'><div class='role'>lca</div><pre>")
        .append(liveBlock).append("</pre></div>")
    }
    "<html><head><style>${markdownRenderer.css()}${EXTRA_CSS}</style></head><body>${body}${live}</body></html>".toString()
  }

  private void render() {
    String doc = snapshotHtml()
    pane.setText(doc)
    pane.setCaretPosition(pane.getDocument().getLength())
  }
```

> The existing `render()` (the one built inline in the constructor era) is fully replaced by the two methods above. Tests assert on `snapshotHtml()` so they do not depend on the async `Timer`; in production `process()` runs on the EDT and the timer bounds render frequency during noisy output.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -Dtest=ConversationViewSpec`
Expected: PASS (3 tests). Then run the existing GUI specs to be safe: `./mvnw test -Dtest='MarkdownRendererSpec,ConversationViewSpec'`.

- [ ] **Step 5: Commit**

```bash
git add src/main/groovy/se/alipsa/lca/gui/ConversationView.groovy \
        src/test/groovy/se/alipsa/lca/gui/ConversationViewSpec.groovy
git commit -m "feat(gui): add streaming live block with coalesced rendering to ConversationView"
```

---

## Task 6: `SinkEventDispatcher`

**Files:**
- Create: `src/main/groovy/se/alipsa/lca/gui/SinkEventDispatcher.groovy`
- Test: `src/test/groovy/se/alipsa/lca/gui/SinkEventDispatcherSpec.groovy`

**Interfaces:**
- Consumes: `SinkEvent` (Task 1), `ConversationView.addNote/beginBlock/appendBlock/endBlock/addAssistantMessage` (Task 5).
- Produces: `static void apply(ConversationView view, SinkEvent event)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/groovy/se/alipsa/lca/gui/SinkEventDispatcherSpec.groovy`:

```groovy
package se.alipsa.lca.gui

import spock.lang.Specification

class SinkEventDispatcherSpec extends Specification {

  ConversationView view = Mock()

  def "routes #kind to the matching ConversationView call"() {
    when:
    SinkEventDispatcher.apply(view, new SinkEvent(kind, text))

    then:
    noteCalls * view.addNote(text)
    beginCalls * view.beginBlock()
    appendCalls * view.appendBlock(text)
    endCalls * view.endBlock()
    msgCalls * view.addAssistantMessage(text)

    where:
    kind                        | text     || noteCalls | beginCalls | appendCalls | endCalls | msgCalls
    SinkEvent.Kind.NOTE         | "n"      || 1         | 0          | 0           | 0        | 0
    SinkEvent.Kind.BLOCK_BEGIN  | null     || 0         | 1          | 0           | 0        | 0
    SinkEvent.Kind.BLOCK_APPEND | "l"      || 0         | 0          | 1           | 0        | 0
    SinkEvent.Kind.BLOCK_END    | null     || 0         | 0          | 0           | 1        | 0
    SinkEvent.Kind.MESSAGE      | "m"      || 0         | 0          | 0           | 0        | 1
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=SinkEventDispatcherSpec`
Expected: FAIL — `unable to resolve class ...SinkEventDispatcher`.

- [ ] **Step 3: Implement the dispatcher**

Create `src/main/groovy/se/alipsa/lca/gui/SinkEventDispatcher.groovy`:

```groovy
package se.alipsa.lca.gui

import groovy.transform.CompileStatic

/**
 * Applies a {@link SinkEvent} to a {@link ConversationView}. Pure mapping with no threading of its
 * own — the caller ({@code LcaMainFrame}'s worker) invokes it on the EDT.
 */
@CompileStatic
class SinkEventDispatcher {

  static void apply(ConversationView view, SinkEvent event) {
    switch (event.kind) {
      case SinkEvent.Kind.NOTE:
        view.addNote(event.text)
        break
      case SinkEvent.Kind.BLOCK_BEGIN:
        view.beginBlock()
        break
      case SinkEvent.Kind.BLOCK_APPEND:
        view.appendBlock(event.text)
        break
      case SinkEvent.Kind.BLOCK_END:
        view.endBlock()
        break
      case SinkEvent.Kind.MESSAGE:
        view.addAssistantMessage(event.text)
        break
    }
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=SinkEventDispatcherSpec`
Expected: PASS (5 iterations).

- [ ] **Step 5: Commit**

```bash
git add src/main/groovy/se/alipsa/lca/gui/SinkEventDispatcher.groovy \
        src/test/groovy/se/alipsa/lca/gui/SinkEventDispatcherSpec.groovy
git commit -m "feat(gui): add SinkEventDispatcher mapping events to ConversationView"
```

---

## Task 7: Sink-based turn pipeline + frame wiring

This is the one signature-changing task. It reduces `TurnResult`, switches `GuiTurnController.process` to emit into a `TurnSink`, and rewrites `LcaMainFrame`'s worker to be that sink. All three change together so the build stays green.

**Files:**
- Modify: `src/main/groovy/se/alipsa/lca/gui/TurnResult.groovy`
- Modify: `src/main/groovy/se/alipsa/lca/gui/GuiTurnController.groovy` (add `import java.util.function.Consumer`, `import java.util.concurrent.atomic.AtomicInteger`, `import java.util.concurrent.atomic.AtomicBoolean`)
- Modify: `src/main/groovy/se/alipsa/lca/gui/LcaMainFrame.groovy`
- Test: `src/test/groovy/se/alipsa/lca/gui/GuiTurnControllerSpec.groovy` (rewrite against the sink)

**Interfaces:**
- Consumes: `TurnSink`, `SinkEvent`, `SinkEventDispatcher`, `BangCommandHandler.handle(input, session, true, Consumer)` and `.strip(input)`, `ConversationView` block methods.
- Produces:
  - `TurnResult` with fields `boolean understood`, `GuiAction action` (default `NONE`); `@Canonical` tuple constructors `TurnResult(boolean)` and `TurnResult(boolean, GuiAction)`.
  - `TurnResult GuiTurnController.process(String input, TurnSink sink)`.
  - `static Consumer<String> GuiTurnController.budgetedForwarder(TurnSink sink, int maxLines)` — forwards up to `maxLines` lines to `sink.append`, then emits a single `… output truncated in view …` marker; thread-safe.

- [ ] **Step 1: Reduce `TurnResult`**

Replace `src/main/groovy/se/alipsa/lca/gui/TurnResult.groovy` with:

```groovy
package se.alipsa.lca.gui

import groovy.transform.Canonical
import groovy.transform.CompileStatic

/**
 * The control-state outcome of one GUI turn. Textual content is delivered through the
 * {@link TurnSink} as it is produced, so this only carries what the frame must act on.
 *
 * @param understood {@code false} when intent routing produced no runnable command
 * @param action     a UI-level action the frame must perform (exit/clear); defaults to NONE
 */
@Canonical
@CompileStatic
class TurnResult {
  boolean understood
  GuiAction action = GuiAction.NONE
}
```

- [ ] **Step 2: Write the failing tests (rewrite `GuiTurnControllerSpec`)**

Replace `src/test/groovy/se/alipsa/lca/gui/GuiTurnControllerSpec.groovy` with:

```groovy
package se.alipsa.lca.gui

import se.alipsa.lca.intent.IntentCommandRouter
import se.alipsa.lca.intent.IntentRouterResult
import se.alipsa.lca.intent.IntentRoutingOutcome
import se.alipsa.lca.intent.IntentRoutingPlan
import se.alipsa.lca.repl.CommandExecutor
import se.alipsa.lca.shell.BangCommandHandler
import spock.lang.Specification

import java.util.function.Consumer

class GuiTurnControllerSpec extends Specification {

  IntentCommandRouter router = Mock()
  CommandExecutor executor = Mock()
  BangCommandHandler bangCommandHandler = Mock()
  GuiTurnController controller = new GuiTurnController(router, executor, bangCommandHandler, 0.6d)

  /** Records sink calls as strings so ordering can be asserted. */
  static class RecordingSink implements TurnSink {
    List<String> events = []
    void note(String t) { events << "note:${t}".toString() }
    void beginBlock() { events << "begin" }
    void append(String line) { events << "append:${line}".toString() }
    void endBlock() { events << "end" }
    void message(String m) { events << "msg:${m}".toString() }
  }

  RecordingSink sink = new RecordingSink()

  def "high-confidence single command emits one message and no note"() {
    given:
    router.routeDetails("do it") >> new IntentRoutingOutcome(new IntentRoutingPlan(["/chat"], 0.95d, "clear"), null)
    executor.execute("/chat") >> "hello"

    when:
    TurnResult result = controller.process("do it", sink)

    then:
    result.understood
    sink.events == ["msg:hello"]
  }

  def "not-understood routing emits a note and reports understood=false"() {
    given:
    router.routeDetails(_ as String) >> new IntentRoutingOutcome(new IntentRoutingPlan([], 0.0d, ""), null)

    when:
    TurnResult result = controller.process("gibberish", sink)

    then:
    !result.understood
    sink.events.size() == 1
    sink.events[0].toLowerCase().contains("couldn't understand")
  }

  def "multiple commands emit a routing note then one message each, in order"() {
    given:
    router.routeDetails("both") >> new IntentRoutingOutcome(new IntentRoutingPlan(["/plan", "/review"], 0.9d, "x"), null)
    executor.execute("/plan") >> "planned"
    executor.execute("/review") >> "reviewed"

    when:
    controller.process("both", sink)

    then:
    sink.events == ["note:Routing to: /plan, /review", "msg:planned", "msg:reviewed"]
  }

  def "blank input does nothing"() {
    when:
    TurnResult result = controller.process("   ", sink)

    then:
    0 * router.routeDetails(_)
    result.understood
    sink.events.isEmpty()
  }

  def "a bang command streams a block: begin, header, body lines, footer, end"() {
    given:
    bangCommandHandler.isBang("! ls") >> true
    bangCommandHandler.strip("! ls") >> "ls"
    bangCommandHandler.handle("! ls", "default", true, _ as Consumer) >> { args ->
      Consumer<String> c = args[3] as Consumer
      c.accept("foo")
      c.accept("bar")
      "[exit 0]"
    }

    when:
    controller.process("! ls", sink)

    then:
    0 * router.routeDetails(_)
    sink.events == ["begin", 'append:$ ls', "append:foo", "append:bar", "append:[exit 0]", "end"]
  }

  def "a bare bang reports usage without opening a block"() {
    given:
    bangCommandHandler.isBang("!") >> true
    bangCommandHandler.strip("!") >> ""

    when:
    controller.process("!", sink)

    then:
    sink.events == ["note:Usage: ! <shell command>"]
  }

  def "an explicit slash command executes directly and emits its output as a message"() {
    given:
    executor.execute("/status") >> "on branch main"

    when:
    TurnResult result = controller.process("/status", sink)

    then:
    0 * router.routeDetails(_)
    1 * executor.execute("/status") >> "on branch main"
    result.understood
    sink.events == ["msg:on branch main"]
    result.action == GuiAction.NONE
  }

  def "#input requests exit without routing or executing"() {
    when:
    TurnResult result = controller.process(input, sink)

    then:
    0 * router.routeDetails(_)
    0 * executor.execute(_)
    result.action == GuiAction.EXIT
    sink.events.isEmpty()

    where:
    input << ["exit", "quit", "/exit", "/quit", "  /Quit  "]
  }

  def "#input clears without routing or executing"() {
    when:
    TurnResult result = controller.process(input, sink)

    then:
    0 * router.routeDetails(_)
    0 * executor.execute(_)
    result.action == GuiAction.CLEAR
    sink.events.isEmpty()

    where:
    input << ["clear", "cls", "/clear", "/cls"]
  }

  def "budgetedForwarder forwards up to the budget then emits one truncation marker"() {
    given:
    Consumer<String> fwd = GuiTurnController.budgetedForwarder(sink, 3)

    when:
    (1..6).each { fwd.accept("line-${it}".toString()) }

    then:
    sink.events == ["append:line-1", "append:line-2", "append:line-3", "append:… output truncated in view …"]
  }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./mvnw test -Dtest=GuiTurnControllerSpec`
Expected: FAIL — `process(String, TurnSink)` and `budgetedForwarder` do not exist.

- [ ] **Step 4: Rewrite `GuiTurnController`**

Replace the body of `src/main/groovy/se/alipsa/lca/gui/GuiTurnController.groovy` with (keep the package and existing `@Component`/`@CompileStatic`/constructor; only `process` changes plus the new imports, constant, and helper):

```groovy
package se.alipsa.lca.gui

import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import se.alipsa.lca.intent.IntentCommandRouter
import se.alipsa.lca.intent.IntentRoutingOutcome
import se.alipsa.lca.intent.IntentRoutingPlan
import se.alipsa.lca.repl.CommandExecutor
import se.alipsa.lca.shell.BangCommandHandler

import java.util.Locale
import java.util.function.Consumer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Component
@CompileStatic
class GuiTurnController {

  private static final Logger log = LoggerFactory.getLogger(GuiTurnController)
  private static final double SHOW_ROUTING_BELOW = 0.85d
  private static final int MAX_VIEW_LINES = 5000

  private final IntentCommandRouter intentRouter
  private final CommandExecutor commandExecutor
  private final BangCommandHandler bangCommandHandler
  private final double secondOpinionThreshold

  GuiTurnController(
    IntentCommandRouter intentRouter,
    CommandExecutor commandExecutor,
    BangCommandHandler bangCommandHandler,
    @Value('${assistant.intent.second-opinion-threshold:0.6}') double secondOpinionThreshold
  ) {
    this.intentRouter = intentRouter
    this.commandExecutor = commandExecutor
    this.bangCommandHandler = bangCommandHandler
    this.secondOpinionThreshold = secondOpinionThreshold
  }

  TurnResult process(String input, TurnSink sink) {
    if (input == null || input.trim().isEmpty()) {
      return new TurnResult(true)
    }

    if (bangCommandHandler.isBang(input)) {
      String command = bangCommandHandler.strip(input)
      if (command.isEmpty()) {
        sink.note("Usage: ! <shell command>")
        return new TurnResult(true)
      }
      sink.beginBlock()
      sink.append('$ ' + command)
      String footer = bangCommandHandler.handle(input, "default", true, budgetedForwarder(sink, MAX_VIEW_LINES))
      if (footer != null && !footer.trim().isEmpty()) {
        sink.append(footer)
      }
      sink.endBlock()
      return new TurnResult(true)
    }

    String trimmed = input.trim()
    String lower = trimmed.toLowerCase(Locale.ROOT)
    if (lower in ["exit", "quit", "/exit", "/quit"]) {
      return new TurnResult(true, GuiAction.EXIT)
    }
    if (lower in ["clear", "cls", "/clear", "/cls"]) {
      return new TurnResult(true, GuiAction.CLEAR)
    }

    if (trimmed.startsWith("/")) {
      try {
        String output = commandExecutor.execute(trimmed)
        if (output != null && !output.trim().isEmpty()) {
          sink.message(output)
        }
        return new TurnResult(true)
      } catch (Exception e) {
        log.error("Error executing command: {}", trimmed, e)
        sink.note("Error: ${e.message}".toString())
        return new TurnResult(true)
      }
    }

    try {
      IntentRoutingOutcome outcome = intentRouter.routeDetails(input)
      IntentRoutingPlan plan = outcome?.plan
      if (plan == null || plan.commands == null || plan.commands.isEmpty()) {
        sink.note("I couldn't understand that. Try rephrasing or use /help.")
        return new TurnResult(false)
      }
      String note = buildNote(plan, outcome)
      if (note != null) {
        sink.note(note)
      }
      for (String command : plan.commands) {
        String result = commandExecutor.execute(command)
        if (result != null && !result.trim().isEmpty()) {
          sink.message(result)
        }
      }
      new TurnResult(true)
    } catch (Exception e) {
      log.error("Error processing GUI input: {}", input, e)
      sink.note("Error: ${e.message}".toString())
      new TurnResult(true)
    }
  }

  /**
   * A thread-safe consumer that forwards up to {@code maxLines} lines to the sink, then emits a
   * single truncation marker. May be called concurrently from the stdout and stderr reader threads.
   */
  static Consumer<String> budgetedForwarder(TurnSink sink, int maxLines) {
    AtomicInteger count = new AtomicInteger()
    AtomicBoolean marked = new AtomicBoolean()
    { String line ->
      int n = count.incrementAndGet()
      if (n <= maxLines) {
        sink.append(line)
      } else if (marked.compareAndSet(false, true)) {
        sink.append("… output truncated in view …")
      }
    } as Consumer<String>
  }

  private String buildNote(IntentRoutingPlan plan, IntentRoutingOutcome outcome) {
    boolean multi = plan.commands.size() > 1
    boolean lowish = plan.confidence < SHOW_ROUTING_BELOW
    if (!multi && !lowish) {
      return null
    }
    StringBuilder note = new StringBuilder("Routing to: ").append(plan.commands.join(", "))
    if (lowish && !multi) {
      note.append(" (confidence ")
        .append(String.format(Locale.UK, "%.0f%%", plan.confidence * 100))
      if (outcome?.result?.usedSecondOpinion) {
        note.append(", second opinion")
      }
      if (plan.confidence < secondOpinionThreshold) {
        note.append(", low confidence")
      }
      note.append(")")
    }
    note.toString()
  }
}
```

- [ ] **Step 5: Run the controller tests**

Run: `./mvnw test -Dtest=GuiTurnControllerSpec`
Expected: PASS (all cases, including the low-confidence note case which is covered by "multiple commands" plus routing — if you removed the explicit low-confidence single-command assertion, re-add one mirroring the old test: `router.routeDetails("maybe") >> ...confidence 0.5...` then assert `sink.events[0]` contains `"50%"`, `"second opinion"`, `"low confidence"`).

> Add this case to the spec (it was in the old spec and must survive):

```groovy
  def "low-confidence single command note shows confidence, second opinion and low-confidence flag"() {
    given:
    IntentRouterResult routerResult = new IntentRouterResult()
    routerResult.usedSecondOpinion = true
    router.routeDetails("maybe") >> new IntentRoutingOutcome(new IntentRoutingPlan(["/chat"], 0.5d, "meh"), routerResult)
    executor.execute("/chat") >> "answer"

    when:
    controller.process("maybe", sink)

    then:
    sink.events[0].contains("50%")
    sink.events[0].contains("second opinion")
    sink.events[0].contains("low confidence")
    sink.events[1] == "msg:answer"
  }
```

- [ ] **Step 6: Rewrite `LcaMainFrame` to stream**

Replace `submit()` and the worker in `src/main/groovy/se/alipsa/lca/gui/LcaMainFrame.groovy`. Add these imports with the existing ones:

```groovy
import java.util.List
```

Replace the entire `submit()` method (and its inline `SwingWorker`) with:

```groovy
  private void submit() {
    String text = inputArea.getText()
    if (text == null || text.trim().isEmpty()) {
      return
    }
    conversationView.addUserMessage(text)
    inputArea.setText("")
    setBusy(true)
    new StreamingWorker(text).execute()
  }

  /**
   * Runs one turn off the EDT and streams its output back. The worker itself is the {@link TurnSink}:
   * its sink methods run on background thread(s) and {@code publish} a {@link SinkEvent}; {@code process}
   * applies each event to the transcript on the EDT.
   */
  private class StreamingWorker extends SwingWorker<TurnResult, SinkEvent> implements TurnSink {

    private final String input

    StreamingWorker(String input) {
      this.input = input
    }

    @Override
    protected TurnResult doInBackground() throws Exception {
      turnController.process(input, this)
    }

    @Override
    void note(String text) { publish(new SinkEvent(SinkEvent.Kind.NOTE, text)) }

    @Override
    void beginBlock() { publish(new SinkEvent(SinkEvent.Kind.BLOCK_BEGIN, null)) }

    @Override
    void append(String line) { publish(new SinkEvent(SinkEvent.Kind.BLOCK_APPEND, line)) }

    @Override
    void endBlock() { publish(new SinkEvent(SinkEvent.Kind.BLOCK_END, null)) }

    @Override
    void message(String markdown) { publish(new SinkEvent(SinkEvent.Kind.MESSAGE, markdown)) }

    @Override
    protected void process(List<SinkEvent> chunks) {
      for (SinkEvent event : chunks) {
        SinkEventDispatcher.apply(conversationView, event)
      }
    }

    @Override
    protected void done() {
      TurnResult result
      try {
        result = get()
      } catch (Exception e) {
        conversationView.addNote("Error: ${e.message}".toString())
        result = new TurnResult(true, GuiAction.NONE)
      }
      if (result?.action == GuiAction.EXIT) {
        dispose()
        System.exit(0)
        return
      }
      if (result?.action == GuiAction.CLEAR) {
        conversationView.clear()
      }
      footerBar.refresh()
      headerBar.refresh()
      setBusy(false)
      inputArea.requestFocusInWindow()
    }
  }
```

Delete the now-unused `SESSION_ID` constant only if the compiler flags it as unused; otherwise leave it. Remove any leftover `import java.awt.event.ActionEvent` usage only if it becomes unused (the submit button listener still uses `ActionListener`, so keep those imports).

- [ ] **Step 7: Compile and run the full suite**

Run: `./mvnw -q -DskipTests compile`
Expected: no compile errors.

Run: `./mvnw test`
Expected: BUILD SUCCESS, 0 failures. (`GuiTurnControllerSpec`, `ConversationViewSpec`, `SinkEventSpec`, `SinkEventDispatcherSpec`, `ShellCommandsSpec`, `BangCommandHandlerSpec` all green.)

- [ ] **Step 8: Manual end-to-end check**

```bash
./localInstall.sh && lcaGui
```
Verify:
1. `! printf 'one\ntwo\nthree\n'` → lines appear inside a code block, with `$ printf ...` header and a `[exit 0]` footer.
2. `! mvn -q -version` (or any noisy command) → output appears progressively, not all at once; the window stays responsive.
3. A multi-step request that routes to two commands → the routing note appears first, then each result as it completes.
4. `/status` → prints directly; `/exit` closes the window; `/clear` empties the transcript.

- [ ] **Step 9: Commit**

```bash
git add src/main/groovy/se/alipsa/lca/gui/TurnResult.groovy \
        src/main/groovy/se/alipsa/lca/gui/GuiTurnController.groovy \
        src/main/groovy/se/alipsa/lca/gui/LcaMainFrame.groovy \
        src/test/groovy/se/alipsa/lca/gui/GuiTurnControllerSpec.groovy
git commit -m "feat(gui): stream shell output and chunk LLM turns through TurnSink"
```

---

## Self-Review (completed by plan author)

**Spec coverage:**
- §1 `TurnSink` → Task 1. §1 `TurnResult` reduced to control state → Task 7 Step 1.
- §2 shell line streaming (`executeShell`/`shellCommandCaptured` consumer, OUT/ERR collapse, header/footer helpers, skip formatters on streaming path, bounded `captured`) → Tasks 2 & 3; `BangCommandHandler` overload → Task 4.
- §3 chunk-by-stage LLM turns (note first, one `message` per command) → Task 7 (`process`).
- §4 `SinkEvent` → Task 1; worker `publish`/`process`, sink-is-worker (inner class → can call protected `publish`) → Task 7 Step 6; live block + coalescing → Task 5; `SinkEventDispatcher` → Task 6; consumer-side thread-safe budget → Task 7 `budgetedForwarder`; `done()` finalises only → Task 7 Step 6.
- Testing section (per-line consumer, both-streams delivery, budget test, block-ordering test, `ConversationView` buffer test, dispatcher test) → Tasks 3, 5, 6, 7. Concurrent OUT/ERR is covered by the explicit both-streams test in Task 3 (set membership + per-stream ordering; cross-stream ordering deliberately not asserted, per spec) and by the thread-safe `budgetedForwarder` (atomics).

**Placeholder scan:** none — every code step contains complete code.

**Type consistency:** `SinkEvent.Kind` names (`NOTE/BLOCK_BEGIN/BLOCK_APPEND/BLOCK_END/MESSAGE`) are identical across Tasks 1, 6, 7. `TurnSink` methods (`note/beginBlock/append/endBlock/message`) match across the interface (1), dispatcher (6), controller (7), worker (7), and the recording fake (7). `budgetedForwarder(TurnSink, int)`, `shellHeader(String)`, `shellFooter(CommandResult)`, `strip(String)`, `shellCommandCaptured(String, String, Consumer)` are used with the same signatures where referenced.
