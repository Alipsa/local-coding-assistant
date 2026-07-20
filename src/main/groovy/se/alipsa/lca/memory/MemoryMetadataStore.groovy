package se.alipsa.lca.memory

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.type.MapType
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Persists {@code Map<String, MemoryEntry>} as JSON at {@code indexDirectory}/metadata.json.
 * Loads into a ConcurrentHashMap at construction, writes through atomically (temp file +
 * Files.move) on every mutation.
 *
 * Concurrency note: the ConcurrentHashMap plus atomic temp-file swap protects each
 * individual write, but two overlapping writers (e.g. the GUI and the REPL both pointed
 * at the same index directory at once) can still race last-write-wins across the whole
 * map. Accepted as fine for now - LCA is single-user/local.
 */
@Component
@CompileStatic
class MemoryMetadataStore {

  private static final Logger log = LoggerFactory.getLogger(MemoryMetadataStore)

  private final Path metadataFile
  private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule())
  private final Map<String, MemoryEntry> entries = new ConcurrentHashMap<>()

  MemoryMetadataStore(MemorySettings settings) {
    Path indexDir = Paths.get(settings.indexDirectory)
    Files.createDirectories(indexDir)
    this.metadataFile = indexDir.resolve("metadata.json")
    load()
  }

  MemoryEntry get(String id) {
    id == null ? null : entries.get(id)
  }

  /**
   * @return false if the write to disk failed (entry is still visible in-memory for the rest
   * of the session; see the class Javadoc's concurrency note). Callers that need to report
   * persistence failure honestly (e.g. rememberFact()) should check this.
   */
  boolean put(MemoryEntry entry) {
    entries.put(entry.id, entry)
    persist()
  }

  /**
   * Persists several entries in a single atomic write. Used by MemoryStore.recall() so
   * bumping lastAccessedAt on every returned hit costs one write per call, not one per hit.
   * @return false if the write to disk failed.
   */
  boolean putAll(Collection<MemoryEntry> newEntries) {
    if (!newEntries) {
      return true
    }
    newEntries.each { MemoryEntry entry -> entries.put(entry.id, entry) }
    persist()
  }

  void remove(String id) {
    if (entries.remove(id) != null) {
      persist()
    }
  }

  Collection<MemoryEntry> all() {
    List.copyOf(entries.values())
  }

  private void load() {
    if (!Files.exists(metadataFile)) {
      return
    }
    try {
      MapType mapType = objectMapper.typeFactory.constructMapType(Map, String, MemoryEntry)
      Map<String, MemoryEntry> loaded = objectMapper.readValue(metadataFile.toFile(), mapType)
      entries.putAll(loaded)
    } catch (IOException e) {
      log.warn("Failed to load memory metadata from {}: {}", metadataFile, e.message)
    }
  }

  private synchronized boolean persist() {
    try {
      Path tempFile = Files.createTempFile(metadataFile.parent, "metadata", ".json.tmp")
      objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), entries)
      Files.move(tempFile, metadataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      true
    } catch (IOException e) {
      log.warn("Failed to persist memory metadata to {}: {}", metadataFile, e.message)
      false
    }
  }
}
