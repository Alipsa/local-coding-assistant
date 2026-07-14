# Multi-line REPL Input Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make pasting or composing multi-line text at the `lca>` prompt work as one input instead of being fragmented line-by-line, both when the terminal delivers a paste atomically and when it doesn't.

**Architecture:** Two independent, additive changes. (1) Fix a regex bug and activate an already-written-but-dead-code paste detector (`CommandInputNormaliser`) inside `JLineRepl`, so an atomically-pasted multi-line blob (bracketed paste) is routed correctly instead of failing regex match or going through the LLM intent classifier. (2) Add a custom JLine `Parser` (`FencedPasteParser`) that recognizes `/paste`...`/end` and `^^^`...`^^^` fenced blocks via JLine's existing `EOFError`/continuation mechanism, giving a terminal-agnostic way to enter multi-line input regardless of paste support.

**Tech Stack:** Groovy 5.0.3, Spring Boot, JLine 3.26.3 (via `spring-shell-core`, not a direct dependency), Spock 2.4.

## Global Constraints

- Groovy 5.0.3, `@CompileStatic` on all new/modified classes where the existing file already uses it.
- 2-space indentation, max 120 characters per line.
- British English spelling in any user-facing text (`/help` output).
- Spock 2.4 tests for all new functionality; run `./mvnw test` after each task.
- Never commit directly to `main` — this plan continues on `feature/multiline-repl-input`.

---

## Implementation note not in the approved spec (flagging transparently)

While turning the spec into concrete code, one detail needed resolving that
the spec described only at a high level ("delegate to `super.parse(...)`
... preserving quote-based continuation"): what should happen when a fenced
block *has just closed* (closer line typed)?

Verified against the actual `DefaultParser.parse()` source (3.26.3): its
own `eofOnUnclosedQuote`/bracket-checking logic only runs when
`context != ParseContext.COMPLETE && context != ParseContext.SPLIT_LINE`.
If `FencedPasteParser`, upon detecting the block just closed, delegated to
`super.parse(line, cursor, context)` using the *original* `ACCEPT_LINE`
context, then pasted content containing a stray unmatched quote (very
plausible — a single apostrophe in prose, an unbalanced string in a code
snippet) would make `DefaultParser` throw its **own** `EOFError` and keep
the block open even though the user just typed the closing marker expecting
submission.

**Resolution:** once `FencedPasteMarkers.isClosed(line)` is true,
`FencedPasteParser` delegates to `super.parse(line, cursor,
ParseContext.COMPLETE)` instead of the original context — reusing
`DefaultParser`'s real word-splitting logic to produce a legitimate
`ParsedLine`, while skipping its validation-error checks entirely. This is
safe here because `JLineRepl` never uses the returned `ParsedLine`'s word
splitting for fenced content anyway — it re-derives content itself via
`FencedPasteMarkers.extractContent(rawLine)`, and `LineReaderImpl.finish()`
returns `buf.toString()` (the raw typed text) regardless of what
`ParsedLine` was produced (confirmed by reading `finishBuffer()`/`finish()`
in `LineReaderImpl` — the return value never derives from `ParsedLine`).

Task 4 below implements this, and includes the regression test for it
(`FencedPasteParserSpec`, "content with an unmatched quote...").

---

## File Structure

New files:
- `src/main/groovy/se/alipsa/lca/repl/FencedPasteMarkers.groovy` — pure marker-matching logic (no JLine/terminal dependency), shared by the parser and the repl.
- `src/main/groovy/se/alipsa/lca/repl/FencedPasteParser.groovy` — `DefaultParser` subclass wiring `FencedPasteMarkers` into JLine's `EOFError` continuation mechanism.
- `src/test/groovy/se/alipsa/lca/repl/FencedPasteMarkersSpec.groovy`
- `src/test/groovy/se/alipsa/lca/repl/FencedPasteParserSpec.groovy`
- `src/test/groovy/se/alipsa/lca/repl/CommandExecutorSpec.groovy` — none exists today for this class.
- `src/test/groovy/se/alipsa/lca/repl/JLineReplSpec.groovy` — none exists today for this class.

Modified files:
- `src/main/groovy/se/alipsa/lca/repl/CommandExecutor.groovy` — `DOTALL` fix, new `executePasteContent` method.
- `src/main/groovy/se/alipsa/lca/repl/JLineRepl.groovy` — new `CommandInputNormaliser` constructor param, `FencedPasteParser` wired into the `LineReader`, `handleInput`/`dispatchPaste` extracted from `start()`.
- `src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy` — one-line `/help` addition.
- `src/test/groovy/se/alipsa/lca/shell/ShellCommandsSpec.groovy` — extend the existing help-text assertion.

Deleted:
- `src/test/groovy/se/alipsa/lca/shell/ShellCommandsSpec.groovy.bak` (stray file, unrelated cleanup folded into Task 6).

---

### Task 1: Fix `CommandExecutor.COMMAND_PATTERN` DOTALL bug

**Files:**
- Modify: `src/main/groovy/se/alipsa/lca/repl/CommandExecutor.groovy:24`
- Test: `src/test/groovy/se/alipsa/lca/repl/CommandExecutorSpec.groovy` (new)

**Interfaces:**
- Consumes: nothing new.
- Produces: `CommandExecutor.execute(String)` now matches multi-line argument text. Task 2 adds to the same new spec file.

- [ ] **Step 1: Write the failing test**

Create `src/test/groovy/se/alipsa/lca/repl/CommandExecutorSpec.groovy`:

```groovy
package se.alipsa.lca.repl

import se.alipsa.lca.agent.PersonaMode
import se.alipsa.lca.review.ReviewSeverity
import se.alipsa.lca.shell.McpCommands
import se.alipsa.lca.shell.ShellCommands
import spock.lang.Specification

class CommandExecutorSpec extends Specification {

  ShellCommands shellCommands = Mock()
  McpCommands mcpCommands = Mock()
  CommandExecutor executor = new CommandExecutor(shellCommands, mcpCommands)

  def "execute matches a slash command whose argument text spans multiple lines"() {
    given:
    String command = '/review --code "line one\nline two"'

    when:
    String result = executor.execute(command)

    then:
    result != "Invalid command format. Expected: /command [args]"
    1 * shellCommands.review("line one\nline two", "", "default", null, null, null, null, null, false,
      ReviewSeverity.LOW, false, true, false, false, false, null) >> "reviewed"
    result == "reviewed"
  }

  def "execute still rejects genuinely malformed input"() {
    when:
    String result = executor.execute("not a slash command")

    then:
    result == "Invalid command format. Expected: /command [args]"
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=CommandExecutorSpec`
Expected: FAIL on `"execute matches a slash command whose argument text spans multiple lines"` — `result == "Invalid command format. Expected: /command [args]"`, not the mocked `"reviewed"` value, because `COMMAND_PATTERN`'s `matcher.matches()` fails outright on the embedded newline.

- [ ] **Step 3: Write minimal implementation**

In `src/main/groovy/se/alipsa/lca/repl/CommandExecutor.groovy`, change line 24:

```groovy
  private static final Pattern COMMAND_PATTERN = ~/^\/(\w+)\s*(.*)/
```

to:

```groovy
  private static final Pattern COMMAND_PATTERN = Pattern.compile(/^\/(\w+)\s*([\s\S]*)/)
```

(Using `[\s\S]*` rather than `(?s)` inline-flag keeps the change local to the one group that needs to cross newlines, matching the style already used for the same purpose in `parseArgs`'s `flagPattern` a few lines below.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=CommandExecutorSpec`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/groovy/se/alipsa/lca/repl/CommandExecutor.groovy src/test/groovy/se/alipsa/lca/repl/CommandExecutorSpec.groovy
git commit -m "Fix CommandExecutor regex to accept multi-line command arguments"
```

---

### Task 2: Add `CommandExecutor.executePasteContent` direct-dispatch method

**Files:**
- Modify: `src/main/groovy/se/alipsa/lca/repl/CommandExecutor.groovy`
- Test: `src/test/groovy/se/alipsa/lca/repl/CommandExecutorSpec.groovy` (from Task 1)

**Interfaces:**
- Consumes: `ShellCommands.paste(String content, String endMarker, boolean send, String session, PersonaMode persona)` (existing method, unchanged signature).
- Produces: `CommandExecutor.executePasteContent(String content, boolean send, String session, PersonaMode persona) -> String`, used by `JLineRepl` in Task 5 (both for the auto-detected bracketed-paste path and the fenced-block path).

- [ ] **Step 1: Write the failing test**

Add to `src/test/groovy/se/alipsa/lca/repl/CommandExecutorSpec.groovy`:

```groovy
  def "executePasteContent forwards directly to ShellCommands.paste without re-parsing"() {
    given:
    String content = "/review --code \"whatever\"\nmore lines that would break COMMAND_PATTERN reparsing"

    when:
    String result = executor.executePasteContent(content, true, "default", PersonaMode.CODER)

    then:
    1 * shellCommands.paste(content, "/end", true, "default", PersonaMode.CODER) >> "sent"
    result == "sent"
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=CommandExecutorSpec`
Expected: FAIL with "No signature of method: ... executePasteContent()" (method doesn't exist yet)

- [ ] **Step 3: Write minimal implementation**

In `src/main/groovy/se/alipsa/lca/repl/CommandExecutor.groovy`, add this method right after `execute(String commandLine)` (after line 100, before `executeChat`):

```groovy
  /**
   * Dispatch already-known paste content directly to ShellCommands.paste,
   * bypassing COMMAND_PATTERN/parseArgs entirely. Used by JLineRepl for
   * both auto-detected bracketed-paste blobs and closed fenced blocks,
   * neither of which should be round-tripped through a re-serialized
   * command string.
   */
  String executePasteContent(String content, boolean send, String session, PersonaMode persona) {
    shellCommands.paste(content, "/end", send, session, persona)
  }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=CommandExecutorSpec`
Expected: PASS (both tests in the file)

- [ ] **Step 5: Commit**

```bash
git add src/main/groovy/se/alipsa/lca/repl/CommandExecutor.groovy src/test/groovy/se/alipsa/lca/repl/CommandExecutorSpec.groovy
git commit -m "Add CommandExecutor.executePasteContent for direct paste dispatch"
```

---

### Task 3: `FencedPasteMarkers` — pure marker-matching logic

**Files:**
- Create: `src/main/groovy/se/alipsa/lca/repl/FencedPasteMarkers.groovy`
- Test: `src/test/groovy/se/alipsa/lca/repl/FencedPasteMarkersSpec.groovy` (new)

**Interfaces:**
- Consumes: nothing (no dependencies).
- Produces:
  - `FencedPasteMarkers.CLOSERS: Map<String, String>` — opener → required closer (`"/paste" -> "/end"`, `"^^^" -> "^^^"`).
  - `FencedPasteMarkers.openerOf(String buffer): String` — the buffer's first trimmed line if it's exactly one of the openers, else `null`.
  - `FencedPasteMarkers.isClosed(String buffer): boolean` — true iff the buffer has a recognized opener and its last trimmed line equals that opener's closer.
  - `FencedPasteMarkers.extractContent(String buffer): String` — the text between the opener and closer lines (verbatim, blank lines preserved), or `null` if not a closed fenced block. Used by both `FencedPasteParser` (Task 4) and `JLineRepl` (Task 5).

- [ ] **Step 1: Write the failing test**

Create `src/test/groovy/se/alipsa/lca/repl/FencedPasteMarkersSpec.groovy`:

```groovy
package se.alipsa.lca.repl

import spock.lang.Specification

class FencedPasteMarkersSpec extends Specification {

  def "openerOf recognises /paste and ^^^ as the sole first line"() {
    expect:
    FencedPasteMarkers.openerOf("/paste") == "/paste"
    FencedPasteMarkers.openerOf("^^^") == "^^^"
    FencedPasteMarkers.openerOf("/paste\nsome content") == "/paste"
    FencedPasteMarkers.openerOf("^^^\nsome content\n^^^") == "^^^"
  }

  def "openerOf ignores non-opener first lines, including /paste with extra args on the same line"() {
    expect:
    FencedPasteMarkers.openerOf("/paste --content foo") == null
    FencedPasteMarkers.openerOf("hello world") == null
    FencedPasteMarkers.openerOf(null) == null
  }

  def "isClosed requires a matching closer as the last line"() {
    expect:
    FencedPasteMarkers.isClosed("/paste\nsome content\n/end") == true
    FencedPasteMarkers.isClosed("^^^\nsome content\n^^^") == true
    FencedPasteMarkers.isClosed("/paste\nsome content") == false
    FencedPasteMarkers.isClosed("/paste\n/end\nmore after the closer") == false
  }

  def "isClosed treats a mismatched closer as still open"() {
    expect:
    FencedPasteMarkers.isClosed("/paste\n^^^") == false
    FencedPasteMarkers.isClosed("^^^\n/end") == false
  }

  def "extractContent strips marker lines and preserves inner blank lines"() {
    expect:
    FencedPasteMarkers.extractContent("/paste\nline one\n\nline two\n/end") == "line one\n\nline two"
    FencedPasteMarkers.extractContent("^^^\nfoo\n^^^") == "foo"
  }

  def "extractContent returns empty string for an empty block"() {
    expect:
    FencedPasteMarkers.extractContent("/paste\n/end") == ""
  }

  def "extractContent returns null when the block is not closed or has no opener"() {
    expect:
    FencedPasteMarkers.extractContent("/paste\nno closer yet") == null
    FencedPasteMarkers.extractContent("just plain text") == null
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=FencedPasteMarkersSpec`
Expected: FAIL to compile — `FencedPasteMarkers` doesn't exist yet.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/groovy/se/alipsa/lca/repl/FencedPasteMarkers.groovy`:

```groovy
package se.alipsa.lca.repl

import groovy.transform.CompileStatic

/**
 * Pure marker-matching logic for the fenced multi-line paste modes
 * (/paste ... /end and ^^^ ... ^^^). No JLine or terminal dependency, so
 * both FencedPasteParser (deciding whether to keep reading) and JLineRepl
 * (extracting the final block's content) share one definition of what
 * counts as an opener/closer pair, instead of duplicating the marker set.
 */
@CompileStatic
class FencedPasteMarkers {

  static final Map<String, String> CLOSERS = [
    "/paste": "/end",
    "^^^"   : "^^^"
  ].asImmutable()

  static String firstLineOf(String buffer) {
    int idx = buffer.indexOf('\n')
    idx == -1 ? buffer : buffer.substring(0, idx)
  }

  static String lastLineOf(String buffer) {
    int idx = buffer.lastIndexOf('\n')
    idx == -1 ? buffer : buffer.substring(idx + 1)
  }

  static String openerOf(String buffer) {
    if (buffer == null) {
      return null
    }
    String firstLine = firstLineOf(buffer).trim()
    CLOSERS.containsKey(firstLine) ? firstLine : null
  }

  static boolean isClosed(String buffer) {
    String opener = openerOf(buffer)
    opener != null && lastLineOf(buffer).trim() == CLOSERS.get(opener)
  }

  static String extractContent(String buffer) {
    if (buffer == null || !isClosed(buffer)) {
      return null
    }
    List<String> lines = buffer.split("\n", -1) as List<String>
    lines.subList(1, lines.size() - 1).join("\n")
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=FencedPasteMarkersSpec`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/groovy/se/alipsa/lca/repl/FencedPasteMarkers.groovy src/test/groovy/se/alipsa/lca/repl/FencedPasteMarkersSpec.groovy
git commit -m "Add FencedPasteMarkers for /paste and ^^^ fence matching"
```

---

### Task 4: `FencedPasteParser` — wire markers into JLine's continuation mechanism

**Files:**
- Create: `src/main/groovy/se/alipsa/lca/repl/FencedPasteParser.groovy`
- Test: `src/test/groovy/se/alipsa/lca/repl/FencedPasteParserSpec.groovy` (new)

**Interfaces:**
- Consumes: `FencedPasteMarkers.openerOf`, `FencedPasteMarkers.isClosed`, `FencedPasteMarkers.CLOSERS` (Task 3). `DefaultParser` (from `org.jline.reader.impl`, already a transitive dependency via `spring-shell-core`, resolved version 3.26.3 — verified via `mvn dependency:tree`, not assumed).
- Produces: `FencedPasteParser` — a drop-in `Parser` implementation `JLineRepl` (Task 5) constructs instead of `DefaultParser`, with the same `setEofOnUnclosedQuote`/`setEofOnEscapedNewLine` setters available (inherited, unchanged).

- [ ] **Step 1: Write the failing test**

Create `src/test/groovy/se/alipsa/lca/repl/FencedPasteParserSpec.groovy`:

```groovy
package se.alipsa.lca.repl

import org.jline.reader.EOFError
import org.jline.reader.Parser
import spock.lang.Specification

class FencedPasteParserSpec extends Specification {

  FencedPasteParser parser = new FencedPasteParser()

  def "an unterminated /paste block keeps signalling incomplete input"() {
    given:
    String line = "/paste"

    when:
    parser.parse(line, line.length(), Parser.ParseContext.ACCEPT_LINE)

    then:
    thrown(EOFError)
  }

  def "an unterminated /paste block with content still keeps signalling incomplete input"() {
    given:
    String line = "/paste\nsome content"

    when:
    parser.parse(line, line.length(), Parser.ParseContext.ACCEPT_LINE)

    then:
    thrown(EOFError)
  }

  def "a closed /paste block does not throw"() {
    given:
    String line = "/paste\nsome content\n/end"

    when:
    def result = parser.parse(line, line.length(), Parser.ParseContext.ACCEPT_LINE)

    then:
    noExceptionThrown()
    result.line() == line
  }

  def "a closed ^^^ block does not throw"() {
    given:
    String line = "^^^\nsome content\n^^^"

    when:
    def result = parser.parse(line, line.length(), Parser.ParseContext.ACCEPT_LINE)

    then:
    noExceptionThrown()
    result.line() == line
  }

  def "a mismatched closer does not close the block"() {
    given:
    String line = "/paste\n^^^"

    when:
    parser.parse(line, line.length(), Parser.ParseContext.ACCEPT_LINE)

    then:
    thrown(EOFError)
  }

  def "an unmatched quote inside pasted content does not reopen a block that just closed"() {
    given:
    parser.setEofOnUnclosedQuote(true)
    String line = "/paste\nit's unmatched\n/end"

    when:
    def result = parser.parse(line, line.length(), Parser.ParseContext.ACCEPT_LINE)

    then:
    noExceptionThrown()
    result.line() == line
  }

  def "input with no fence still gets DefaultParser's own unclosed-quote continuation"() {
    given:
    parser.setEofOnUnclosedQuote(true)
    String line = 'echo "unclosed'

    when:
    parser.parse(line, line.length(), Parser.ParseContext.ACCEPT_LINE)

    then:
    thrown(EOFError)
  }

  def "ordinary single-line input with no fence parses normally"() {
    given:
    String line = "/status"

    when:
    def result = parser.parse(line, line.length(), Parser.ParseContext.ACCEPT_LINE)

    then:
    noExceptionThrown()
    result.line() == line
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=FencedPasteParserSpec`
Expected: FAIL to compile — `FencedPasteParser` doesn't exist yet.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/groovy/se/alipsa/lca/repl/FencedPasteParser.groovy`:

```groovy
package se.alipsa.lca.repl

import groovy.transform.CompileStatic
import org.jline.reader.EOFError
import org.jline.reader.ParsedLine
import org.jline.reader.Parser
import org.jline.reader.impl.DefaultParser

/**
 * Extends DefaultParser to recognise two fenced multi-line block openers
 * (/paste ... /end and ^^^ ... ^^^), using JLine's own EOFError/ACCEPT_LINE
 * continuation mechanism (the same one DefaultParser already uses for
 * unclosed quotes) so LineReader.readLine() keeps reading physical lines
 * until the fence closes, then returns the whole block as one string.
 *
 * Once a fence closes, parsing is delegated to super.parse(..., COMPLETE)
 * rather than the original context: DefaultParser's own EOFError checks
 * (unclosed quotes/brackets) are gated on context != COMPLETE, so this
 * avoids pasted content that happens to contain a stray unmatched quote
 * re-opening a block the user just explicitly closed.
 */
@CompileStatic
class FencedPasteParser extends DefaultParser {

  @Override
  ParsedLine parse(String line, int cursor, Parser.ParseContext context) {
    if (context == Parser.ParseContext.ACCEPT_LINE) {
      String opener = FencedPasteMarkers.openerOf(line)
      if (opener != null) {
        if (!FencedPasteMarkers.isClosed(line)) {
          String closer = FencedPasteMarkers.CLOSERS.get(opener)
          throw new EOFError(-1, -1, "Paste block open, end with a line containing only '${closer}'", opener)
        }
        return super.parse(line, cursor, Parser.ParseContext.COMPLETE)
      }
    }
    super.parse(line, cursor, context)
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=FencedPasteParserSpec`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/groovy/se/alipsa/lca/repl/FencedPasteParser.groovy src/test/groovy/se/alipsa/lca/repl/FencedPasteParserSpec.groovy
git commit -m "Add FencedPasteParser for /paste and ^^^ multi-line continuation"
```

---

### Task 5: Wire auto-paste-detection and the fenced parser into `JLineRepl`

**Files:**
- Modify: `src/main/groovy/se/alipsa/lca/repl/JLineRepl.groovy`
- Test: `src/test/groovy/se/alipsa/lca/repl/JLineReplSpec.groovy` (new)

**Interfaces:**
- Consumes:
  - `CommandInputNormaliser.isPasteCandidate(String): boolean` (existing, `se.alipsa.lca.shell.CommandInputNormaliser`).
  - `CommandExecutor.executePasteContent(String, boolean, String, PersonaMode): String` (Task 2).
  - `FencedPasteMarkers.extractContent(String): String` (Task 3).
  - `FencedPasteParser` (Task 4).
- Produces: `JLineRepl.handleInput(String raw)` (package-private, no return value) — the extracted decision logic, directly unit-testable without driving a real `readLine()` call.

- [ ] **Step 1: Write the failing test**

Create `src/test/groovy/se/alipsa/lca/repl/JLineReplSpec.groovy`:

```groovy
package se.alipsa.lca.repl

import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import se.alipsa.lca.agent.PersonaMode
import se.alipsa.lca.intent.IntentCommandRouter
import se.alipsa.lca.intent.IntentRoutingOutcome
import se.alipsa.lca.intent.IntentRoutingPlan
import se.alipsa.lca.shell.CommandInputNormaliser
import se.alipsa.lca.shell.ShellSettings
import spock.lang.Specification

class JLineReplSpec extends Specification {

  IntentCommandRouter intentRouter = Mock()
  CommandExecutor commandExecutor = Mock()
  CommandInputNormaliser normaliser = new CommandInputNormaliser(new ShellSettings(true))
  Terminal terminal = TerminalBuilder.builder()
    .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
    .system(false)
    .dumb(true)
    .build()

  JLineRepl repl = new JLineRepl(intentRouter, commandExecutor, normaliser, terminal, "lca> ", null, 0.6d)

  def cleanup() {
    terminal.close()
  }

  def "a fenced /paste block dispatches directly, bypassing intent routing"() {
    when:
    repl.handleInput("/paste\nline one\nline two\n/end")

    then:
    1 * commandExecutor.executePasteContent("line one\nline two", true, "default", PersonaMode.CODER) >> "sent"
    0 * intentRouter.routeDetails(_)
  }

  def "a fenced ^^^ block dispatches directly"() {
    when:
    repl.handleInput("^^^\nfoo\nbar\n^^^")

    then:
    1 * commandExecutor.executePasteContent("foo\nbar", true, "default", PersonaMode.CODER) >> "sent"
    0 * intentRouter.routeDetails(_)
  }

  def "an empty fenced block is a no-op"() {
    when:
    repl.handleInput("/paste\n/end")

    then:
    0 * commandExecutor.executePasteContent(_, _, _, _)
    0 * intentRouter.routeDetails(_)
  }

  def "raw multi-line text with no leading slash auto-dispatches as a paste candidate"() {
    when:
    repl.handleInput("first line\nsecond line")

    then:
    1 * commandExecutor.executePasteContent("first line\nsecond line", true, "default", PersonaMode.CODER) >> "sent"
    0 * intentRouter.routeDetails(_)
  }

  def "single-line input still routes through the intent classifier as before"() {
    given:
    def plan = new IntentRoutingPlan(commands: [], confidence: 1.0d, explanation: null)

    when:
    repl.handleInput("what does this project do")

    then:
    1 * intentRouter.routeDetails("what does this project do") >> new IntentRoutingOutcome(plan: plan, result: null)
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=JLineReplSpec`
Expected: FAIL to compile — `JLineRepl` has no `handleInput` method and no `CommandInputNormaliser` constructor parameter yet.

(`IntentRoutingPlan` and `IntentRoutingOutcome` are `@Canonical` Groovy classes — verified in `src/main/groovy/se/alipsa/lca/intent/IntentRoutingPlan.groovy` and `IntentRoutingOutcome.groovy` — so the named-argument construction in the "single-line input" test above works as written with no adjustment needed.)

- [ ] **Step 3: Write minimal implementation**

In `src/main/groovy/se/alipsa/lca/repl/JLineRepl.groovy`, apply these changes:

Replace the import block (lines 1–23) — remove the now-unused `DefaultParser` import and add the two new ones:

```groovy
package se.alipsa.lca.repl

import groovy.transform.CompileStatic
import org.jline.reader.EndOfFileException
import org.jline.reader.Highlighter
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.terminal.Terminal
import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder
import org.jline.utils.AttributedStyle
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import se.alipsa.lca.agent.PersonaMode
import se.alipsa.lca.intent.IntentCommandRouter
import se.alipsa.lca.intent.IntentRoutingOutcome
import se.alipsa.lca.intent.IntentRoutingPlan
import se.alipsa.lca.shell.CommandInputNormaliser

import java.nio.file.Paths
import java.util.regex.Pattern
```

Replace the field declarations and constructor (original lines 35–90):

```groovy
  private final IntentCommandRouter intentRouter
  private final CommandExecutor commandExecutor
  private final CommandInputNormaliser commandInputNormaliser
  private final Terminal terminal
  private final LineReader lineReader
  private final String prompt
  private final double secondOpinionThreshold
  private final AttributedStyle userInputStyle
  private volatile boolean running = true

  JLineRepl(
    IntentCommandRouter intentRouter,
    CommandExecutor commandExecutor,
    CommandInputNormaliser commandInputNormaliser,
    Terminal terminal,
    @Value('${lca.repl.prompt:lca> }') String prompt,
    @Value('${lca.repl.history-file:#{null}}') String historyFile,
    @Value('${assistant.intent.second-opinion-threshold:0.6}') double secondOpinionThreshold
  ) {
    this.intentRouter = intentRouter
    this.commandExecutor = commandExecutor
    this.commandInputNormaliser = commandInputNormaliser
    this.terminal = terminal
    this.prompt = prompt
    this.secondOpinionThreshold = secondOpinionThreshold
    this.userInputStyle = AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN + AttributedStyle.BRIGHT)

    // Create line reader with history and editing
    def parser = new FencedPasteParser()
    parser.setEofOnUnclosedQuote(true)
    parser.setEofOnEscapedNewLine(true)

    def builder = LineReaderBuilder.builder()
      .terminal(terminal)
      .parser(parser)
      .appName("lca")
      .highlighter(new UserInputHighlighter(userInputStyle))
      .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
      .variable(LineReader.HISTORY_SIZE, 500)
      .variable(LineReader.BELL_STYLE, "none")
      .variable(LineReader.SECONDARY_PROMPT_PATTERN, "paste... ")

    if (historyFile != null && !historyFile.trim().isEmpty()) {
      def historyPath = Paths.get(historyFile.trim())
      // Create parent directory if it doesn't exist
      if (historyPath.parent != null) {
        historyPath.parent.toFile().mkdirs()
      }
      builder.variable(LineReader.HISTORY_FILE, historyPath)
    }

    this.lineReader = builder.build()

    // Verify terminal capabilities
    if (!terminal.type.equals("dumb")) {
      log.debug("Terminal type: {}, size: {}x{}", terminal.type, terminal.width, terminal.height)
    } else {
      log.warn("Running on a dumb terminal - line editing may be limited")
    }
  }
```

Replace the `start()` method (original lines 95–137):

```groovy
  /**
   * Start the REPL loop.
   */
  void start() {
    log.debug("JLineRepl.start() called - beginning REPL loop")
    printWelcome()

    while (running) {
      log.debug("REPL loop iteration starting, running={}", running)
      try {
        String line = lineReader.readLine(prompt)
        log.debug("Read line from terminal: '{}'", line)
        handleInput(line)
      } catch (UserInterruptException e) {
        // Ctrl+C pressed
        log.debug("User interrupt")
        terminal.writer().println("^C")
      } catch (EndOfFileException e) {
        // Ctrl+D pressed or exit command
        log.debug("End of file - exiting REPL")
        break
      } catch (Exception e) {
        log.error("Error processing input: {}", e.getClass().getName(), e)
        terminal.writer().println("Error: " + e.message)
      }
    }

    shutdown()
  }

  /**
   * Handle one completed line already read from the terminal: built-in
   * commands, closed fenced multi-line blocks, auto-detected bracketed
   * paste candidates, and otherwise ordinary intent-routed input.
   * Package-private so it can be exercised directly in tests without
   * driving a real readLine() call.
   */
  void handleInput(String raw) {
    if (raw == null || raw.trim().isEmpty()) {
      return
    }
    String trimmed = raw.trim()

    if (handleBuiltInCommand(trimmed)) {
      return
    }

    String fencedContent = FencedPasteMarkers.extractContent(raw)
    if (fencedContent != null) {
      dispatchPaste(fencedContent)
      return
    }

    if (commandInputNormaliser.isPasteCandidate(raw)) {
      dispatchPaste(raw)
      return
    }

    processInput(trimmed)
  }

  private void dispatchPaste(String content) {
    if (content.trim().isEmpty()) {
      return
    }
    String result = commandExecutor.executePasteContent(content, true, "default", PersonaMode.CODER)
    if (result != null && !result.trim().isEmpty()) {
      terminal.writer().println(result)
    }
  }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=JLineReplSpec`
Expected: PASS

- [ ] **Step 5: Run the full test suite to check for regressions**

Run: `./mvnw test`
Expected: PASS. `grep -rn "new JLineRepl(" src/` (already checked while writing this plan) confirms the only construction sites are Spring's constructor injection — which resolves the new `CommandInputNormaliser` parameter automatically since it's already an existing `@Component` — and `JLineReplSpec` itself. No other call site needs updating.

- [ ] **Step 6: Commit**

```bash
git add src/main/groovy/se/alipsa/lca/repl/JLineRepl.groovy src/test/groovy/se/alipsa/lca/repl/JLineReplSpec.groovy
git commit -m "Wire CommandInputNormaliser and FencedPasteParser into JLineRepl"
```

---

### Task 6: `/help` caveat and repo cleanup

**Files:**
- Modify: `src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy:264-265`
- Modify: `src/test/groovy/se/alipsa/lca/shell/ShellCommandsSpec.groovy:1691-1707`
- Delete: `src/test/groovy/se/alipsa/lca/shell/ShellCommandsSpec.groovy.bak`

**Interfaces:**
- Consumes: nothing new.
- Produces: nothing new; this is documentation/cleanup only.

- [ ] **Step 1: Write the failing test**

In `src/test/groovy/se/alipsa/lca/shell/ShellCommandsSpec.groovy`, extend the existing `"help lists commands alphabetically and includes config options"` test (around line 1703) by adding one more assertion to the `then:` block:

```groovy
    then:
    output.contains("=== Help ===")
    output.contains("Config options (/config):")
    output.contains("- intent: enabled|disabled|default")
    output.contains("- web-search: htmlunit|jsoup|disabled|default")
    output.contains("Multi-line input:")
    output.contains("/paste ... /end")
    output.contains("^^^ ... ^^^")
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=ShellCommandsSpec#"help lists commands alphabetically and includes config options"`
Expected: FAIL — `output.contains("Multi-line input:")` is false, since `help()` doesn't emit that section yet.

- [ ] **Step 3: Write minimal implementation**

In `src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy`, replace the final two lines of `help()` (currently lines 264–265):

```groovy
    formatSection("Help", "Commands:\n${commandLines}\n\nConfig options (/config):\n${configLines}")
  }
```

with:

```groovy
    String multilineLines = [
      "- Paste multi-line text directly; it is sent as a single message.",
      "- Or wrap it explicitly with /paste ... /end, or ^^^ ... ^^^ " +
        "(pick whichever marker does not itself appear in the pasted content)."
    ].join("\n")
    formatSection("Help",
      "Commands:\n${commandLines}\n\nConfig options (/config):\n${configLines}\n\nMulti-line input:\n${multilineLines}")
  }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=ShellCommandsSpec#"help lists commands alphabetically and includes config options"`
Expected: PASS

- [ ] **Step 5: Delete the stray backup file**

```bash
git rm src/test/groovy/se/alipsa/lca/shell/ShellCommandsSpec.groovy.bak
```

- [ ] **Step 6: Run the full test suite**

Run: `./mvnw test`
Expected: PASS (all tests, full project — final regression check for the whole feature)

- [ ] **Step 7: Commit**

```bash
git add src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy src/test/groovy/se/alipsa/lca/shell/ShellCommandsSpec.groovy
git commit -m "Document multi-line paste markers in /help; remove stray .bak file"
```

---

## Self-Review

**1. Spec coverage:**
- Part 1, point 1 (`COMMAND_PATTERN` DOTALL) → Task 1.
- Part 1, points 2–3 (wire `CommandInputNormaliser`, direct dispatch method) → Tasks 2 and 5.
- Part 2 (`FencedPasteParser`, `/paste`/`/end` and `^^^`/`^^^`, dispatch) → Tasks 3, 4, 5.
- Error handling & edge cases (Ctrl+C/Ctrl+D fall out naturally, empty block, marker collision, blank-line preservation) → covered by `handleInput`'s structure (Task 5) and `FencedPasteMarkers`/`FencedPasteParser` tests (Tasks 3–4); marker-collision caveat documented in `/help` (Task 6). No dedicated Ctrl+C/Ctrl+D test is added because the spec's own reasoning is that these propagate through *unmodified* existing exception handling in `start()` — there is nothing new to unit-test there.
- Testing section's call for `CommandExecutor` spec, extended `CommandInputNormaliser`/`ShellCommands.paste` integration coverage, and extracted+tested `JLineRepl` decision logic → Tasks 1, 2, 5.
- Minor cleanup (stray `.bak`) → Task 6.
- Out of scope items (no timing heuristics, no `ShellBatchCommandExecutor` changes) → correctly untouched by every task above.

**2. Placeholder scan:** No TBD/TODO markers; every step has complete, runnable code.

**3. Type consistency:** `executePasteContent(String, boolean, String, PersonaMode)` (Task 2) is called identically from `JLineRepl.dispatchPaste` (Task 5) and exercised identically in both `CommandExecutorSpec` and `JLineReplSpec`. `FencedPasteMarkers.extractContent`/`openerOf`/`isClosed`/`CLOSERS` (Task 3) are used with matching names in both `FencedPasteParser` (Task 4) and `JLineRepl` (Task 5). `JLineRepl`'s constructor parameter order (`intentRouter, commandExecutor, commandInputNormaliser, terminal, prompt, historyFile, secondOpinionThreshold`) is identical between the modified class and `JLineReplSpec`'s construction call.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-07-14-multiline-repl-input.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
