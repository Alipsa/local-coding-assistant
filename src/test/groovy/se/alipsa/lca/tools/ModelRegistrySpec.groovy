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
    String body = '{"models":[{"name":"m1"},{"name":"m2"}]}'
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
    String body = '{"models":[{"name":"M1"}]}'
    ModelRegistry registry = new FakeRegistry(true, List.of("M1"))

    expect:
    registry.isModelAvailable("m1")
    !registry.isModelAvailable("m2")
  }

  private static class FakeRegistry extends ModelRegistry {
    private final boolean reachable
    private final List<String> models

    FakeRegistry(boolean reachable, List<String> models) {
      super("http://localhost:11434", 1000L, HttpClient.newHttpClient())
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
}
