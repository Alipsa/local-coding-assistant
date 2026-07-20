# Plan: RAG-based long-term memory store (docs/roadmap.md item 3, step 1)

## Context

`docs/roadmap.md` item 3 asks for a RAG capability so the assistant can "learn and
remember," noting RAG support arrived in Embabel 0.5.0 (the version this project
already pins). Step 1 of that item is a memory store with four concrete behaviours:
recall-by-similarity, store-on-"surprising"-learning, created/lastAccessed
timestamps, and forgetting once a memory is both old *and* idle. Today there is no
persistence layer of any kind in LCA (no DB, no vector store) and no code touches
Embabel's RAG APIs — this is a greenfield addition. This plan scopes only step 1
(a working memory store wired into the chat flow); roadmap items 4 (runtime model
switching) and 5 (prompt caching) are unrelated and out of scope.

Decisions confirmed with the user:
- **Memory is enabled by default** (`lca.memory.enabled=true`), not opt-in.
- **"Surprising learning" detection is heuristically gated**: a cheap local
  keyword/regex scan runs first; the extra LLM classification call only fires
  when the scan finds a signal worth checking (avoids paying a full LLM
  round-trip on every single turn).
- **An explicit `recallMemory()` `@LlmTool`** is added alongside automatic
  pre-turn recall injection, so the model can also ask on demand.

Two further additions, beyond the roadmap's literal wording, are included because
LCA is a coding assistant used across many repos and a memory store with neither
scoping nor an explicit write path would be actively misleading rather than
useful:
- **Project/repo scoping** (§3, §4) — every memory is tagged with the project it
  was learned in (derived from the existing `Workspace` base directory), and
  recall/remember are scoped to "this project's memories, plus any global ones."
  Without this, a fact learned in one repo (e.g. "this repo requires Node 18")
  would surface as noise while working in an unrelated one.
- **A symmetric `rememberFact()` `@LlmTool`, plus supersession on write** (§4,
  §5) — the heuristic-gated automatic path only catches hedges/corrections; a
  flat instruction like "always use 4-space indentation here" has no hedge word
  to trigger on, so users need an explicit way to make something stick. And
  since a new fact can contradict an old one, writes now check for a
  near-duplicate existing memory and supersede it rather than letting recall
  resurface both the stale and the corrected version.

New package: `se.alipsa.lca.memory` (sibling to `agent/`, `shell/`, `tools/`,
`validation/`, etc.).

---

## 1. Dependencies — add, then verify the real API before coding against it

`pom.xml`, after the existing `embabel-agent-starter-ollama` dependency (~line 103):

```xml
<dependency>
  <groupId>com.embabel.agent</groupId>
  <artifactId>embabel-agent-rag-core</artifactId>
</dependency>
<dependency>
  <groupId>com.embabel.agent</groupId>
  <artifactId>embabel-agent-rag-lucene</artifactId>
</dependency>
```

No `<version>` needed — inherited from the already-imported `embabel-agent-dependencies`
BOM (pom.xml lines 59-65), same pattern as `embabel-agent-starter`. Both artifact ids
are confirmed present in the cached 0.5.0 BOM. Do **not** add `embabel-agent-rag-tika`
(document parsing — memories are plain LLM-generated sentences, not ingested
documents) or `embabel-agent-rag-pipeline` (chunking for large documents — a memory
entry is already an atomic short fact).

**Blocking first task, before writing `EmbabelRagMemoryIndex` (§4):** resolve the new
dependencies (`./mvnw -q dependency:resolve` then fetch `-sources.jar`s) and inspect
the real 0.5.0 API, since neither artifact exists in the local Maven cache today and
their exact class/interface names are unverified. Confirm:
- the vector-store/index type that supports upserting a text + metadata item,
- the retriever/similarity-search entry point (topK, per-hit score),
- how embedding invocation is wired (via `embabel.models.default-embedding-model` /
  `embabel.models.embedding-services.*`, already scaffolded but commented out at
  `application.properties` lines 15, 21-22), and
- whether stored metadata is mutable after insert (this determines whether
  `lastAccessedAt` could live inside the index — see §4 for why the plan doesn't
  rely on that either way).

Not blocking, but worth confirming while in there: `PromptRunner`-family builder
methods (`withPromptContributor`, `withSystemPrompt`, `withTools`, ...) all appear
to return the same fluent type in every existing call site (e.g.
`CodingAssistantAgent.groovy:262`, `ChatAgent.groovy:80-84`), which is what makes
the reassignable-local pattern in §5 point 3 compile — no separate action needed,
just don't be surprised if a given method turns out to return a narrower type.

Do not hard-code guessed class names; `MemoryIndex` (§4) isolates this uncertainty
behind one interface so the rest of the app never depends on the real Embabel types
directly.

Uncomment in `application.properties` (lines 15, 21-22):
```properties
embabel.models.default-embedding-model=nomic-embed-text:latest
embabel.models.embedding-services.best=nomic-embed-text:latest
embabel.models.embedding-services.cheapest=nomic-embed-text:latest
```

**`models.sh`** — this is the script that actually provisions Ollama models (it
installs Ollama itself if missing, then calls `checkAndInstall <model>` for each
required base model — currently `qwen3.6:35b-a3b` and `gpt-oss:20b`, lines 86-88 —
followed by `createCustomModel` calls that build larger-context variants via a
generated Modelfile, lines 91-92). Add one line alongside the existing
`checkAndInstall` calls so the embedding model is pulled automatically as part of
setup, the same way the chat models are:
```sh
# Install base models
#checkAndInstall deepseek-coder:6.7b
checkAndInstall qwen3.6:35b-a3b
checkAndInstall gpt-oss:20b
checkAndInstall nomic-embed-text:latest
```
No `createCustomModel` call is needed for it — embedding models aren't invoked
with the `num_ctx` chat-context tuning the custom variants exist for, so a plain
pull is sufficient. `localInstall.sh` (build + install the `lca`/`lcaGui`
binaries) is unrelated to model provisioning and needs no changes.

---

## 2. Config — `MemorySettings.groovy`

New file `src/main/groovy/se/alipsa/lca/memory/MemorySettings.groovy`, following the
`@ConfigurationProperties` template from
`src/main/groovy/se/alipsa/lca/validation/ValidationSettings.groovy`:

```groovy
package se.alipsa.lca.memory

import groovy.transform.CompileStatic
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "lca.memory")
@CompileStatic
class MemorySettings {

  boolean enabled = true
  String embeddingModel = "nomic-embed-text:latest"
  String indexDirectory = "${System.getProperty('user.home')}/.lca/memory-index"
  int recallTopK = 5
  double recallMinScore = 0.6

  // A memory is forgotten only once BOTH hold: created longer ago than maxAgeDays
  // AND not accessed (recalled) for longer than maxIdleDays.
  int maxAgeDays = 180
  int maxIdleDays = 30
  int pruneMinIntervalMinutes = 60

  boolean surprisingLearningDetectionEnabled = true
  String surprisingLearningModel = ""

  // Caps so memory injection can't silently crowd out other prompt context (see §5).
  int recallMaxContextChars = 2000
  int maxMemoryContentChars = 500

  // Project scoping (see §4): how much to over-fetch from the index before
  // filtering to the current project + global memories, as a fallback if the
  // real RAG API can't pre-filter by project at query time (§1). Used by both
  // recall() and remember()'s supersession check - both filter by scope
  // after a fixed-size fetch, so both need it.
  int recallOverFetchFactor = 4

  // Supersession on write (see §4): how similar a new memory must be to an
  // existing one (in the same scope) to replace it rather than sit alongside it.
  // Deliberately stricter than recallMinScore - this must be near-identical topic,
  // not merely "related". supersedeCandidateLimit is the final, post-scope-filter
  // count considered for replacement - the raw index fetch is
  // supersedeCandidateLimit * recallOverFetchFactor, same over-fetch treatment
  // as recall().
  double supersedeSimilarityThreshold = 0.85
  int supersedeCandidateLimit = 3
}
```

`application.properties` — new section after the "Swing GUI" block (~line 88):

```properties
# Long-term memory (RAG-based recall/remember) - roadmap item 3, step 1
lca.memory.enabled=true
lca.memory.embedding-model=${embabel.models.embedding-services.cheapest:nomic-embed-text:latest}
lca.memory.index-directory=${user.home}/.lca/memory-index
lca.memory.recall-top-k=5
lca.memory.recall-min-score=0.6
# Forgotten only when BOTH thresholds are exceeded (age AND idle).
lca.memory.max-age-days=180
lca.memory.max-idle-days=30
lca.memory.prune-min-interval-minutes=60
lca.memory.surprising-learning-detection-enabled=true
lca.memory.surprising-learning-model=
# Caps so memory injection can't silently crowd out other prompt context.
lca.memory.recall-max-context-chars=2000
lca.memory.max-memory-content-chars=500
# Project scoping and same-topic supersession on write.
lca.memory.recall-over-fetch-factor=4
lca.memory.supersede-similarity-threshold=0.85
lca.memory.supersede-candidate-limit=3
```

---

## 3. Domain model

`src/main/groovy/se/alipsa/lca/memory/MemoryEntry.groovy`:
```groovy
@Canonical
@CompileStatic
class MemoryEntry {
  String id
  String content
  Instant createdAt
  Instant lastAccessedAt
  String sourceSessionId
  String projectId  // null = global (applies across every project); see §4
}
```

`src/main/groovy/se/alipsa/lca/memory/RecalledMemory.groovy`:
```groovy
@Canonical
@CompileStatic
class RecalledMemory {
  MemoryEntry entry
  double score
}
```

Kept minimal beyond `projectId` — no tags/category for step 1.

---

## 4. Store/service design

The RAG index is used only for what it's certain to do — embed text and answer
top-K similarity search. Timestamp/eviction bookkeeping (genuinely app-specific,
and independent of the unverified RAG API's metadata capabilities) is owned by a
local JSON sidecar.

- **`src/main/groovy/se/alipsa/lca/memory/ProjectScopeResolver.groovy`** — a small
  `@Component` depending on the existing `Workspace` bean
  (`src/main/groovy/se/alipsa/lca/tools/Workspace.groovy`), which already holds
  "the single, mutable session base directory shared by all path-aware tools"
  (its own class doc, line 12-15) — i.e. `Workspace.baseDir` is process-wide and
  read live on every call, exactly what's needed here. `ProjectScopeResolver`
  adds one thing `Workspace` doesn't do: resolving *which project* a directory
  belongs to, not just which directory is current:
  ```groovy
  String currentProjectId() {
    resolve(workspace.baseDir)
  }

  private String resolve(Path dir) {
    // walk dir and its parents looking for a .git entry (directory or file - a
    // file means a worktree, see `git worktree`) so that cd-ing into a
    // subdirectory of the same repo mid-session still resolves to the same
    // project; if no .git is found anywhere up to the filesystem root, fall
    // back to the canonical absolute path of `dir` itself (still gives a
    // stable, distinct identity to non-git working directories)
    // returns the canonical absolute path string of whichever directory was found
  }
  ```
  Called fresh (not cached) at each `recall()`/`remember()` call site, matching
  `Workspace.baseDir` being mutable at runtime (e.g. via the GUI's folder
  chooser) — a stale cached `projectId` would silently misfile memories after a
  directory change.

- **`src/main/groovy/se/alipsa/lca/memory/MemoryMetadataStore.groovy`** — persists
  `Map<String, MemoryEntry>` as JSON at `${indexDirectory}/metadata.json` (same
  directory convention as the vector index; mirrors the `~/.lca/history` precedent
  in `JLineRepl.groovy` lines 55, 82-88). Loads into a `ConcurrentHashMap` at
  construction, writes through atomically (temp file + `Files.move`) on every
  mutation. Needs an `ObjectMapper` with `JavaTimeModule` registered for `Instant`.
  Methods: `get(id)`, `put(entry)`, `putAll(Collection<MemoryEntry> entries)`,
  `remove(id)`, `all()`. `putAll` exists so `recall()` (below) can persist every
  `lastAccessedAt` bump from one call in a single atomic write instead of one
  write per hit.

  **Concurrency note:** the `ConcurrentHashMap` plus atomic temp-file swap
  protects each individual write, but two overlapping writers (e.g. the GUI and
  the REPL both pointed at the same `~/.lca/memory-index/` at once) can still
  race last-write-wins across the whole map. Accepted as fine for now — LCA is
  single-user/local — but called out explicitly rather than left implicit, since
  "atomic write" could otherwise read as "safe under concurrency," which it
  isn't beyond per-write atomicity.

- **`src/main/groovy/se/alipsa/lca/memory/MemoryIndex.groovy`** (interface) — the
  seam isolating the unverified Embabel RAG API. `search` takes an over-fetch
  count rather than the caller's real `topK`, because project-scope filtering
  (see `MemoryStore.recall()` below) happens after the index returns candidates:
  ```groovy
  interface MemoryIndex {
    void upsert(String id, String content)
    List<MemoryIndexHit> search(String queryText, int fetchCount)
    void delete(String id)
  }
  // MemoryIndexHit: @Canonical { String id; double score }
  ```
  Add to §1's API-verification checklist: whether the real Lucene-backed store
  can filter by a metadata field *at query time* (e.g. a `BooleanQuery`/filter
  combined with the similarity query on a `projectId` field). If yes, `search`
  should take a `projectId`/global filter directly and push scoping into the
  index — more efficient and exact than the app-level over-fetch-then-filter
  fallback below, and avoids that fallback's failure mode where a fixed
  over-fetch multiplier still isn't enough (see `MemoryStore.recall()`).

- **`src/main/groovy/se/alipsa/lca/memory/EmbabelRagMemoryIndex.groovy`** — the real
  `@Component` implementation, written only after §1's API verification, configured
  with `MemorySettings.indexDirectory`/`embeddingModel`. If the real API proves
  awkward for "one short fact + externally-tracked timestamps," the documented
  fallback is a `SimpleCosineMemoryIndex` (brute-force cosine similarity over
  embeddings in the same JSON sidecar) — only build this if §1 forces it.

- **`src/main/groovy/se/alipsa/lca/memory/MemoryStore.groovy`** — the service the
  rest of the app depends on:
  ```groovy
  @Component
  @CompileStatic
  class MemoryStore {
    private final MemoryIndex memoryIndex
    private final MemoryMetadataStore metadataStore
    private final MemorySettings settings
    private volatile Instant lastPruneAt = Instant.EPOCH

    MemoryStore(MemoryIndex memoryIndex, MemoryMetadataStore metadataStore, MemorySettings settings) { ... }

    // projectId: the scope this memory belongs to. Pass ProjectScopeResolver.currentProjectId()
    // from call sites for a normal project-scoped memory, or null explicitly for a global one
    // (only the rememberFact() tool, §5, exposes that choice to the user).
    MemoryEntry remember(String content, String sessionId, String projectId) {
      // guard: !settings.enabled or blank content -> null
      // truncate content to settings.maxMemoryContentChars (classifier-extracted facts
      // have no length bound on their own - see SurprisingLearningDetector in §5)
      //
      // supersession: same over-fetch-then-filter shape as recall() below, and for the identical
      // reason - the index has no scope awareness. fetchCount = settings.supersedeCandidateLimit *
      // settings.recallOverFetchFactor; memoryIndex.search(content, fetchCount); keep only hits
      // scoped the same way (candidate.projectId == projectId, i.e. compare within the same
      // project or within the global tier - never cross-scope) with score >= supersedeSimilarityThreshold,
      // capped to supersedeCandidateLimit; delete each such candidate from both memoryIndex and
      // metadataStore before inserting the new one (same hard-delete choice already made for
      // forget() - no separate archival mechanism). Skipping the over-fetch here (i.e. searching
      // for only supersedeCandidateLimit raw hits) would be a worse failure mode than recall()'s:
      // in a store with many other-project/global memories, the true same-scope near-duplicate
      // could rank outside the raw top-N and simply never be found, so a correction would silently
      // fail to supersede the stale entry - both versions would then persist indefinitely.
      //
      // creates MemoryEntry(id, content, now, now, sessionId, projectId); upserts into index + metadata;
      // maybeForget(); returns entry
    }

    // projectId: the caller's current scope (ProjectScopeResolver.currentProjectId()). A hit is
    // included if entry.projectId == projectId (same project) OR entry.projectId == null (global).
    List<RecalledMemory> recall(String query, int topK, String projectId) {
      // guard: !settings.enabled or blank query -> []
      // fetchCount = topK * settings.recallOverFetchFactor (over-fetch because scope filtering
      // happens after the index returns candidates - see MemoryIndex note above for the caveat
      // this implies, and the preferred alternative if the real API supports query-time filtering)
      // hits = memoryIndex.search(query, fetchCount); drop hits below recallMinScore
      // for each surviving hit: look up MemoryEntry; skip if entry.projectId not in {projectId, null};
      // bump lastAccessedAt=now (in-memory only, not yet persisted); keep the first topK survivors
      // after the loop: metadataStore.putAll(updatedEntries) - ONE atomic write for the whole call,
      // not one per hit; recall() runs pre-turn on every user turn, so N per-hit writes (up to
      // recallTopK, default 5) would mean N full-map serializations per turn just to bump timestamps
      // return sorted by score desc
      //
      // Known imprecision: because scope-filtering happens after a fixed-size over-fetch, a project
      // with very few memories relative to a much larger global/other-project pool could, in the
      // worst case, get fewer than topK results even when enough in-scope memories exist beyond the
      // over-fetch window. Increasing recallOverFetchFactor trades this off against doing more
      // filtering work per call. Goes away entirely once §1 confirms query-time scope filtering.
    }

    int forget() {
      // ageCutoff = now - maxAgeDays; idleCutoff = now - maxIdleDays
      // remove entries where createdAt < ageCutoff AND lastAccessedAt < idleCutoff (from both index and metadata)
      // scope-independent - a memory is forgotten by age/idle regardless of project
      // returns count removed
    }

    private void maybeForget() {
      // no-op unless (now - lastPruneAt) >= pruneMinIntervalMinutes; then lastPruneAt = now; forget()
    }
  }
  ```
  Eviction is lazy pruning triggered from `remember()` (a write path), rate-limited
  by `pruneMinIntervalMinutes` so it isn't a full metadata scan on every write —
  no scheduler/executor infra exists in the app today, so this avoids adding one.
  `forget()` stays public for a future manual/maintenance trigger.

  **Supersession's known limitation:** it only catches a new memory that is
  textually/semantically *similar* to the one it replaces (e.g. "the API key env
  var is FOO_KEY" → "actually it's BAR_KEY" — high similarity, correctly
  superseded). A correction phrased very differently from the original (e.g.
  "this project uses Node 18" → "we're actually on Deno now, not Node") may fall
  under `supersedeSimilarityThreshold` and simply sit alongside the stale memory
  instead of replacing it. Catching that reliably would need a second LLM
  classification call per write (compare new content against retrieved
  candidates) — accepted as a step-1 gap in favour of keeping writes to one
  extra `memoryIndex.search` call rather than adding another LLM round-trip.

---

## 5. Integration into the conversation flow

**Recall (automatic, pre-turn)** — in
`src/main/groovy/se/alipsa/lca/agent/ChatAgent.groovy`, inside
`respondWithThinking(...)` (the single funnel both thinking and non-thinking
branches share, lines 64-108):

1. Constructor gains `MemoryStore memoryStore`, `MemorySettings memorySettings`,
   and `ProjectScopeResolver projectScopeResolver` (mirrors how
   `codingAssistantAgent` is already injected, lines 45-51).
2. After `PersonaTemplate template = personaTemplate(request.persona)` (line 72),
   add:
   ```groovy
   List<RecalledMemory> recalled = memorySettings.enabled
     ? memoryStore.recall(userMessage.content, memorySettings.recallTopK, projectScopeResolver.currentProjectId())
     : []
   MemoryPromptContributor memoryContributor = recalled ? new MemoryPromptContributor(recalled) : null
   ```
   Recall is keyed off `userMessage.content` (the latest user turn), matching the
   roadmap's "similar things discussed" wording, and scoped to the project
   currently active in `Workspace` (§4) plus any global memories.
3. Both `promptRunner`-building chains (lines 80-84 and 98-102) currently end in a
   single fluent expression, not a reassignable local. Restructure each into a
   `def promptRunner = ...` local (this exact pattern already exists in this
   codebase at `CodingAssistantAgent.groovy:262`,
   `def promptRunner = ai.withLlm(options).withPromptContributor(reviewer)`, and
   compiles fine under `@CompileStatic`), then conditionally reassign:
   ```groovy
   def promptRunner = ai
     .withLlm(options)
     .withPromptContributor(template.persona)
     .withSystemPrompt(systemPrompt)
     .withTools(codingAssistantAgent.buildLlmTools(conversation.id))
   if (memoryContributor != null) {
     promptRunner = promptRunner.withPromptContributor(memoryContributor)
   }
   ```
   This sidesteps needing to know whether `withPromptContributor` accepts/no-ops
   on a null argument — the conditional reassignment works either way. Apply this
   to both the `withThinking` and non-thinking branches (each keeps its own
   `promptRunner` local, as today).

New file `src/main/groovy/se/alipsa/lca/memory/RecalledMemoryFormatter.groovy` —
a small stateless utility shared by both places recalled memories reach the
model (the automatic prompt contributor below, and the explicit `recallMemory()`
tool), so the `recallMaxContextChars` cap applies exactly once and can't
accidentally diverge between the two paths:
```groovy
static String render(List<RecalledMemory> recalled, int maxChars) {
  // recalled is assumed already sorted by score desc (recall()'s contract);
  // build "[createdAt, similarity score] content" lines, appending in that
  // order and stopping (not trimming mid-line) once the next line would push
  // the joined result over maxChars - i.e. keep highest-scored first, drop the
  // rest, same behaviour regardless of caller
  // returns "" if recalled is empty (callers decide their own empty-case message)
}
```

New file `src/main/groovy/se/alipsa/lca/memory/MemoryPromptContributor.groovy` —
implements whichever prompt-contributor interface is appropriate (confirm the exact
interface during §1's inspection; `WebSearchTool.WebSearchResults implements
InternetResources` at `src/main/groovy/se/alipsa/lca/tools/WebSearchTool.groovy:97`
is the closest existing precedent for a small "inject this content into the
prompt" wrapper). Wraps `RecalledMemoryFormatter.render(recalled, settings.recallMaxContextChars)`
with a header, e.g.:
```
Relevant memories from earlier conversations:
- [2026-03-01, similarity 0.82] <content>
- [2026-05-14, similarity 0.71] <content>
```
This is a self-contained cap rather than a plug into `ContextBudgetManager`
(`src/main/groovy/se/alipsa/lca/tools/ContextBudgetManager.groovy`): that
class's `applyBudget(...)` is written specifically against
`CodeSearchTool.SearchHit` (file-context budgeting), not a generic text budget,
so reusing it here would mean refactoring it to be content-agnostic — out of
scope for step 1. Recorded as a gap: today `ContextBudgetManager`'s allocation
(system prompt / history / file context / web search,
`docs/architecture.md:637-645`) still has no awareness of memory content at
all; the two are independent budgets that both compete for the same underlying
model context window. Generalizing `ContextBudgetManager` to also cover memory
(and web search, which has the same gap today) is worth a follow-up, not part
of this plan.

**Explicit on-demand recall and remember tools** — add both to
`src/main/groovy/se/alipsa/lca/agent/CodingAssistantAgent.groovy` (constructor
gains `MemoryStore memoryStore`, `MemorySettings memorySettings`,
`ProjectScopeResolver projectScopeResolver`), following the exact three-part
shape already used for `search`/`searchFiles` (lines ~407-419):

```groovy
@Action(description = "Search long-term memory for facts relevant to a query.")
@LlmTool(name = "recallMemory", description = "Search long-term memory for facts or context relevant to the given query.")
String recallMemory(String query) {
  List<RecalledMemory> recalled = memoryStore.recall(query, memorySettings.recallTopK, projectScopeResolver.currentProjectId())
  String rendered = RecalledMemoryFormatter.render(recalled, memorySettings.recallMaxContextChars)
  rendered ?: "No relevant memories found."
}

@Action(description = "Explicitly store a fact in long-term memory, e.g. a user preference or standing instruction.")
@LlmTool(
  name = "rememberFact",
  description = "Store a fact in long-term memory so it's recalled in future conversations. " +
    "Use scope=\"project\" (default) for facts specific to the current repository (e.g. build " +
    "commands, conventions), or scope=\"global\" for facts that should apply everywhere " +
    "(e.g. the user's general style preferences)."
)
String rememberFact(String fact, String scope = "project") {
  String projectId = scope == "global" ? null : projectScopeResolver.currentProjectId()
  MemoryEntry entry = memoryStore.remember(fact, null, projectId)
  entry ? "Remembered." : "Could not store that (memory may be disabled)."
}
```
`sourceSessionId` is passed `null` here deliberately: unlike `buildLlmTools(sessionId)`
(lines 445-460), which threads a session id in only for `ConfirmingLlmTool`'s
confirmation bookkeeping, a plain `@LlmTool` method body such as this one has no
session id available to it (`CodingAssistantAgent` doesn't hold "the current
session" as instance state, and adding that plumbing just for provenance metadata
is out of scope for step 1). `MemoryEntry.sourceSessionId` therefore stays
populated for automatically-detected memories (`SurprisingLearningDetector` has
`conversation.id` in scope, see below) but is `null` for anything stored via the
explicit tool — acceptable since `sourceSessionId` is provenance-only and never
used by recall/forget/supersession filtering.

No `@RequiresConfirmation` on either tool — both are read/write against the
assistant's own private local memory store, never the user's repository, so they
carry none of the risk `writeFile`/`applyPatch` guard against; the memory is also
inherently reversible (`forget()`, or simply superseding it with a later
`rememberFact()` call).

Worth noting, not fixing: `rememberFact()` (and the automatic surprising-learning
path) is the first thing in this codebase whose output persists across sessions
and gets replayed into unrelated future conversations — every other tool's
output (including `WebSearchTool`'s results) only ever influences the current
turn. That means content originating from a web search, once summarized into a
"surprising learning" or explicitly `rememberFact()`'d, now has a path to
permanent, cross-session storage rather than staying transient. Accepted for
step 1 — LCA is single-user/local, so this doesn't cross any trust boundary that
doesn't already exist in the app — but it's a qualitatively different kind of
persistence from everything else here, so it's called out in `docs/memory.md`
(§7) rather than left unstated.

Because `ChatAgent` already calls `codingAssistantAgent.buildLlmTools(conversation.id)`
(lines 84, 102), both tools are automatically discovered and available in chat
with no further wiring — `MethodToolFactory` picks them up like every other
`@LlmTool`.

**Remember ("surprising learning" detection, heuristically gated)** — new file
`src/main/groovy/se/alipsa/lca/memory/SurprisingLearningDetector.groovy`, a
`@Component` with one public method, called from `ChatAgent.respondWithThinking`
right after `conversation.addMessage(reply)` (line 106):
```groovy
conversation.addMessage(reply)
surprisingLearningDetector.maybeRemember(userMessage.content, reply.content, conversation.id, ai)
new ChatResponse(reply, reasoning)
```

`maybeRemember(...)` logic:
1. Short-circuit if `!settings.enabled || !settings.surprisingLearningDetectionEnabled`
   — no heuristic scan, no LLM call.
2. **Heuristic pre-filter (cheap, no LLM call):** scan `reply.content` for hedging
   phrases (e.g. "I don't know", "I'm not sure", "I don't have information",
   "I don't recall") and scan `userMessage.content` for correction markers (e.g.
   "actually,", "that's wrong", "no, ", "correction:", "to be clear,"). This is a
   small fixed keyword/regex list local to the class — not a new config surface,
   since the roadmap doesn't call for tuning it and over-configuring a heuristic
   that's likely to be refined later adds no value now.
3. Only if the heuristic matches, invoke the classifier LLM call:
   ```groovy
   LlmOptions options = LlmOptions.withModel(resolveModel()) // surprisingLearningModel override, else fallback-model
   SurpriseVerdict verdict = ai.withLlm(options).createObject(buildClassificationPrompt(userInput, assistantReply), SurpriseVerdict)
   if (verdict?.surprising && verdict.fact?.trim()) {
     memoryStore.remember(verdict.fact.trim(), sessionId, projectScopeResolver.currentProjectId())
   }
   ```
   (`SurpriseVerdict`: `@Canonical { boolean surprising; String fact }`, matching
   the `createObject(prompt, Type)` pattern already used in
   `CodingAssistantAgent.groovy:301-304`. `SurprisingLearningDetector` gains a
   `ProjectScopeResolver` dependency alongside `MemoryStore`/`MemorySettings`;
   automatic memories are always project-scoped, never global — only the
   explicit `rememberFact()` tool exposes the global option, since deciding a
   fact applies everywhere is a judgment call for the user to make, not an
   automatic classifier.)

This keeps the added-latency cost proportional to how often a turn actually looks
like a correction or a gap in the model's knowledge, rather than paying a full
extra LLM round-trip on every single turn.

**Relationship to `ContextCompactor`/`SessionState` — confirmed, no conflict.**
`ContextCompactor.compact()` is invoked from `ShellCommands.maybeAutoCompact(session)`
(`src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy:469-479`), which runs
*after* `sessionState.appendHistory(...)` — i.e. strictly after
`ChatAgent.respondWithThinking` has already returned (confirmed at
`ShellCommands.groovy:418-419`, `631-632`, `713-714`). Memory recall/remember
operates on the `userMessage`/`reply` method parameters directly, never on
`conversation.messages` after the fact, so later compaction of the in-memory
`Conversation` (held by `SessionState`, reset on restart) can't affect what memory
already captured. Storage is fully disjoint: `ContextCompactor` mutates in-memory,
session-scoped state; `MemoryStore` persists to `~/.lca/memory-index/` on disk,
across restarts.

---

## 6. Testing (Spock 2.4, `src/test/groovy/se/alipsa/lca/memory/`)

1. **`MemorySettingsSpec.groovy`** — `ApplicationContextRunner.withUserConfiguration(MemorySettings)`
   + `@Unroll` + `where:` table (mirrors `RunnerConfigurationSpec.groovy`), verifying
   defaults and property-override binding.
2. **`MemoryStoreSpec.groovy`** — `Mock(MemoryIndex)` + `Mock(MemoryMetadataStore)` +
   a real `MemorySettings` (direct construction, matching `CodingAssistantAgentSpec.groovy`):
   - `remember()` upserts into index + metadata with `createdAt == lastAccessedAt`.
   - `remember()` with content longer than `maxMemoryContentChars` stores/upserts
     the truncated form, not the original — assert on what's actually passed to
     `memoryIndex.upsert(...)` and `metadataStore.put(...)`, not just the return
     value's length.
   - `recall()` filters below `recallMinScore`, sorts by score desc, bumps
     `lastAccessedAt` on every returned hit, and persists all of them via exactly
     one `metadataStore.putAll(...)` call per `recall()` invocation (not one
     `put` per hit).
   - `forget()` — `@Unroll`/`where:` matrix covering the AND-condition explicitly:
     old+idle → forgotten; old+recent → kept; new+idle → kept; new+recent → kept.
   - `maybeForget()` gating — two `remember()` calls inside `pruneMinIntervalMinutes`
     trigger the scan only once (may need an injectable clock/`Supplier<Instant>`
     constructor seam on `MemoryStore` to make this deterministic rather than
     relying on real sleeps).
   - **Scoping** (`@Unroll`/`where:` matrix): `recall(query, topK, "projectA")`
     returns a memory tagged `projectId: "projectA"` and a memory tagged
     `projectId: null` (global), but not one tagged `projectId: "projectB"`.
   - **Supersession**: verify `remember()` calls `memoryIndex.search` with a
     fetch count of `supersedeCandidateLimit * recallOverFetchFactor`, not the
     raw `supersedeCandidateLimit` (the over-fetch is the whole point — this is
     the case worth asserting directly rather than just its effect). With
     content similar (mocked `memoryIndex.search` score ≥
     `supersedeSimilarityThreshold`) to an existing same-scope entry, deletes
     that entry from both `memoryIndex` and `metadataStore` before inserting the
     new one; a similar entry in a *different* scope is left untouched; a
     similar-but-below-threshold entry is left untouched (new memory sits
     alongside it, as documented).
3. **`ProjectScopeResolverSpec.groovy`** — `Mock(Workspace)` returning different
   `baseDir` values: a path containing a `.git` directory resolves to that
   ancestor; a path containing a `.git` file (worktree) resolves the same way; a
   plain non-git directory resolves to its own canonical path; a subdirectory of
   a git repo resolves to the same `projectId` as the repo root itself.
4. **`RecalledMemoryFormatterSpec.groovy`** — the truncation logic lives here
   (shared by `MemoryPromptContributor` and `recallMemory()`), so this is where
   it's tested once rather than per-caller:
   - Under the cap: all recalled memories appear in the rendered output, each
     with its timestamp and score, in score-descending order.
   - Over the cap: with several memories whose combined rendered length exceeds
     `maxChars`, asserts the *highest-scored* memories are the ones kept and the
     lowest-scored are dropped first — not just that the final output length is
     under budget, which would pass even if the wrong memories were kept.
   - Empty input renders to `""`.
   **`MemoryPromptContributorSpec.groovy`** — thin by comparison, since
   truncation is delegated: verifies it wraps `RecalledMemoryFormatter.render(...)`
   with the "Relevant memories from earlier conversations:" header, and passes
   `settings.recallMaxContextChars` through unchanged (a `Mock`/spy on the
   formatter, or just asserting the header wraps a correctly-capped body, is
   enough — no need to re-test the truncation behaviour itself here).
5. **`MemoryMetadataStoreSpec.groovy`** — round-trips a `MemoryEntry` through JSON
   (incl. `Instant`) using `@TempDir`; verifies re-construction against the same
   file survives a simulated restart.
6. **`SurprisingLearningDetectorSpec.groovy`** — verifies: reply/input with no
   heuristic match never calls `ai` at all; a hedging/correction match does invoke
   the classifier; `surprising=false` → `memoryStore.remember` never called;
   `surprising=true` → called once with the extracted fact and the current
   `projectScopeResolver.currentProjectId()` (never `null`, i.e. never global);
   master toggle off → `ai` never invoked regardless of heuristic match.
7. **`ChatAgentSpec.groovy`** (new, alongside `CodingAssistantAgentSpec.groovy`'s
   pattern) — verifies `respondWithThinking` calls
   `memoryStore.recall(userMessage.content, ..., projectScopeResolver.currentProjectId())`,
   chains the extra `.withPromptContributor(...)` only when recall is non-empty, and
   calls `surprisingLearningDetector.maybeRemember(...)` exactly once per turn after
   `conversation.addMessage(reply)`.
8. **`CodingAssistantAgentSpec.groovy` additions**:
   - `recallMemory(query)` delegates to `memoryStore.recall` scoped to
     `projectScopeResolver.currentProjectId()`, formats the result via
     `RecalledMemoryFormatter.render(recalled, memorySettings.recallMaxContextChars)`
     — same shared cap as automatic recall, not a separate/uncapped path — or
     falls back to "No relevant memories found." when recall is empty.
   - `rememberFact(fact, scope)` — `scope="project"` (default) passes
     `projectScopeResolver.currentProjectId()` to `memoryStore.remember`;
     `scope="global"` passes `null`; both pass `sourceSessionId = null`.
9. If `EmbabelRagMemoryIndex` ends up backed by the real Lucene module, add
   `EmbabelRagMemoryIndexIntegrationSpec.groovy` (`IntegrationSpec` suffix, run
   under `verify` via the existing `maven-failsafe-plugin` convention, not `test`)
   that upserts a couple of real strings and asserts a paraphrased query returns
   the right hit above threshold — the one place the real API gets exercised
   rather than mocked.

Run `./mvnw test` after implementing, per `AGENTS.md`.

---

## 7. Docs

- `docs/architecture.md` — add a "Long-Term Memory" subsection near "4. Context
  Management" (lines 626-654), describing `MemoryStore`/`MemoryIndex`/
  `MemoryMetadataStore`/`ProjectScopeResolver`, the recall/remember/forget flow,
  and explicitly distinguishing it from `ContextCompactor`.
- `docs/roadmap.md` — mark step 1's four bullets done once implemented and tested.
- New `docs/memory.md` — config reference for all `lca.memory.*` properties, a
  short "how it works" (recall keyed off latest user turn; heuristically-gated
  automatic remember plus the explicit `rememberFact()`/`recallMemory()` tools;
  forget = age AND idle, lazy-pruned on write; supersession on write), project
  scoping (derived from the current `Workspace` directory's git root, with an
  opt-in `global` tier via `rememberFact(fact, "global")`), the storage
  location (`~/.lca/memory-index/`), and a short note that this is the only
  persistence mechanism in the app whose content survives across sessions and
  gets replayed into future conversations — including content that may
  originate from a web search result via the surprising-learning path — which
  is fine given LCA is single-user/local, but distinct enough from every other
  (transient, current-turn-only) tool output to call out explicitly.

---

## Verification

1. `./mvnw test` — all new Spock specs pass alongside the existing suite.
2. Manual smoke test via the REPL (`lca.repl.enabled=true`): start a chat, state a
   fact the model would plausibly correct or not know, confirm (via added logging
   or a temporary debug tool call) that a `MemoryEntry` lands in
   `~/.lca/memory-index/metadata.json`; start a new session and ask a related
   question, confirm recalled memory appears in the response context (e.g. via the
   `recallMemory` tool call or by checking the injected prompt block).
3. Confirm `./mvnw -q dependency:resolve` succeeds with the new `embabel-agent-rag-*`
   dependencies before relying on any of their classes.
4. Manual scoping check: from two different repos (or `Workspace.changeBaseDir`
   to two different directories in one REPL session), `rememberFact()` a
   project-specific fact in each, then confirm `recallMemory()`/automatic recall
   in repo A never surfaces the fact stored while in repo B. Repeat with
   `scope="global"` and confirm that fact *does* surface from both.
5. Manual supersession check: `rememberFact()` a fact, then `rememberFact()` a
   near-identical corrected version of it; confirm only the corrected version is
   returned by a subsequent `recallMemory()` for that topic (the stale entry
   should no longer be present in `~/.lca/memory-index/metadata.json`).
