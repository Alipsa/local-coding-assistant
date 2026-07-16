package se.alipsa.lca.tools

import spock.lang.Specification

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class ModelRegistrySpec extends Specification {

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
    String json = '{"model_info": {"qwen3.architecture": "qwen3", "qwen3.context_length": 98304}}'
    ModelRegistry registry = new ShowRegistry(200, json)

    expect:
    registry.contextLength("qwen3.6-96k:latest") == 98304
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
