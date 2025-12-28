package se.alipsa.lca.tools

import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference

@Component
@CompileStatic
class AgentsMdProvider {

  private static final Logger log = LoggerFactory.getLogger(AgentsMdProvider)
  private static final String AGENTS_FILE = "AGENTS.md"

  private final Path agentsPath
  private final int maxChars
  private final Object cacheLock = new Object()
  private final AtomicReference<CacheEntry> cache = new AtomicReference<>()

  AgentsMdProvider(
    FileEditingTool fileEditingTool,
    @Value('${assistant.agents.max-chars:0}') int maxChars
  ) {
    Path root = fileEditingTool?.getProjectRoot() ?: Paths.get(".").toAbsolutePath().normalize()
    this.agentsPath = root.resolve(AGENTS_FILE)
    this.maxChars = Math.max(0, maxChars)
  }

  String appendToSystemPrompt(String basePrompt) {
    String agents = readAgents()
    if (agents == null || agents.isEmpty()) {
      return basePrompt?.trim() ?: ""
    }
    String base = basePrompt?.trim()
    if (base) {
      return base + "\n\nAGENTS.md:\n" + agents
    }
    "AGENTS.md:\n" + agents
  }

  String readAgents() {
    if (!Files.exists(agentsPath)) {
      clearCache()
      return null
    }
    long modified
    try {
      modified = Files.getLastModifiedTime(agentsPath).toMillis()
    } catch (IOException e) {
      log.warn("Failed to read AGENTS.md timestamp at {}", agentsPath, e)
      return null
    }
    CacheEntry cached = cache.get()
    if (cached != null && cached.modified == modified) {
      return cached.content
    }
    synchronized (cacheLock) {
      cached = cache.get()
      if (cached != null && cached.modified == modified) {
        return cached.content
      }
      if (!Files.isRegularFile(agentsPath)) {
        log.warn("AGENTS.md exists but is not a regular file: {}", agentsPath)
        cache.set(new CacheEntry(modified, null))
        return null
      }
      if (!Files.isReadable(agentsPath)) {
        log.warn("AGENTS.md exists but is not readable: {}", agentsPath)
        cache.set(new CacheEntry(modified, null))
        return null
      }
      String content
      try {
        content = Files.readString(agentsPath, StandardCharsets.UTF_8)
      } catch (IOException e) {
        log.warn("Failed to read AGENTS.md at {}", agentsPath, e)
        cache.set(new CacheEntry(modified, null))
        return null
      }
      String trimmed = content != null ? content.trim() : ""
      if (trimmed.isEmpty()) {
        cache.set(new CacheEntry(modified, null))
        return null
      }
      if (maxChars > 0) {
        trimmed = truncateToMaxCodePoints(content, maxChars).trim()
        if (trimmed.isEmpty()) {
          cache.set(new CacheEntry(modified, null))
          return null
        }
      }
      cache.set(new CacheEntry(modified, trimmed))
      return trimmed
    }
  }

  private void clearCache() {
    cache.set(null)
  }

  private static String truncateToMaxCodePoints(String value, int maxChars) {
    if (value == null || maxChars <= 0) {
      return value ?: ""
    }
    int codePoints = value.codePointCount(0, value.length())
    if (codePoints <= maxChars) {
      return value
    }
    int endIndex = value.offsetByCodePoints(0, maxChars)
    value.substring(0, endIndex)
  }

  private static final class CacheEntry {
    final long modified
    final String content

    CacheEntry(long modified, String content) {
      this.modified = modified
      this.content = content
    }
  }
}
