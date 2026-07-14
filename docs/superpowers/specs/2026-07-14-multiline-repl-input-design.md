# JLineRepl: support multi-line input (paste and explicit composition)

## Background

Pasting a multi-line chunk of text at the `lca>` prompt currently breaks: each
embedded linefeed is treated as if Enter had been pressed, so every line gets
routed and executed separately instead of being treated as one input. A
transcript of the failure showed the prompt (`lca>`) reappearing after every
line, with fragments being routed individually.

Investigation surfaced two independent, pre-existing gaps rather than one bug:

1. **`CommandExecutor.COMMAND_PATTERN`** (`se/alipsa/lca/repl/CommandExecutor.groovy:24`)
   is `~/^\/(\w+)\s*(.*)/` with no `DOTALL` flag. `execute()` matches it via
   `matcher.matches()`, which requires the *entire* trimmed string to match.
   Since `.` doesn't cross a line terminator without `DOTALL`, a multi-line
   argument makes the whole match fail outright — the failure mode is
   `"Invalid command format. Expected: /command [args]"`, **not** a
   silently truncated argument. (An earlier draft of this spec described
   this as truncation; it isn't — verified by re-reading `execute()`, which
   calls `matcher.matches()` and returns the "Invalid command format" string
   on failure before any group is ever extracted.) The fix is unchanged
   (add `DOTALL`/`(?s)`), but a regression test for this should assert the
   absence of the "Invalid command format" error, not assert
   non-truncation.
2. **`CommandInputNormaliser`** (`se/alipsa/lca/shell/CommandInputNormaliser.groovy`)
   has methods (`normaliseWords`, `isPasteCandidate`) that already detect
   "raw text, doesn't start with `/`, contains a newline, auto-paste
   enabled" and can produce paste-command words. **These two methods are
   currently dead code in production.** `ShellBatchCommandExecutor` (the
   only production caller of `CommandInputNormaliser`) only ever calls
   `normalise()` — verified by reading `ShellBatchCommandExecutor.execute()`,
   which calls `normaliser.normalise(trimmedCommand)` and nothing else.
   `normaliseWords`/`isPasteCandidate` are exercised only by
   `CommandInputNormaliserSpec`. This means wiring them into `JLineRepl`
   is **not** "extending an existing, exercised code path into a new
   caller" — it's activating previously-inert logic in production for the
   first time, and should get correspondingly more defensive integration
   testing (see Testing section), not just "extend the existing spec."

Separately, JLine — the actually-resolved version is **3.26.3** (via
`spring-shell-core`; there is no direct `org.jline` dependency in
`pom.xml`, verified via `mvn dependency:tree`) — supports bracketed paste
by default: when a terminal wraps a paste in the bracketed paste escape
sequences, `LineReaderImpl.beginPaste()` inserts the whole blob — literal
embedded newlines included — into the edit buffer as one atomic edit,
without triggering "accept line" per newline. Whether that happens at all
depends on the terminal emulator actually sending those escape sequences;
it cannot be guaranteed for every terminal/multiplexer the app might run
under. (Verified directly against the 3.26.3 sources, not assumed from a
newer version: `BRACKETED_PASTE` defaults to `true`, `beginPaste()` and
`EOFError`/`ParseContext.ACCEPT_LINE`/secondary-prompt continuation all
exist with the same structure as in later releases.)

## Goals

- When a terminal *does* deliver a paste atomically (bracketed paste
  working), the app must handle the resulting multi-line string correctly
  end-to-end, instead of truncating or misrouting it.
- Provide a terminal-agnostic way to enter multi-line input that works
  regardless of bracketed-paste support, using a marker convention the user
  explicitly opens and closes.
- No change to the non-multi-line (single-line) experience.

## Part 1 — Fix the plumbing bugs

1. Add `Pattern.DOTALL` to `COMMAND_PATTERN` in `CommandExecutor` (or the
   equivalent `(?s)` inline flag) so a slash command whose argument text
   spans multiple lines matches at all, instead of failing
   `matcher.matches()` outright and returning `"Invalid command format.
   Expected: /command [args]"`.
2. Wire `CommandInputNormaliser` into `JLineRepl`, activating the
   currently-dead `isPasteCandidate`/`normaliseWords` methods for the first
   time in production. After `lineReader.readLine(prompt)` returns and
   before intent-routing, check `normaliser.isPasteCandidate(trimmed)`. If
   true (raw text, no leading `/`, contains a newline, auto-paste enabled),
   skip the LLM intent classifier entirely — this case is unambiguous, see
   "Current routing baseline" below — and dispatch directly.
3. Add a new method on `CommandExecutor` that takes already-split content
   (not a re-serialized command string) and forwards straight to
   `shellCommands.paste(content, "/end", send: true, session, persona)`.
   This avoids ever round-tripping multi-line content back through a
   string that would need re-parsing by `COMMAND_PATTERN`/`parseArgs`.

This makes a real bracketed-paste blob (what Terminal.app, iTerm2, Ghostty,
and most modern emulators send on Cmd+V) work correctly. It does nothing for
terminals/sessions where bracketed paste doesn't reach the JVM, which Part 2
covers.

### Current routing baseline (why skipping the LLM classifier is safe here)

Today, `JLineRepl.start()` sends *every* non-empty, non-built-in line —
single-line or otherwise, slash-prefixed or not — to
`intentRouter.routeDetails(input)` via `processInput()`; there is no
existing branch for "this looks like pasted content" at all
(`CommandInputNormaliser` isn't referenced from `JLineRepl` today). The new
paste-candidate check in step 2 above is inserted *before* that call, as an
additional branch: raw text with no leading `/` and an embedded newline,
with auto-paste enabled, is specific enough (verified against
`CommandInputNormaliser.isPasteCandidate`'s existing conditions) that it
can't be a slash command or a single-line natural-language query, so
routing it straight to `/paste --content ... --send true` instead of
through the classifier is a safe narrowing, not a change to how anything
else gets routed.

## Part 2 — Explicit, terminal-agnostic multi-line mode

JLine's `Parser.parse()` can throw an `EOFError` to signal "input is
incomplete" (already used here for unclosed quotes via
`parser.setEofOnUnclosedQuote(true)`). When that happens during
`ParseContext.ACCEPT_LINE`, JLine's own `readLine()` keeps reading more
physical lines — showing a secondary prompt — until parsing succeeds, then
returns the *entire* accumulated block as a single string from that one
`readLine()` call. This works no matter how the lines arrive (atomic paste
or fragmented per-line "Enters"), because each Enter just extends the
buffer instead of submitting while the block is open.

### `FencedPasteParser`

A new class wrapping/extending `DefaultParser`, overriding `parse()`:

- Recognizes two independent marker pairs, matched by trimmed line content:
  - Opener `/paste` — the trimmed first line must be **exactly** `/paste`,
    nothing else on that line — closes only on a line that is exactly
    `/end`. A line like `/paste --content foo` or `/paste --end-marker X`
    is *not* an opener (it doesn't equal `/paste`); it falls through to
    `super.parse(...)` and is routed as a normal single-line slash command,
    same as today. This distinction (`equals`, not `startsWith`) is
    load-bearing and must be implemented as such — a `startsWith("/paste")`
    check would incorrectly treat `/paste --content foo` as a fence opener
    waiting for `/end`.
  - Opener `^^^` closes only on a line that is exactly `^^^`.
  - `^^^` was chosen (over triple-backtick, straight/curly triple-quote,
    or acute accent) specifically to avoid colliding with fences or
    multi-line string syntax commonly found in pasted Markdown, Python, or
    Groovy source.
  - Shipping both openers is a deliberate choice, not an oversight: `/paste`
    matches the naming already documented in `/help` and any existing
    muscle memory around it; `^^^` exists specifically for the collision
    case `/paste`/`/end` doesn't solve (pasted content that itself contains
    a literal `/end` line). Both get full test coverage (see Testing).
- If the first physical line of the buffer (in `ACCEPT_LINE` context) is
  one of the two openers and no matching closer line has appeared yet,
  throw `EOFError` (continuation), overriding JLine's default secondary
  prompt pattern with something explicit like `paste... `.
- A closer must match its own opener: a stray `^^^` line inside a `/paste`
  block is just content, not a close, and vice versa.
- Otherwise (no recognized opener, or the block is already closed),
  delegate to `super.parse(...)`, preserving all existing behavior
  including quote-based continuation.
- **Decision: the fenced `/paste` opener always closes on the literal
  `/end` line; it does not honor a custom end marker.** Today,
  `ShellCommands.paste`'s `endMarker` `@ShellOption` (default `/end`) is
  only consulted by `readFromStdIn`, which this fenced mode replaces *for
  the interactive `JLineRepl` path only*. `readFromStdIn`/`endMarker`
  remains reachable and unchanged for non-interactive use (e.g.
  `ShellBatchCommandExecutor`, which has no `LineReader`/parser to run a
  fence through and must keep reading raw `System.in` lines). Supporting a
  custom end marker in the interactive fence would require
  `FencedPasteParser` to parse flags off the opener line itself before
  deciding what closer to wait for — solvable, but added complexity for a
  narrow, rarely-needed case; deliberately out of scope for this pass.

### Dispatch

Once `JLineRepl` receives a closed fenced block from `readLine()`, it strips
the opener/closer marker lines and treats the inner content as raw paste
content **unconditionally** — sent directly to
`shellCommands.paste(content, ..., send: true, session: "default",
persona: CODER)`, the same terminal destination as Part 1's auto-detected
path, bypassing both intent-routing and slash-command detection even if the
content happens to start with `/` or otherwise look command-like. Opening a
fence is an explicit, unambiguous signal from the user; no heuristics are
needed or applied inside it.

This replaces the current `/paste`-with-no-`--content` implementation,
which reads raw `System.in` via a separate `BufferedReader`
(`ShellCommands.readFromStdIn`) — a code path entirely outside JLine's
terminal handling, which risks raw/canonical tty-mode conflicts and gets no
line editing. The new version stays inside the same `LineReader`, so
composing/pasting a block gets real editing (arrows, backspace) for free.

## Error handling & edge cases

- **Ctrl+C mid-fence**: the entire multi-line continuation happens inside
  one `lineReader.readLine()` call, so `UserInterruptException` propagates
  out of that single call to the existing top-level catch in
  `JLineRepl.start()` (prints `^C`, returns to a fresh prompt). No
  fence-specific handling needed.
- **Ctrl+D / real EOF mid-fence**: propagates as `EndOfFileException` to the
  existing handler, same as today's top-level Ctrl+D. Partial paste is
  discarded; not a regression.
- **Empty block** (opener immediately followed by its closer): skip
  sending, consistent with today's empty-input handling
  (`JLineRepl.start()`'s existing blank-line check).
- **Marker collision**: if pasted content itself contains a line that is
  *exactly* `/end` or `^^^`, the block closes prematurely. This is an
  inherent limitation of any marker-based scheme (bash heredocs have the
  same limitation) — pick whichever marker doesn't appear in the content.
  Worth a one-line mention in `/help`.
- Leading/trailing blank lines *within* the fence (excluding the marker
  lines themselves) are preserved verbatim — only the "is the whole block
  blank" check is trimmed, not the content that gets sent.

## Testing

- **`FencedPasteParser`** (new): fully unit-testable with no real terminal.
  Spock spec covers normal single-line passthrough, existing
  quote-continuation still delegating to `super.parse()` correctly,
  `/paste` + `/end` extraction, `^^^` + `^^^` extraction, a mismatched
  closer *not* closing the block, and an unterminated fence continuing to
  signal incomplete input across repeated calls.
- **`CommandExecutor`**: no Spock spec exists yet for this class (only
  `ShellBatchCommandExecutorSpec` exists, covering a different class) — add
  one, covering the `DOTALL` fix (assert a multi-line-argument command no
  longer returns `"Invalid command format..."`) and the new direct
  paste-content dispatch method.
- **`CommandInputNormaliser` / `ShellCommands.paste`**: `isPasteCandidate`
  and `normaliseWords` already have spec coverage in isolation, but since
  wiring them into `JLineRepl` makes them live in production for the first
  time (see Part 1, point 2 above), add integration-level coverage too —
  not just unit tests of the normaliser in isolation, but a test exercising
  the actual `JLineRepl` decision path (see below) with realistic
  paste-candidate and non-candidate inputs, confirming each lands on the
  correct destination (direct paste dispatch vs. intent-router).
- **`JLineRepl`**: has no tests today. Its `Terminal` is Spring-injected
  (built once by `TerminalConfiguration.terminal()`), so that part is easy
  to supply a test double for; what's hard to unit-test is the `LineReader`
  built from it in the constructor, since `LineReaderBuilder.build()`
  against a real/dumb terminal is what would need to run to exercise actual
  paste/fence behavior end-to-end. Rather than fighting that, extract the
  "decide what to do with a completed line of input" branching
  (paste-candidate? fenced content already closed? otherwise route via
  intent?) into a small, separately testable method or collaborator, so the
  decision logic gets Spock coverage independent of the `LineReader`/parser
  construction.

## Minor cleanup (unrelated, noted in passing)

`src/test/groovy/se/alipsa/lca/shell/ShellCommandsSpec.groovy.bak` is a
stray backup file sitting in the test tree, unrelated to this feature.
Worth deleting whenever someone touches this area next; not a blocker for
this work.

## Out of scope

- No change to `/help` text beyond the one-line marker-collision caveat.
- No timing/heuristic-based paste detection (e.g. treating rapid
  consecutive lines as a burst) — deliberately not pursued; it would be a
  fragile heuristic on top of the two reliable mechanisms above.
- No change to `ShellBatchCommandExecutor`'s existing use of
  `CommandInputNormaliser` for batch/script mode.
