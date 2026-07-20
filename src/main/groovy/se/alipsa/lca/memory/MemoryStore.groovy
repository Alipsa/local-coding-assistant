package se.alipsa.lca.memory

import groovy.transform.CompileStatic
import org.springframework.stereotype.Component

import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The long-term memory service: recall-by-similarity, store-on-write with same-topic
 * supersession, and forget-if-old-AND-idle eviction. See docs/memory.md.
 */
@Component
@CompileStatic
class MemoryStore {

  private final MemoryIndex memoryIndex
  private final MemoryMetadataStore metadataStore
  private final MemorySettings settings
  private volatile Instant lastPruneAt = Instant.EPOCH

  MemoryStore(MemoryIndex memoryIndex, MemoryMetadataStore metadataStore, MemorySettings settings) {
    this.memoryIndex = memoryIndex
    this.metadataStore = metadataStore
    this.settings = settings
  }

  /**
   * Persists a new memory, superseding any near-duplicate existing memory in the same scope.
   * projectId: null for a global memory (applies across every project), otherwise the scope
   * this memory belongs to - see ProjectScopeResolver.
   */
  MemoryEntry remember(String content, String sessionId, String projectId) {
    if (!settings.enabled || !content?.trim()) {
      return null
    }
    String truncated = truncate(content.trim(), settings.maxMemoryContentChars)

    supersede(truncated, projectId)

    Instant now = Instant.now()
    String id = UUID.randomUUID().toString()
    MemoryEntry entry = new MemoryEntry(id, truncated, now, now, sessionId, projectId)
    memoryIndex.upsert(id, truncated)
    metadataStore.put(entry)
    maybeForget()
    entry
  }

  /**
   * Semantic-similarity recall against the given query text, scoped to the caller's current
   * project plus any global memories. Updates lastAccessedAt on every hit returned, persisted
   * in a single write for the whole call (not one write per hit).
   */
  List<RecalledMemory> recall(String query, int topK, String projectId) {
    if (!settings.enabled || !query?.trim()) {
      return []
    }
    int effectiveTopK = topK > 0 ? topK : settings.recallTopK
    int fetchCount = effectiveTopK * settings.recallOverFetchFactor
    List<MemoryIndexHit> hits = memoryIndex.search(query.trim(), fetchCount)

    Instant now = Instant.now()
    List<RecalledMemory> survivors = []
    List<MemoryEntry> updatedEntries = []
    for (MemoryIndexHit hit : hits) {
      if (survivors.size() >= effectiveTopK) {
        break
      }
      if (hit.score < settings.recallMinScore) {
        continue
      }
      MemoryEntry entry = metadataStore.get(hit.id)
      if (entry == null || (entry.projectId != null && entry.projectId != projectId)) {
        continue
      }
      entry.lastAccessedAt = now
      updatedEntries << entry
      survivors << new RecalledMemory(entry, hit.score)
    }
    if (updatedEntries) {
      metadataStore.putAll(updatedEntries)
    }
    survivors.sort { RecalledMemory a, RecalledMemory b -> b.score <=> a.score }
    survivors
  }

  /**
   * Evicts memories that are both older than maxAgeDays AND idle longer than maxIdleDays.
   * Scope-independent - a memory is forgotten by age/idle regardless of project.
   */
  int forget() {
    Instant now = Instant.now()
    Instant ageCutoff = now.minus(settings.maxAgeDays, ChronoUnit.DAYS)
    Instant idleCutoff = now.minus(settings.maxIdleDays, ChronoUnit.DAYS)
    List<MemoryEntry> toForget = metadataStore.all().findAll { MemoryEntry e ->
      e.createdAt.isBefore(ageCutoff) && e.lastAccessedAt.isBefore(idleCutoff)
    } as List<MemoryEntry>
    toForget.each { MemoryEntry e ->
      memoryIndex.delete(e.id)
      metadataStore.remove(e.id)
    }
    toForget.size()
  }

  /**
   * Lazy pruning triggered from remember() (a write path), rate-limited by
   * pruneMinIntervalMinutes so it isn't a full metadata scan on every write.
   */
  private void maybeForget() {
    Instant now = Instant.now()
    if (Duration.between(lastPruneAt, now).toMinutes() < settings.pruneMinIntervalMinutes) {
      return
    }
    lastPruneAt = now
    forget()
  }

  /**
   * Same over-fetch-then-filter shape as recall(), for the identical reason - the index has no
   * scope awareness. Deletes near-duplicate same-scope entries before the new one is inserted,
   * so a correction supersedes the stale entry it replaces rather than sitting alongside it.
   * Known limitation: only catches a new memory that is textually/semantically similar to the
   * one it replaces - a differently-worded contradiction may fall under
   * supersedeSimilarityThreshold and simply coexist with the stale memory. Catching that
   * reliably would need a second LLM classification call per write; accepted as a step-1 gap.
   */
  private void supersede(String content, String projectId) {
    int fetchCount = settings.supersedeCandidateLimit * settings.recallOverFetchFactor
    List<MemoryIndexHit> hits = memoryIndex.search(content, fetchCount)
    int matched = 0
    for (MemoryIndexHit hit : hits) {
      if (matched >= settings.supersedeCandidateLimit) {
        break
      }
      if (hit.score < settings.supersedeSimilarityThreshold) {
        continue
      }
      MemoryEntry candidate = metadataStore.get(hit.id)
      if (candidate == null || candidate.projectId != projectId) {
        continue
      }
      memoryIndex.delete(candidate.id)
      metadataStore.remove(candidate.id)
      matched++
    }
  }

  private static String truncate(String content, int maxChars) {
    (maxChars > 0 && content.length() > maxChars) ? content.substring(0, maxChars) : content
  }
}
