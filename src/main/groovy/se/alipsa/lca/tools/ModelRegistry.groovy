package se.alipsa.lca.tools

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Canonical
import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.lang.Nullable
import org.springframework.stereotype.Component

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale

@Component
@CompileStatic
class ModelRegistry {

  private static final Logger log = LoggerFactory.getLogger(ModelRegistry)
  private static final Set<String> LOOPBACK_HOSTS = Set.of(
    "localhost", "::1", "0:0:0:0:0:0:0:1", "0.0.0.0"
  )
  // The entire 127.0.0.0/8 range is loopback (RFC 5735), not just 127.0.0.1 — anchored so a
  // hostname that merely starts with "127." (e.g. a subdomain) doesn't false-match.
  private static final java.util.regex.Pattern LOOPBACK_IPV4_RANGE = ~/^127(\.\d{1,3}){3}$/
  private final URI tagsUri
  private final URI showUri
  private final URI psUri
  private final boolean remote
  private final HttpClient client
  private final Duration timeout
  private final String baseUrl
  private final long cacheTtlMillis
  private final long healthTtlMillis
  protected volatile List<String> cachedModels = null
  protected volatile long cachedAt = 0L
  protected volatile List<LoadedModel> cachedLoaded = null
  protected volatile long cachedLoadedAt = 0L
  private volatile Health cachedHealth = null
  private volatile long healthCachedAt = 0L

  ModelRegistry(
    @Value('${spring.ai.ollama.base-url:http://localhost:11434}') String baseUrl,
    @Value('${assistant.llm.registry-timeout-millis:4000}') long timeoutMillis,
    @Value('${assistant.llm.model-cache-ttl-millis:30000}') long cacheTtlMillis,
    @Value('${assistant.llm.health-cache-ttl-millis:5000}') long healthTtlMillis,
    @Nullable HttpClient httpClient
  ) {
    if (baseUrl == null || baseUrl.trim().isEmpty()) {
      throw new IllegalArgumentException("Ollama baseUrl must be provided")
    }
    this.baseUrl = baseUrl
    String normalized = baseUrl?.endsWith("/") ? baseUrl[0..-2] : baseUrl
    this.tagsUri = URI.create("${normalized}/api/tags")
    this.showUri = URI.create("${normalized}/api/show")
    this.psUri = URI.create("${normalized}/api/ps")
    this.remote = isRemoteHost(tagsUri.getHost())
    long effectiveTimeout = timeoutMillis > 0 ? timeoutMillis : 4000L
    this.timeout = Duration.ofMillis(effectiveTimeout)
    this.cacheTtlMillis = cacheTtlMillis > 0 ? cacheTtlMillis : 30000L
    this.healthTtlMillis = healthTtlMillis > 0 ? healthTtlMillis : 5000L
    this.client = httpClient != null
      ? httpClient
      : HttpClient.newBuilder().connectTimeout(Duration.ofMillis(effectiveTimeout)).build()
  }

  List<String> listModels() {
    long now = nowMillis()
    List<String> current
    long currentAt
    synchronized (this) {
      current = cachedModels
      currentAt = cachedAt
    }
    if (current != null && (now - currentAt) < cacheTtlMillis) {
      return List.copyOf(current)
    }
    // Hold the lock across the fetch itself (mirroring checkHealth()), not just the freshness
    // check: otherwise concurrent callers past a stale cache would each fire their own request
    // instead of coalescing onto one.
    synchronized (this) {
      if (cachedModels != null && (nowMillis() - cachedAt) < cacheTtlMillis) {
        return List.copyOf(cachedModels)
      }
      try {
        HttpResponse<String> response = fetchTags()
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
          Map parsed = (Map) new JsonSlurper().parseText(response.body())
          Object modelsObj = parsed != null ? parsed.get("models") : null
          if (modelsObj instanceof List) {
            List<?> rawModels = (List<?>) modelsObj
            List<String> models = rawModels.collect { Object it ->
              if (it instanceof Map && ((Map) it).containsKey("name")) {
                Object name = ((Map) it).get("name")
                return name != null ? name.toString() : null
              }
              it != null ? it.toString() : null
            }.findAll { it } as List<String>
            cachedModels = models
            cachedAt = nowMillis()
            return List.copyOf(models)
          }
        }
        log.debug("Unexpected response listing models: status {}", response.statusCode())
      } catch (Exception e) {
        log.debug("Failed to list models from {}", tagsUri, e)
      }
      if (cachedModels != null) {
        boolean stale = (nowMillis() - cachedAt) >= cacheTtlMillis
        if (stale) {
          log.info("Returning stale model cache due to fetch failure; cache age={}ms", nowMillis() - cachedAt)
        }
        return List.copyOf(cachedModels)
      }
      List.of()
    }
  }

  /**
   * Whether the configured Ollama base URL points somewhere other than this machine — used to
   * decide whether local host memory metrics are representative of the machine actually running
   * inference. Fixed at construction; no I/O.
   */
  boolean isRemote() {
    remote
  }

  /**
   * Pure host-string check — no DNS/InetAddress resolution (which could block or misfire across
   * networks). Known limitation: a loopback alias via {@code /etc/hosts} or mDNS (e.g.
   * {@code host.docker.internal}, {@code foo.local}) is classified as remote even if it happens
   * to resolve to this machine — acceptable until it proves wrong for a real setup.
   */
  @PackageScope
  static boolean isRemoteHost(String host) {
    if (host == null || host.trim().isEmpty()) {
      return false
    }
    String normalized = host.toLowerCase(Locale.ROOT)
    boolean loopback = LOOPBACK_HOSTS.contains(normalized) || LOOPBACK_IPV4_RANGE.matcher(normalized).matches()
    !loopback
  }

  /**
   * Currently loaded models on the Ollama server ({@code GET /api/ps}), with their reported
   * memory footprint. This reflects the host actually running inference — which may differ from
   * this machine's own stats when {@link #isRemote} is true. Cached/degraded like
   * {@link #listModels}.
   */
  List<LoadedModel> loadedModels() {
    long now = nowMillis()
    List<LoadedModel> current
    long currentAt
    synchronized (this) {
      current = cachedLoaded
      currentAt = cachedLoadedAt
    }
    if (current != null && (now - currentAt) < cacheTtlMillis) {
      return List.copyOf(current)
    }
    synchronized (this) {
      if (cachedLoaded != null && (nowMillis() - cachedLoadedAt) < cacheTtlMillis) {
        return List.copyOf(cachedLoaded)
      }
      try {
        HttpResponse<String> response = fetchPs()
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
          Map parsed = (Map) new JsonSlurper().parseText(response.body())
          Object modelsObj = parsed != null ? parsed.get("models") : null
          if (modelsObj instanceof List) {
            List<LoadedModel> models = new ArrayList<>()
            for (Object it : (List<?>) modelsObj) {
              if (it instanceof Map) {
                Map m = (Map) it
                models.add(new LoadedModel(loadedModelName(m), asLong(m.get("size")), asLong(m.get("size_vram"))))
              }
            }
            cachedLoaded = models
            cachedLoadedAt = nowMillis()
            return List.copyOf(models)
          }
        }
        log.debug("Unexpected response listing loaded models: status {}", response.statusCode())
      } catch (Exception e) {
        log.debug("Failed to list loaded models from {}", psUri, e)
      }
      if (cachedLoaded != null) {
        boolean stale = (nowMillis() - cachedLoadedAt) >= cacheTtlMillis
        if (stale) {
          log.info("Returning stale loaded-model cache due to fetch failure; cache age={}ms", nowMillis() - cachedLoadedAt)
        }
        return List.copyOf(cachedLoaded)
      }
      List.of()
    }
  }

  boolean isModelAvailable(String modelName) {
    if (modelName == null || modelName.trim().isEmpty()) {
      return false
    }
    return listModels().any { it.equalsIgnoreCase(modelName.trim()) }
  }

  Health checkHealth() {
    long now = nowMillis()
    Health healthSnapshot
    long healthAt
    synchronized (this) {
      healthSnapshot = cachedHealth
      healthAt = healthCachedAt
    }
    if (healthSnapshot != null && (now - healthAt) < healthTtlMillis) {
      return healthSnapshot
    }
    synchronized (this) {
      if (cachedHealth != null && (nowMillis() - healthCachedAt) < healthTtlMillis) {
        return cachedHealth
      }
      try {
        HttpResponse<Void> response = fetchHealth()
        boolean ok = response.statusCode() >= 200 && response.statusCode() < 300
        Health health = new Health(ok, ok ? "reachable" : "received status ${response.statusCode()}".toString())
        cachedHealth = health
        healthCachedAt = nowMillis()
        return health
      } catch (Exception e) {
        Health health = new Health(false, e.message ?: e.class.simpleName)
        cachedHealth = health
        healthCachedAt = nowMillis()
        return health
      }
    }
  }

  /**
   * The context-window size (in tokens) reported by Ollama for the given model, or
   * {@code null} when the model is unknown, unreachable, or does not advertise a
   * context length. Reads the {@code model_info.*.context_length} value from
   * {@code POST /api/show}.
   */
  Integer contextLength(String model) {
    if (model == null || model.trim().isEmpty()) {
      return null
    }
    try {
      HttpResponse<String> response = fetchShow(model.trim())
      if (response.statusCode() >= 200 && response.statusCode() < 300) {
        Map parsed = (Map) new JsonSlurper().parseText(response.body())
        Object infoObj = parsed != null ? parsed.get("model_info") : null
        if (infoObj instanceof Map) {
          Integer length = contextLengthFromModelInfo((Map<String, Object>) infoObj)
          if (length != null) {
            return length
          }
        }
      }
      log.debug("No context_length reported for model {} (status {})", model, response.statusCode())
    } catch (Exception e) {
      log.debug("Failed to fetch context length for {}", model, e)
    }
    null
  }

  /**
   * Reads the {@code context_length} for the model's own architecture out of a {@code model_info}
   * map. Ollama/GGUF metadata names the primary model via {@code general.architecture} and keys
   * its context length as {@code "<architecture>.context_length"}; a multi-component model (e.g.
   * one with a CLIP vision tower) carries additional {@code <component>.context_length} keys for
   * its sub-components, so picking the first {@code .context_length}-suffixed key found isn't
   * reliable. Prefer the architecture-qualified key; fall back to the first match when
   * {@code general.architecture} is absent or doesn't resolve (keeping today's behaviour for
   * single-component models, which don't have this ambiguity).
   */
  @PackageScope
  static Integer contextLengthFromModelInfo(Map<String, Object> info) {
    Object architecture = info.get("general.architecture")
    if (architecture instanceof String) {
      Object direct = info.get("${architecture}.context_length".toString())
      if (direct instanceof Number) {
        return ((Number) direct).intValue()
      }
    }
    for (Map.Entry<String, Object> entry : info.entrySet()) {
      String key = entry.key
      if (key != null && key.endsWith(".context_length") && entry.value instanceof Number) {
        return ((Number) entry.value).intValue()
      }
    }
    null
  }

  protected HttpResponse<String> fetchShow(String model) throws Exception {
    String body = JsonOutput.toJson([name: model])
    HttpRequest request = HttpRequest.newBuilder(showUri)
      .timeout(timeout)
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .build()
    client.send(request, HttpResponse.BodyHandlers.ofString())
  }

  protected HttpResponse<String> fetchTags() throws Exception {
    HttpRequest request = HttpRequest.newBuilder(tagsUri).timeout(timeout).GET().build()
    client.send(request, HttpResponse.BodyHandlers.ofString())
  }

  protected HttpResponse<Void> fetchHealth() throws Exception {
    HttpRequest request = HttpRequest.newBuilder(tagsUri).timeout(timeout).GET().build()
    client.send(request, HttpResponse.BodyHandlers.discarding())
  }

  protected HttpResponse<String> fetchPs() throws Exception {
    HttpRequest request = HttpRequest.newBuilder(psUri).timeout(timeout).GET().build()
    client.send(request, HttpResponse.BodyHandlers.ofString())
  }

  private static String loadedModelName(Map m) {
    Object name = m.get("name") ?: m.get("model")
    name != null ? name.toString() : null
  }

  private static long asLong(Object o) {
    o instanceof Number ? ((Number) o).longValue() : 0L
  }

  protected long nowMillis() {
    System.currentTimeMillis()
  }

  @Canonical
  @CompileStatic
  static class Health {
    boolean reachable
    String message
  }

  @Canonical
  @CompileStatic
  static class LoadedModel {
    String name
    long size       // total memory footprint, bytes
    long sizeVram   // portion resident in GPU VRAM, bytes (0 if CPU-only)
  }

  String getBaseUrl() {
    baseUrl
  }
}
