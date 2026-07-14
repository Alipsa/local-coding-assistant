# JLineRepl: support multi-line input (paste and explicit composition)

## Background

Pasting a multi-line chunk of text at the `lca>` prompt currently breaks: each
embedded linefeed is treated as if Enter had been pressed, so every line gets
routed and executed separately instead of being treated as one input. A
transcript of the failure showed the prompt (`lca>`) reappearing after every
line, with fragments being routed individually.

Investigation surfaced two independent, pre-existing gaps rather than one bug:

1. **`CommandExecutor.COMMAND_PATTERN`** (`se/alipsa/lca/repl/CommandExecutor.groovy:24`)
   is `~/^\/(\w+)\s*(.*)/` with no `DOTALL` flag, so a slash command's
   argument text is truncated at the first embedded newline even if it
   arrives intact as one string.
2. **`CommandInputNormaliser`** (`se/alipsa/lca/shell/CommandInputNormaliser.groovy`)
   already detects "raw text, doesn't start with `/`, contains a newline,
   auto-paste enabled" and can produce paste-command words
   (`normaliseWords`) — but it is only wired into `ShellBatchCommandExecutor`
   (batch/script mode), never into `JLineRepl`, the interactive loop.

Separately, JLine (3.30.9, confirmed the resolved version) supports
bracketed paste by default: when a terminal wraps a paste in the bracketed
paste escape sequences, `LineReaderImpl.beginPaste()` inserts the whole
blob — literal embedded newlines included — into the edit buffer as one
atomic edit, without triggering "accept line" per newline. Whether that
happens at all depends on the terminal emulator actually sending those
escape sequences; it cannot be guaranteed for every terminal/multiplexer
the app might run under.

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
   equivalent `(?s)` inline flag) so multi-line argument text is no longer
   truncated at the first newline.
2. Wire `CommandInputNormaliser` into `JLineRepl`. After
   `lineReader.readLine(prompt)` returns and before intent-routing, check
   `normaliser.isPasteCandidate(trimmed)`. If true (raw text, no leading
   `/`, contains a newline, auto-paste enabled), skip the LLM intent
   classifier entirely — this case is unambiguous — and dispatch directly.
3. Add a new method on `CommandExecutor` that takes already-split content
   (not a re-serialized command string) and forwards straight to
   `shellCommands.paste(content, "/end", send: true, session, persona)`.
   This avoids ever round-tripping multi-line content back through a
   string that would need re-parsing by `COMMAND_PATTERN`/`parseArgs`.

This makes a real bracketed-paste blob (what Terminal.app, iTerm2, Ghostty,
and most modern emulators send on Cmd+V) work correctly. It does nothing for
terminals/sessions where bracketed paste doesn't reach the JVM, which Part 2
covers.

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
  - Opener `/paste` closes only on a line that is exactly `/end`.
  - Opener `^^^` closes only on a line that is exactly `^^^`.
  - `^^^` was chosen (over triple-backtick, straight/curly triple-quote,
    or acute accent) specifically to avoid colliding with fences or
    multi-line string syntax commonly found in pasted Markdown, Python, or
    Groovy source.
- If the first physical line of the buffer (in `ACCEPT_LINE` context) is
  one of the two openers and no matching closer line has appeared yet,
  throw `EOFError` (continuation), overriding JLine's default secondary
  prompt pattern with something explicit like `paste... `.
- A closer must match its own opener: a stray `^^^` line inside a `/paste`
  block is just content, not a close, and vice versa.
- Otherwise (no recognized opener, or the block is already closed),
  delegate to `super.parse(...)`, preserving all existing behavior
  including quote-based continuation.

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
  one, covering the `DOTALL` fix (multi-line slash-command args no longer
  truncate) and the new direct paste-content dispatch method.
- **`CommandInputNormaliser` / `ShellCommands.paste`**: existing specs
  extended to cover the new direct-dispatch path invoked from `JLineRepl`.
- **`JLineRepl`**: has no tests today and builds a real system `Terminal`
  in its constructor. Rather than fighting that, extract the "decide what
  to do with a completed line of input" branching (paste-candidate? fenced
  content already closed? otherwise route via intent?) into a small,
  separately testable method or collaborator, so the decision logic gets
  Spock coverage independent of the raw terminal I/O loop.

## Out of scope

- No change to `/help` text beyond the one-line marker-collision caveat.
- No timing/heuristic-based paste detection (e.g. treating rapid
  consecutive lines as a burst) — deliberately not pursued; it would be a
  fragile heuristic on top of the two reliable mechanisms above.
- No change to `ShellBatchCommandExecutor`'s existing use of
  `CommandInputNormaliser` for batch/script mode.
