package se.alipsa.lca.tools

import groovy.json.JsonSlurper
import groovy.transform.Canonical
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Component
@CompileStatic
class ModelRegistry {

  private static final Logger log = LoggerFactory.getLogger(ModelRegistry)
  private final URI tagsUri
  private final HttpClient client
  private final Duration timeout
  private final String baseUrl

  ModelRegistry() {
    this("http://localhost:11434", 4000L)
  }

  ModelRegistry(
    @Value('${spring.ai.ollama.base-url:http://localhost:11434}') String baseUrl,
    @Value('${assistant.llm.registry-timeout-millis:4000}') long timeoutMillis
  ) {
    this.baseUrl = baseUrl
    String normalized = baseUrl?.endsWith("/") ? baseUrl[0..-2] : baseUrl
    this.tagsUri = URI.create("${normalized}/api/tags")
    this.timeout = Duration.ofMillis(timeoutMillis > 0 ? timeoutMillis : 4000L)
    this.client = HttpClient.newBuilder().connectTimeout(this.timeout).build()
  }

  List<String> listModels() {
    Health health = checkHealth()
    if (!health.reachable) {
      return List.of()
    }
    try {
      HttpRequest request = HttpRequest.newBuilder(tagsUri).timeout(timeout).GET().build()
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString())
      if (response.statusCode() >= 200 && response.statusCode() < 300) {
        Map parsed = (Map) new JsonSlurper().parseText(response.body())
        Object modelsObj = parsed != null ? parsed.get("models") : null
        if (modelsObj instanceof List) {
          List<?> rawModels = (List<?>) modelsObj
          return rawModels.collect { Object it ->
            if (it instanceof Map && ((Map) it).containsKey("name")) {
              Object name = ((Map) it).get("name")
              return name != null ? name.toString() : null
            }
            it != null ? it.toString() : null
          }.findAll { it } as List<String>
        }
      }
      log.debug("Unexpected response listing models: status {}", response.statusCode())
    } catch (Exception e) {
      log.debug("Failed to list models from {}", tagsUri, e)
    }
    List.of()
  }

  boolean isModelAvailable(String modelName) {
    if (modelName == null || modelName.trim().isEmpty()) {
      return false
    }
    List<String> models = listModels()
    if (models.isEmpty()) {
      return true // assume available when listing failed to avoid blocking usage
    }
    models.any { it.equalsIgnoreCase(modelName.trim()) }
  }

  Health checkHealth() {
    try {
      HttpRequest request = HttpRequest.newBuilder(tagsUri).timeout(timeout).GET().build()
      HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding())
      boolean ok = response.statusCode() >= 200 && response.statusCode() < 500
      return new Health(ok, ok ? "reachable" : "received status ${response.statusCode()}".toString())
    } catch (Exception e) {
      return new Health(false, e.message ?: e.class.simpleName)
    }
  }

  @Canonical
  @CompileStatic
  static class Health {
    boolean reachable
    String message
  }

  String getBaseUrl() {
    baseUrl
  }
}
