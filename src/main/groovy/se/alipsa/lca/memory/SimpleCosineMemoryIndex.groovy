package se.alipsa.lca.memory

import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.ai.model.ModelSelectionCriteria
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
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
 * MemoryIndex backed directly by an EmbeddingService - brute-force cosine similarity over
 * embeddings persisted to a JSON sidecar, no vector-store library involved.
 *
 * Chosen over embabel-agent-rag-lucene after inspecting its 0.5.0 API: LuceneSearchOperations
 * does not implement FilteringVectorSearch/FilteringTextSearch (no native metadata-field
 * filtering), and its only deletion entry point is deleteRootAndDescendants(uri) - a
 * document/hierarchy-level operation, not the atomic per-id delete a short-fact memory store
 * needs for forget()/supersession. The RAG module's ingestion/chunking pipeline is built for
 * document RAG, not atomic fact storage, so this direct approach is a better fit and needs no
 * dependency beyond what embabel-agent-starter-ollama already provides (ModelProvider is
 * already a registered Spring bean via AgentPlatformConfiguration.modelProvider(...)).
 */
@Component
@CompileStatic
class SimpleCosineMemoryIndex implements MemoryIndex {

  private static final Logger log = LoggerFactory.getLogger(SimpleCosineMemoryIndex)

  private final ModelProvider modelProvider
  private final MemorySettings settings
  private final Path vectorFile
  private final ObjectMapper objectMapper = new ObjectMapper()
  private final Map<String, float[]> vectors = new ConcurrentHashMap<>()

  SimpleCosineMemoryIndex(ModelProvider modelProvider, MemorySettings settings) {
    this.modelProvider = modelProvider
    this.settings = settings
    Path indexDir = Paths.get(settings.indexDirectory)
    Files.createDirectories(indexDir)
    this.vectorFile = indexDir.resolve("vectors.json")
    load()
  }

  @Override
  boolean upsert(String id, String content) {
    float[] vector = embeddingService().embed(content)
    vectors.put(id, vector)
    persist()
  }

  @Override
  List<MemoryIndexHit> search(String queryText, int fetchCount) {
    if (vectors.isEmpty() || fetchCount <= 0) {
      return []
    }
    float[] queryVector = embeddingService().embed(queryText)
    List<MemoryIndexHit> hits = vectors.entrySet().collect { Map.Entry<String, float[]> e ->
      new MemoryIndexHit(e.key, cosineSimilarity(queryVector, e.value))
    }
    hits.sort { MemoryIndexHit a, MemoryIndexHit b -> b.score <=> a.score }
    hits.take(fetchCount)
  }

  @Override
  void delete(String id) {
    if (vectors.remove(id) != null) {
      persist()
    }
  }

  private EmbeddingService embeddingService() {
    modelProvider.getEmbeddingService(ModelSelectionCriteria.byName(settings.embeddingModel))
  }

  private static double cosineSimilarity(float[] a, float[] b) {
    double dot = 0d
    double normA = 0d
    double normB = 0d
    int length = Math.min(a.length, b.length)
    for (int i = 0; i < length; i++) {
      dot += a[i] * b[i]
      normA += a[i] * a[i]
      normB += b[i] * b[i]
    }
    (normA == 0d || normB == 0d) ? 0d : dot / (Math.sqrt(normA) * Math.sqrt(normB))
  }

  private void load() {
    if (!Files.exists(vectorFile)) {
      return
    }
    try {
      Map<String, float[]> loaded = objectMapper.readValue(vectorFile.toFile(), new TypeReference<Map<String, float[]>>() {})
      vectors.putAll(loaded)
    } catch (IOException e) {
      log.warn("Failed to load memory vectors from {}: {}", vectorFile, e.message)
    }
  }

  private synchronized boolean persist() {
    try {
      Path tempFile = Files.createTempFile(vectorFile.parent, "vectors", ".json.tmp")
      objectMapper.writeValue(tempFile.toFile(), vectors)
      Files.move(tempFile, vectorFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      true
    } catch (IOException e) {
      log.warn("Failed to persist memory vectors to {}: {}", vectorFile, e.message)
      false
    }
  }
}
