package se.alipsa.lca.tools

import spock.lang.Specification
import spock.lang.Unroll

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ModelRegistrySpec extends Specification {

  def "listModels coalesces concurrent cache-miss callers onto a single fetch"() {
    given:
    CountDownLatch releaseFetch = new CountDownLatch(1)
    AtomicInteger fetchCount = new AtomicInteger(0)
    ModelRegistry registry = new ShowRegistry(200, "{}") {
      @Override
      protected HttpResponse<String> fetchTags() throws Exception {
        fetchCount.incrementAndGet()
        releaseFetch.await(2, TimeUnit.SECONDS)
        [statusCode: { -> 200 }, body: { -> '{"models":[{"name":"m1"}]}' }] as HttpResponse
      }
    }

    when:
    List<Thread> callers = (1..5).collect { Thread.start { registry.listModels() } }
    // Give every caller a chance to reach (and block on) the synchronized fetch before releasing it.
    Thread.sleep(200)
    releaseFetch.countDown()
    callers.each { it.join(2000) }

    then:
    fetchCount.get() == 1
    registry.listModels() == ["m1"]
  }

  def "health returns reachable only for 2xx"() {
    given:
    ModelRegistry registry = new FakeRegistry(status >= 200 && status < 300, List.of())

    expect:
    registry.checkHealth().reachable == reachable

    where:
    status || reachable
    200    || true
    204    || true
    400    || false
    503    || false
  }

  def "listModels parses names"() {
    given:
    ModelRegistry registry = new FakeRegistry(true, List.of("m1", "m2"))

    expect:
    registry.listModels() == ["m1", "m2"]
  }

  def "isModelAvailable returns false when list empty"() {
    given:
    ModelRegistry registry = new FakeRegistry(false, List.of())

    expect:
    !registry.isModelAvailable("anything")
  }

  def "isModelAvailable matches case-insensitively but returns availability"() {
    given:
    ModelRegistry registry = new FakeRegistry(true, List.of("M1"))

    expect:
    registry.isModelAvailable("m1")
    !registry.isModelAvailable("m2")
  }

  def "listModels returns empty on fetch exception"() {
    given:
    ModelRegistry registry = new ErrorRegistry(true, true)

    expect:
    registry.listModels().isEmpty()
  }

  def "checkHealth returns unreachable on exception"() {
    given:
    ModelRegistry registry = new ErrorRegistry(false, false) {
      @Override
      Health checkHealth() {
        return new Health(false, "down")
      }
    }

    expect:
    !registry.checkHealth().reachable
  }

  def "contextLength reads model_info context_length"() {
    given:
    String json = '{"model_info": {"qwen3.architecture": "qwen3", "qwen3.context_length": 131072}}'
    ModelRegistry registry = new ShowRegistry(200, json)

    expect:
    registry.contextLength("qwen3.6-128k:latest") == 131072
  }

  def "contextLength returns null when not reported"() {
    given:
    ModelRegistry registry = new ShowRegistry(200, '{"model_info": {"general.name": "x"}}')

    expect:
    registry.contextLength("m") == null
  }

  def "contextLength returns null for blank model"() {
    given:
    ModelRegistry registry = new ShowRegistry(200, '{}')

    expect:
    registry.contextLength("   ") == null
  }

  def "contextLength prefers the architecture-qualified key for a multi-component model"() {
    given:
    // A vision sub-component's context_length would sort first in the map, but the model's own
    // (general.architecture-qualified) context length is the one that should be reported.
    String json = '''
      {"model_info": {
        "general.architecture": "qwen3",
        "clip.vision_model.context_length": 4096,
        "qwen3.context_length": 131072
      }}
    '''
    ModelRegistry registry = new ShowRegistry(200, json)

    expect:
    registry.contextLength("qwen3-vl:latest") == 131072
  }

  def "contextLengthFromModelInfo falls back to the first match when general.architecture is absent"() {
    expect:
    ModelRegistry.contextLengthFromModelInfo(
      ["qwen3.architecture": "qwen3", "qwen3.context_length": 131072] as Map<String, Object>) == 131072
  }

  def "contextLengthFromModelInfo falls back to the first match when the architecture key doesn't resolve"() {
    expect:
    ModelRegistry.contextLengthFromModelInfo(
      ["general.architecture": "unknown", "qwen3.context_length": 131072] as Map<String, Object>) == 131072
  }

  @Unroll
  def "isRemoteHost('#host') == #expected"() {
    expect:
    ModelRegistry.isRemoteHost(host) == expected

    where:
    host                     || expected
    "localhost"              || false
    "LOCALHOST"              || false
    "127.0.0.1"              || false
    "::1"                    || false
    "0:0:0:0:0:0:0:1"        || false
    "0.0.0.0"                || false
    null                     || false
    ""                       || false
    "192.168.1.50"           || true
    "ollama.example.com"     || true
    "host.docker.internal"   || true
  }

  def "isRemote reflects the configured base URL's host"() {
    expect:
    !new ModelRegistry("http://localhost:11434", 1000L, 30000L, 5000L, HttpClient.newHttpClient()).isRemote()
    new ModelRegistry("http://ollama.example.com:11434", 1000L, 30000L, 5000L, HttpClient.newHttpClient()).isRemote()
  }

  def "loadedModels parses name/size/size_vram, falling back to the model key for the name"() {
    given:
    ModelRegistry registry = new PsRegistry(200, '''
      {"models": [
        {"name": "mistral:latest", "size": 5137025024, "size_vram": 5137025024},
        {"model": "qwen3.6:latest", "size": 8000000000}
      ]}
    ''')

    when:
    List<ModelRegistry.LoadedModel> loaded = registry.loadedModels()

    then:
    loaded.size() == 2
    loaded[0].name == "mistral:latest"
    loaded[0].size == 5137025024L
    loaded[0].sizeVram == 5137025024L
    loaded[1].name == "qwen3.6:latest"
    loaded[1].size == 8000000000L
    loaded[1].sizeVram == 0L
  }

  def "loadedModels returns empty for zero loaded models"() {
    expect:
    new PsRegistry(200, '{"models": []}').loadedModels().isEmpty()
  }

  def "loadedModels skips non-map entries without throwing"() {
    given:
    ModelRegistry registry = new PsRegistry(200, '{"models": ["oops", {"name": "m1", "size": 10}]}')

    expect:
    registry.loadedModels()*.name == ["m1"]
  }

  def "loadedModels returns empty on fetch exception"() {
    given:
    ModelRegistry registry = new PsRegistry(200, "{}") {
      @Override
      protected HttpResponse<String> fetchPs() throws Exception {
        throw new RuntimeException("fetch failed")
      }
    }

    expect:
    registry.loadedModels().isEmpty()
  }

  def "loadedModels coalesces concurrent cache-miss callers onto a single fetch"() {
    given:
    CountDownLatch releaseFetch = new CountDownLatch(1)
    AtomicInteger fetchCount = new AtomicInteger(0)
    ModelRegistry registry = new PsRegistry(200, "{}") {
      @Override
      protected HttpResponse<String> fetchPs() throws Exception {
        fetchCount.incrementAndGet()
        releaseFetch.await(2, TimeUnit.SECONDS)
        [statusCode: { -> 200 }, body: { -> '{"models":[{"name":"m1","size":10}]}' }] as HttpResponse
      }
    }

    when:
    List<Thread> callers = (1..5).collect { Thread.start { registry.loadedModels() } }
    Thread.sleep(200)
    releaseFetch.countDown()
    callers.each { it.join(2000) }

    then:
    fetchCount.get() == 1
    registry.loadedModels()*.name == ["m1"]
  }

  private static class ShowRegistry extends ModelRegistry {
    private final int status
    private final String body

    ShowRegistry(int status, String body) {
      super("http://localhost:11434", 1000L, 30000L, 5000L, HttpClient.newHttpClient())
      this.status = status
      this.body = body
    }

    @Override
    protected HttpResponse<String> fetchShow(String model) throws Exception {
      [statusCode: { -> status }, body: { -> body }] as HttpResponse
    }
  }

  private static class PsRegistry extends ModelRegistry {
    private final int status
    private final String body

    PsRegistry(int status, String body) {
      super("http://localhost:11434", 1000L, 30000L, 5000L, HttpClient.newHttpClient())
      this.status = status
      this.body = body
    }

    @Override
    protected HttpResponse<String> fetchPs() throws Exception {
      [statusCode: { -> status }, body: { -> body }] as HttpResponse
    }
  }

  private static class FakeRegistry extends ModelRegistry {
    private final boolean reachable
    private final List<String> models

    FakeRegistry(boolean reachable, List<String> models) {
      super("http://localhost:11434", 1000L, 30000L, 5000L, HttpClient.newHttpClient())
      this.reachable = reachable
      this.models = models
    }

    @Override
    Health checkHealth() {
      new Health(reachable, reachable ? "reachable" : "received status 503")
    }

    @Override
    List<String> listModels() {
      if (!reachable) {
        return List.of()
      }
      models
    }
  }

  private static class ErrorRegistry extends ModelRegistry {
    private final boolean reachable
    private final boolean throwOnTags

    ErrorRegistry(boolean reachable, boolean throwOnTags) {
      super("http://localhost:11434", 1000L, 30000L, 5000L, HttpClient.newHttpClient())
      this.reachable = reachable
      this.throwOnTags = throwOnTags
    }

    @Override
    protected HttpResponse<String> fetchTags() throws Exception {
      if (throwOnTags) {
        throw new RuntimeException("fetch failed")
      }
      super.fetchTags()
    }
  }
}
