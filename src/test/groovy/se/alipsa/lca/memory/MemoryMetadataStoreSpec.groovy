package se.alipsa.lca.memory

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path
import java.time.Instant

class MemoryMetadataStoreSpec extends Specification {

  @TempDir
  Path tempDir

  private MemorySettings settingsFor(Path dir) {
    MemorySettings settings = new MemorySettings()
    settings.indexDirectory = dir.toString()
    settings
  }

  def "put persists an entry that get can retrieve"() {
    given:
    MemoryMetadataStore store = new MemoryMetadataStore(settingsFor(tempDir))
    MemoryEntry entry = new MemoryEntry("id-1", "content", Instant.EPOCH, Instant.EPOCH, "session-1", "proj-1")

    when:
    store.put(entry)

    then:
    store.get("id-1") == entry
  }

  def "get returns null for an unknown or null id"() {
    given:
    MemoryMetadataStore store = new MemoryMetadataStore(settingsFor(tempDir))

    expect:
    store.get("missing") == null
    store.get(null) == null
  }

  def "putAll persists multiple entries in a single call"() {
    given:
    MemoryMetadataStore store = new MemoryMetadataStore(settingsFor(tempDir))
    MemoryEntry entry1 = new MemoryEntry("id-1", "one", Instant.EPOCH, Instant.EPOCH, null, null)
    MemoryEntry entry2 = new MemoryEntry("id-2", "two", Instant.EPOCH, Instant.EPOCH, null, null)

    when:
    store.putAll([entry1, entry2])

    then:
    store.get("id-1") == entry1
    store.get("id-2") == entry2
    store.all().size() == 2
  }

  def "putAll is a no-op for an empty or null collection"() {
    given:
    MemoryMetadataStore store = new MemoryMetadataStore(settingsFor(tempDir))

    when:
    store.putAll([])
    store.putAll(null)

    then:
    store.all().isEmpty()
  }

  def "remove deletes an entry"() {
    given:
    MemoryMetadataStore store = new MemoryMetadataStore(settingsFor(tempDir))
    store.put(new MemoryEntry("id-1", "content", Instant.EPOCH, Instant.EPOCH, null, null))

    when:
    store.remove("id-1")

    then:
    store.get("id-1") == null
  }

  def "remove is a no-op for an unknown id"() {
    given:
    MemoryMetadataStore store = new MemoryMetadataStore(settingsFor(tempDir))

    expect:
    store.remove("missing")
    store.all().isEmpty()
  }

  def "entries survive a simulated restart via the JSON sidecar, including Instant fields"() {
    given:
    Instant createdAt = Instant.parse("2026-01-15T10:30:00Z")
    Instant lastAccessedAt = Instant.parse("2026-02-20T08:00:00Z")
    MemoryEntry entry = new MemoryEntry("id-1", "restart-safe content", createdAt, lastAccessedAt, "session-9", "proj-9")
    MemorySettings settings = settingsFor(tempDir)
    new MemoryMetadataStore(settings).put(entry)

    when: "a brand-new store instance loads from the same directory"
    MemoryMetadataStore reloaded = new MemoryMetadataStore(settings)

    then:
    reloaded.get("id-1") == entry
    reloaded.get("id-1").createdAt == createdAt
    reloaded.get("id-1").lastAccessedAt == lastAccessedAt
  }

  def "starting with no existing metadata.json file yields an empty store"() {
    given:
    MemoryMetadataStore store = new MemoryMetadataStore(settingsFor(tempDir))

    expect:
    store.all().isEmpty()
  }
}
