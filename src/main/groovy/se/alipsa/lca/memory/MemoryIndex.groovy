package se.alipsa.lca.memory

/**
 * Seam isolating the vector-store/embedding implementation from the rest of the app.
 * {@code search} takes an over-fetch count rather than the caller's real topK, because
 * project-scope filtering (see MemoryStore.recall()) happens after the index returns
 * candidates - this implementation has no native metadata filtering.
 */
interface MemoryIndex {

  void upsert(String id, String content)

  List<MemoryIndexHit> search(String queryText, int fetchCount)

  void delete(String id)
}
