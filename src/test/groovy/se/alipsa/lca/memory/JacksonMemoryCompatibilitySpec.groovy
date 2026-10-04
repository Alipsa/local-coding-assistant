package se.alipsa.lca.memory

import ch.qos.logback.classic.Logger
import ch.qos.logback.core.read.ListAppender
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.ai.model.ModelSelectionCriteria
import com.fasterxml.jackson.databind.ObjectMapper as LegacyMapper
import com.fasterxml.jackson.core.type.TypeReference as LegacyTypeReference
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import org.slf4j.LoggerFactory
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class JacksonMemoryCompatibilitySpec extends Specification {

  @TempDir
  Path directory

  private MemorySettings settings() {
    new MemorySettings(indexDirectory: directory.toString())
  }

  private SimpleCosineMemoryIndex index() {
    EmbeddingService service = Stub(EmbeddingService) {
      embed(_ as String) >> ([0.1f, -0.25f, 1f] as float[])
    }
    ModelProvider provider = Stub(ModelProvider) {
      getEmbeddingService(_ as ModelSelectionCriteria) >> service
    }
    new SimpleCosineMemoryIndex(provider, settings())
  }

  private static List<MemoryEntry> entries() {
    Instant nano = Instant.parse('2026-10-04T10:15:30.123456789Z')
    Instant zero = Instant.parse('2026-10-04T10:15:30Z')
    [new MemoryEntry('nano', 'legacy memory', nano, zero, 'session', null),
      new MemoryEntry('zero', 'project memory', zero, nano, 'session', 'project')]
  }

  def 'loads real Jackson 2 metadata and vectors without losing precision'() {
    given:
    ['metadata.json', 'vectors.json'].each { String name ->
      getClass().getResourceAsStream('/memory/jackson2/' + name).withCloseable { stream ->
        Files.copy(stream, directory.resolve(name))
      }
    }

    when:
    def metadata = new MemoryMetadataStore(settings())
    def vectors = index()

    then:
    entries().every { metadata.get(it.id) == it }
    metadata.get('nano').createdAt.nano == 123456789
    metadata.get('zero').createdAt.nano == 0
    metadata.get('nano').projectId == null
    Arrays.equals(vectors.@vectors.get('nano'), [0.1f, -0.25f, 1f] as float[])
  }

  def 'Jackson 2 can read new memory files including nanosecond timestamps and floats'() {
    given:
    assert new MemoryMetadataStore(settings()).putAll(entries())
    assert index().upsert('nano', 'legacy memory')
    def legacy = new LegacyMapper().registerModule(new JavaTimeModule())

    when:
    Map<String, MemoryEntry> metadata = legacy.readValue(directory.resolve('metadata.json').toFile(),
      legacy.typeFactory.constructMapType(Map, String, MemoryEntry))
    Map<String, float[]> vectors = new LegacyMapper().readValue(directory.resolve('vectors.json').toFile(),
      new LegacyTypeReference<Map<String, float[]>>() {})

    then:
    entries().every { metadata[it.id] == it }
    Files.readString(directory.resolve('metadata.json')).contains('2026-10-04T10:15:30.123456789Z')
    Arrays.equals(vectors.nano, [0.1f, -0.25f, 1f] as float[])
  }

  def 'corrupt memory files warn and leave the store usable'() {
    given:
    Files.writeString(directory.resolve(file), '{"broken":')
    Logger logger = (Logger) LoggerFactory.getLogger(type)
    def appender = new ListAppender()
    appender.start()
    logger.addAppender(appender)

    when:
    def store = type == MemoryMetadataStore ? new MemoryMetadataStore(settings()) : index()

    then:
    noExceptionThrown()
    type == MemoryMetadataStore ? store.all().isEmpty() : store.search('query', 5).isEmpty()
    appender.list.any { it.level.toString() == 'WARN' && it.formattedMessage.contains('Failed to load memory') }

    cleanup:
    logger.detachAppender(appender)
    appender.stop()

    where:
    file            | type
    'metadata.json' | MemoryMetadataStore
    'vectors.json'  | SimpleCosineMemoryIndex
  }

  def 'metadata serialisation failures return false and removal does not throw'() {
    given:
    def store = new MemoryMetadataStore(settings())
    assert store.put(entries()[0])
    String before = Files.readString(directory.resolve('metadata.json'))
    def broken = new ExplodingEntry(id: 'broken')

    expect:
    !store.put(broken)
    !store.putAll([entries()[1]])
    Files.readString(directory.resolve('metadata.json')) == before
    noTemporaryFiles()

    when:
    store.remove('zero')

    then:
    noExceptionThrown()
    Files.readString(directory.resolve('metadata.json')) == before
    noTemporaryFiles()
  }

  def 'vector serialisation failures return false and deletion does not throw'() {
    given:
    def vectors = index()
    assert vectors.upsert('original', 'content')
    String before = Files.readString(directory.resolve('vectors.json'))
    vectors.@vectors.put('broken', new ExplodingEntry(id: 'broken'))

    expect:
    !vectors.upsert('new', 'content')
    Files.readString(directory.resolve('vectors.json')) == before
    noTemporaryFiles()

    when:
    vectors.delete('new')

    then:
    noExceptionThrown()
    Files.readString(directory.resolve('vectors.json')) == before
    noTemporaryFiles()
  }

  private boolean noTemporaryFiles() {
    Files.list(directory).withCloseable { paths ->
      paths.noneMatch { it.fileName.toString().endsWith('.json.tmp') }
    }
  }

  static class ExplodingEntry extends MemoryEntry {
    @Override
    String getContent() {
      throw new IllegalStateException('Intentional serialisation failure')
    }
  }
}
