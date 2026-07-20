package se.alipsa.lca.memory

/**
 * Seam isolating the vector-store/embedding implementation from the rest of the app.
 * {@code search} takes an over-fetch count rather than the caller's real topK, because
 * project-scope filtering (see MemoryStore.recall()) happens after the index returns
 * candidates - this implementation has no native metadata filtering.
 */
interface MemoryIndex {

  /** @return false if the underlying write failed (e.g. disk full); the in-memory index is
   * still updated either way, so a failure here only means the entry may not survive a restart. */
  boolean upsert(String id, String content)

  List<MemoryIndexHit> search(String queryText, int fetchCount)

  void delete(String id)
}
