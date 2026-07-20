package se.alipsa.lca.memory

import spock.lang.Specification
import spock.lang.Unroll

import java.time.Instant
import java.time.temporal.ChronoUnit

class MemoryStoreSpec extends Specification {

  MemoryIndex memoryIndex = Mock(MemoryIndex)
  // Default so remember()'s maybeForget() -> forget() -> metadataStore.all() doesn't NPE in tests
  // that aren't specifically exercising forget()/maybeForget(); overridden per-test where needed.
  MemoryMetadataStore metadataStore = Mock(MemoryMetadataStore) {
    all() >> []
  }
  MemorySettings settings = new MemorySettings()
  MemoryStore store = new MemoryStore(memoryIndex, metadataStore, settings)

  def "remember stores a new entry with matching createdAt and lastAccessedAt"() {
    given:
    settings.recallOverFetchFactor = 4
    settings.supersedeCandidateLimit = 3

    when:
    MemoryEntry entry = store.remember("a new fact", "session-1", "proj-1")

    then:
    1 * memoryIndex.search("a new fact", 12) >> []
    1 * memoryIndex.upsert(_ as String, "a new fact")
    1 * metadataStore.put({ MemoryEntry e -> e.content == "a new fact" && e.projectId == "proj-1" })
    entry != null
    entry.content == "a new fact"
    entry.createdAt == entry.lastAccessedAt
    entry.sourceSessionId == "session-1"
    entry.projectId == "proj-1"
  }

  def "remember returns null when memory is disabled"() {
    given:
    settings.enabled = false

    when:
    MemoryEntry entry = store.remember("fact", "session-1", "proj-1")

    then:
    entry == null
    0 * memoryIndex._
    0 * metadataStore._
  }

  def "remember returns null for blank content"() {
    expect:
    store.remember(null, "session-1", "proj-1") == null
    store.remember("   ", "session-1", "proj-1") == null
  }

  def "remember truncates content to maxMemoryContentChars"() {
    given:
    settings.maxMemoryContentChars = 5
    String longContent = "0123456789"

    when:
    MemoryEntry entry = store.remember(longContent, null, null)

    then:
    memoryIndex.search(_, _) >> []
    entry.content == "01234"
  }

  def "remember does not truncate when maxMemoryContentChars is non-positive"() {
    given:
    settings.maxMemoryContentChars = 0
    String content = "0123456789"

    when:
    MemoryEntry entry = store.remember(content, null, null)

    then:
    memoryIndex.search(_, _) >> []
    entry.content == content
  }

  def "recall returns an empty list when memory is disabled"() {
    given:
    settings.enabled = false

    expect:
    store.recall("query", 5, "proj-1") == []
  }

  def "recall returns an empty list for a blank query"() {
    expect:
    store.recall(null, 5, "proj-1") == []
    store.recall("  ", 5, "proj-1") == []
  }

  def "recall over-fetches by recallOverFetchFactor before filtering"() {
    given:
    settings.recallOverFetchFactor = 4

    when:
    store.recall("query", 3, "proj-1")

    then:
    1 * memoryIndex.search("query", 12) >> []
  }

  def "recall filters out hits below recallMinScore"() {
    given:
    settings.recallMinScore = 0.6d
    MemoryEntry entry = new MemoryEntry("id-1", "content", Instant.EPOCH, Instant.EPOCH, null, null)

    when:
    List<RecalledMemory> recalled = store.recall("query", 5, null)

    then:
    1 * memoryIndex.search("query", _) >> [
      new MemoryIndexHit("id-1", 0.59d),
    ]
    0 * metadataStore.get(_)
    recalled == []
  }

  def "recall sorts survivors by score descending"() {
    given:
    MemoryEntry entryA = new MemoryEntry("id-a", "a", Instant.EPOCH, Instant.EPOCH, null, null)
    MemoryEntry entryB = new MemoryEntry("id-b", "b", Instant.EPOCH, Instant.EPOCH, null, null)

    when:
    List<RecalledMemory> recalled = store.recall("query", 5, null)

    then:
    1 * memoryIndex.search("query", _) >> [
      new MemoryIndexHit("id-a", 0.7d),
      new MemoryIndexHit("id-b", 0.9d),
    ]
    1 * metadataStore.get("id-a") >> entryA
    1 * metadataStore.get("id-b") >> entryB
    1 * metadataStore.putAll(_ as Collection<MemoryEntry>)
    recalled*.entry.id == ["id-b", "id-a"]
  }

  def "recall caps results at the effective topK"() {
    given:
    MemoryEntry entryA = new MemoryEntry("id-a", "a", Instant.EPOCH, Instant.EPOCH, null, null)
    MemoryEntry entryB = new MemoryEntry("id-b", "b", Instant.EPOCH, Instant.EPOCH, null, null)
    MemoryEntry entryC = new MemoryEntry("id-c", "c", Instant.EPOCH, Instant.EPOCH, null, null)

    when:
    List<RecalledMemory> recalled = store.recall("query", 2, null)

    then:
    1 * memoryIndex.search("query", _) >> [
      new MemoryIndexHit("id-a", 0.9d),
      new MemoryIndexHit("id-b", 0.8d),
      new MemoryIndexHit("id-c", 0.7d),
    ]
    metadataStore.get("id-a") >> entryA
    metadataStore.get("id-b") >> entryB
    metadataStore.get("id-c") >> entryC
    metadataStore.putAll(_)
    recalled.size() == 2
    recalled*.entry.id == ["id-a", "id-b"]
  }

  def "recall uses settings.recallTopK when topK is non-positive"() {
    given:
    settings.recallTopK = 2
    settings.recallOverFetchFactor = 4

    when:
    store.recall("query", 0, "proj-1")

    then:
    1 * memoryIndex.search("query", 8) >> []
  }

  def "recall bumps lastAccessedAt in a single batched write, not one write per hit"() {
    given:
    MemoryEntry entryA = new MemoryEntry("id-a", "a", Instant.EPOCH, Instant.EPOCH, null, null)
    MemoryEntry entryB = new MemoryEntry("id-b", "b", Instant.EPOCH, Instant.EPOCH, null, null)

    when:
    store.recall("query", 5, null)

    then:
    1 * memoryIndex.search("query", _) >> [
      new MemoryIndexHit("id-a", 0.9d),
      new MemoryIndexHit("id-b", 0.8d),
    ]
    metadataStore.get("id-a") >> entryA
    metadataStore.get("id-b") >> entryB
    1 * metadataStore.putAll({ Collection<MemoryEntry> updated ->
      updated.size() == 2 && updated.every { it.lastAccessedAt != Instant.EPOCH }
    })
    0 * metadataStore.put(_)
  }

  def "recall does not call putAll when there are no survivors"() {
    when:
    store.recall("query", 5, null)

    then:
    1 * memoryIndex.search("query", _) >> []
    0 * metadataStore.putAll(_)
  }

  def "recall drops a hit whose metadata entry is missing"() {
    when:
    List<RecalledMemory> recalled = store.recall("query", 5, "proj-1")

    then:
    1 * memoryIndex.search("query", _) >> [new MemoryIndexHit("id-a", 0.9d)]
    1 * metadataStore.get("id-a") >> null
    recalled == []
  }

  @Unroll
  def "recall scope filtering: entry projectId=#entryProjectId visible under caller projectId=#callerProjectId is #visible"() {
    given:
    MemoryEntry entry = new MemoryEntry("id-a", "a", Instant.EPOCH, Instant.EPOCH, null, entryProjectId)

    when:
    List<RecalledMemory> recalled = store.recall("query", 5, callerProjectId)

    then:
    1 * memoryIndex.search("query", _) >> [new MemoryIndexHit("id-a", 0.9d)]
    metadataStore.get("id-a") >> entry
    recalled.size() == (visible ? 1 : 0)

    where:
    entryProjectId | callerProjectId || visible
    null           | "proj-1"        || true
    null           | null            || true
    "proj-1"       | "proj-1"        || true
    "proj-1"       | "proj-2"        || false
    "proj-1"       | null            || false
  }

  @Unroll
  def "forget: createdAt #createdDaysAgo days ago, lastAccessedAt #idleDaysAgo days ago -> forgotten=#forgotten"() {
    given:
    settings.maxAgeDays = 180
    settings.maxIdleDays = 30
    Instant now = Instant.now()
    MemoryEntry entry = new MemoryEntry(
      "id-1", "content",
      now.minus(createdDaysAgo, ChronoUnit.DAYS),
      now.minus(idleDaysAgo, ChronoUnit.DAYS),
      null, null
    )

    when:
    int removed = store.forget()

    then:
    1 * metadataStore.all() >> [entry]
    removed == (forgotten ? 1 : 0)
    (forgotten ? 1 : 0) * memoryIndex.delete("id-1")
    (forgotten ? 1 : 0) * metadataStore.remove("id-1")

    where:
    createdDaysAgo | idleDaysAgo || forgotten
    200            | 40          || true
    200            | 10          || false
    100            | 40          || false
    100            | 10          || false
  }

  def "maybeForget triggers a forget scan on the first remember call, then gates subsequent calls within the interval"() {
    given:
    settings.pruneMinIntervalMinutes = 60

    when: "the first remember() call happens (lastPruneAt starts at Instant.EPOCH, long ago)"
    store.remember("first fact", null, null)

    then:
    memoryIndex.search(_, _) >> []
    1 * metadataStore.all() >> []

    when: "a second remember() call happens moments later, well within pruneMinIntervalMinutes"
    store.remember("second fact", null, null)

    then:
    memoryIndex.search(_, _) >> []
    0 * metadataStore.all()
  }

  def "supersede over-fetches by supersedeCandidateLimit times recallOverFetchFactor"() {
    given:
    settings.supersedeCandidateLimit = 3
    settings.recallOverFetchFactor = 4

    when:
    store.remember("new fact", null, "proj-1")

    then:
    1 * memoryIndex.search("new fact", 12) >> []
  }

  def "supersede deletes a same-scope near-duplicate candidate above the threshold"() {
    given:
    settings.supersedeSimilarityThreshold = 0.85d
    MemoryEntry stale = new MemoryEntry("stale-id", "old fact", Instant.EPOCH, Instant.EPOCH, null, "proj-1")

    when:
    store.remember("corrected fact", null, "proj-1")

    then:
    1 * memoryIndex.search("corrected fact", _) >> [new MemoryIndexHit("stale-id", 0.9d)]
    1 * metadataStore.get("stale-id") >> stale
    1 * memoryIndex.delete("stale-id")
    1 * metadataStore.remove("stale-id")
  }

  def "supersede does not delete a candidate below the similarity threshold"() {
    given:
    settings.supersedeSimilarityThreshold = 0.85d

    when:
    store.remember("a different fact", null, "proj-1")

    then:
    1 * memoryIndex.search("a different fact", _) >> [new MemoryIndexHit("other-id", 0.5d)]
    0 * metadataStore.get(_)
    0 * memoryIndex.delete(_)
    0 * metadataStore.remove(_)
  }

  def "supersede does not delete a candidate from a different scope"() {
    given:
    MemoryEntry otherScope = new MemoryEntry("other-id", "old fact", Instant.EPOCH, Instant.EPOCH, null, "proj-2")

    when:
    store.remember("corrected fact", null, "proj-1")

    then:
    1 * memoryIndex.search("corrected fact", _) >> [new MemoryIndexHit("other-id", 0.95d)]
    1 * metadataStore.get("other-id") >> otherScope
    0 * memoryIndex.delete(_)
    0 * metadataStore.remove(_)
  }

  def "supersede caps deletions at supersedeCandidateLimit"() {
    given:
    settings.supersedeCandidateLimit = 1
    MemoryEntry candidateA = new MemoryEntry("id-a", "a", Instant.EPOCH, Instant.EPOCH, null, "proj-1")
    MemoryEntry candidateB = new MemoryEntry("id-b", "b", Instant.EPOCH, Instant.EPOCH, null, "proj-1")

    when:
    store.remember("corrected fact", null, "proj-1")

    then:
    1 * memoryIndex.search("corrected fact", _) >> [
      new MemoryIndexHit("id-a", 0.95d),
      new MemoryIndexHit("id-b", 0.9d),
    ]
    metadataStore.get("id-a") >> candidateA
    metadataStore.get("id-b") >> candidateB
    1 * memoryIndex.delete("id-a")
    1 * metadataStore.remove("id-a")
    0 * memoryIndex.delete("id-b")
    0 * metadataStore.remove("id-b")
  }
}
