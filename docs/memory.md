# Long-Term Memory

Roadmap item 3, step 1: a memory store so the assistant can recall relevant facts from
earlier conversations and learn new ones as it goes, persisting across sessions and restarts.

`rememberFact()`/automatic remembering is the first mechanism in LCA that persists data across
sessions and restarts (everything else — conversation history, context compaction — lives only
in memory for the lifetime of a single process). Treat `~/.lca/memory-index/` accordingly: it's
local-only, but it *is* durable state, not disposable cache.

## How it works

- **Recall**: before each assistant turn, `ChatAgent` calls `MemoryStore.recall()` with the
  latest user message as the query. Matches are injected into the prompt via
  `MemoryPromptContributor`. The model can also call `recallMemory(query)` directly to search on
  demand.
- **Remember**: after each turn, `SurprisingLearningDetector` runs a cheap local keyword scan
  over the exchange (hedging phrases like "I don't know", correction markers like "actually,").
  Only if that heuristic matches does it make an LLM call to classify whether something
  surprising was actually learned; if so, the fact is stored. The model can also call
  `rememberFact(fact, scope)` directly to store something explicitly (`scope="project"`, the
  default, or `scope="global"`).
- **Supersession**: when a new memory is stored, any existing memory in the same scope that's
  near-identical in topic (above `supersede-similarity-threshold`) is deleted first, so a
  correction replaces the stale fact it corrects rather than sitting alongside it. This only
  catches near-duplicate topics — a differently-worded contradiction may not be detected.
- **Forgetting**: a memory is only evicted once it is *both* older than `max-age-days` *and* has
  not been recalled for longer than `max-idle-days`. This is checked opportunistically on writes
  (rate-limited by `prune-min-interval-minutes`), not on a background schedule.
- **Scoping**: memories are scoped to the current git repository root (see `ProjectScopeResolver`)
  by default, or global (`projectId = null`) when explicitly requested via `rememberFact(...,
  scope: "global")`. Recall always considers both the current project's memories and global ones.

## Storage

Everything lives under `lca.memory.index-directory` (default `~/.lca/memory-index/`):

- `metadata.json` — id, content, `createdAt`/`lastAccessedAt`, source session, project scope
- `vectors.json` — embedding vectors, one per memory, used for brute-force cosine similarity
  search (see `SimpleCosineMemoryIndex`'s Javadoc for why this was chosen over
  `embabel-agent-rag-lucene`)

Both are plain JSON, written atomically (temp file + rename) on every mutation.

## Configuration

All properties are under the `lca.memory.*` prefix.

| Property | Default | Description |
|---|---|---|
| `enabled` | `true` | Master switch for recall/remember. |
| `embedding-model` | `nomic-embed-text:latest` | Ollama model used to embed memory content and queries. Must be pulled locally (`models.sh` does this). |
| `index-directory` | `~/.lca/memory-index` | Where `metadata.json`/`vectors.json` are stored. |
| `recall-top-k` | `5` | Max memories returned by a single recall. |
| `recall-min-score` | `0.6` | Minimum cosine-similarity score for a recalled memory to surface. |
| `max-age-days` | `180` | A memory is eligible for forgetting once at least this old... |
| `max-idle-days` | `30` | ...**and** not recalled for at least this long. Both must hold. |
| `prune-min-interval-minutes` | `60` | Minimum time between opportunistic forget scans. |
| `surprising-learning-detection-enabled` | `true` | Whether automatic remembering runs at all. |
| `surprising-learning-model` | *(blank)* | Optional model override for the surprise classifier; falls back to `assistant.llm.fallback-model` when blank. |
| `recall-max-context-chars` | `2000` | Cap on how much recalled-memory text is injected into a prompt (automatic recall and `recallMemory()` share this cap). |
| `max-memory-content-chars` | `500` | Memory content longer than this is truncated on write. |
| `recall-over-fetch-factor` | `4` | How much to over-fetch from the index before scope-filtering (the index itself has no concept of project scope). |
| `supersede-similarity-threshold` | `0.85` | How similar a new memory must be to an existing one to replace it. Deliberately stricter than `recall-min-score`. |
| `supersede-candidate-limit` | `3` | Max candidates considered for supersession per write. |

## Setup

Requires the `nomic-embed-text` embedding model to be pulled locally:

```
ollama pull nomic-embed-text:latest
```

`models.sh` does this automatically as part of the standard local model setup.
