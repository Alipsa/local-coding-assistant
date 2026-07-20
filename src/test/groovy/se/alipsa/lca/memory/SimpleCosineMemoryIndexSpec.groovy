package se.alipsa.lca.memory

import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.ai.model.ModelSelectionCriteria
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * embabel-agent-rag-lucene was evaluated and rejected for this project (see the class
 * Javadoc on SimpleCosineMemoryIndex); this spec exercises the brute-force cosine-similarity
 * fallback that was built instead - docs/rag-plan.md's §6 test list still names
 * EmbabelRagMemoryIndexIntegrationSpec for the path that was ultimately not taken.
 */
class SimpleCosineMemoryIndexSpec extends Specification {

  @TempDir
  Path tempDir

  private MemorySettings settings

  def setup() {
    settings = new MemorySettings()
    settings.indexDirectory = tempDir.toString()
    settings.embeddingModel = "nomic-embed-text:latest"
  }

  private SimpleCosineMemoryIndex indexWith(EmbeddingService embeddingService) {
    ModelProvider modelProvider = Mock(ModelProvider) {
      getEmbeddingService(_ as ModelSelectionCriteria) >> embeddingService
    }
    new SimpleCosineMemoryIndex(modelProvider, settings)
  }

  def "search returns an empty list when the index is empty"() {
    given:
    EmbeddingService embeddingService = Mock(EmbeddingService)
    SimpleCosineMemoryIndex index = indexWith(embeddingService)

    when:
    List<MemoryIndexHit> hits = index.search("query", 5)

    then:
    hits == []
    0 * embeddingService.embed(_)
  }

  def "search returns an empty list when fetchCount is not positive"() {
    given:
    EmbeddingService embeddingService = Mock(EmbeddingService) {
      embed(_ as String) >> ([1f, 0f] as float[])
    }
    SimpleCosineMemoryIndex index = indexWith(embeddingService)
    index.upsert("id-1", "content")

    expect:
    index.search("query", 0) == []
  }

  def "upsert then search ranks identical vectors highest via cosine similarity"() {
    given:
    EmbeddingService embeddingService = Mock(EmbeddingService)
    embeddingService.embed("exact match") >> ([1f, 0f] as float[])
    embeddingService.embed("orthogonal") >> ([0f, 1f] as float[])
    embeddingService.embed("query") >> ([1f, 0f] as float[])
    SimpleCosineMemoryIndex index = indexWith(embeddingService)

    when:
    boolean firstUpsertResult = index.upsert("match-id", "exact match")
    index.upsert("orthogonal-id", "orthogonal")
    List<MemoryIndexHit> hits = index.search("query", 5)

    then:
    firstUpsertResult
    hits.size() == 2
    hits[0].id == "match-id"
    hits[0].score == 1.0d
    hits[1].id == "orthogonal-id"
    hits[1].score == 0.0d
  }

  def "search caps results at fetchCount"() {
    given:
    EmbeddingService embeddingService = Mock(EmbeddingService) {
      embed(_ as String) >> ([1f, 0f] as float[])
    }
    SimpleCosineMemoryIndex index = indexWith(embeddingService)
    index.upsert("id-1", "one")
    index.upsert("id-2", "two")
    index.upsert("id-3", "three")

    when:
    List<MemoryIndexHit> hits = index.search("query", 2)

    then:
    hits.size() == 2
  }

  def "delete removes a vector so it no longer surfaces in search"() {
    given:
    EmbeddingService embeddingService = Mock(EmbeddingService) {
      embed(_ as String) >> ([1f, 0f] as float[])
    }
    SimpleCosineMemoryIndex index = indexWith(embeddingService)
    index.upsert("id-1", "content")

    when:
    index.delete("id-1")

    then:
    index.search("query", 5) == []
  }

  def "delete is a no-op for an unknown id"() {
    given:
    EmbeddingService embeddingService = Mock(EmbeddingService)
    SimpleCosineMemoryIndex index = indexWith(embeddingService)

    expect:
    index.delete("missing")
  }

  def "vectors survive a simulated restart via the JSON sidecar"() {
    given:
    EmbeddingService embeddingService = Mock(EmbeddingService) {
      embed(_ as String) >> ([1f, 0f] as float[])
    }
    indexWith(embeddingService).upsert("id-1", "content")

    when: "a brand-new index instance loads from the same directory"
    EmbeddingService queryEmbeddingService = Mock(EmbeddingService) {
      embed(_ as String) >> ([1f, 0f] as float[])
    }
    SimpleCosineMemoryIndex reloaded = indexWith(queryEmbeddingService)
    List<MemoryIndexHit> hits = reloaded.search("query", 5)

    then:
    hits.size() == 1
    hits[0].id == "id-1"
  }
}
