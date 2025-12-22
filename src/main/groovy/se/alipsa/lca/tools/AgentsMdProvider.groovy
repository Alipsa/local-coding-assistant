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

@Component
@CompileStatic
class AgentsMdProvider {

  private static final Logger log = LoggerFactory.getLogger(AgentsMdProvider)
  private static final String AGENTS_FILE = "AGENTS.md"

  private final Path agentsPath
  private final int maxChars
  private volatile String cachedContent
  private volatile long cachedModified = -1L

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
    String cached = cachedContent
    if (cached != null && cachedModified == modified) {
      return cached
    }
    if (!Files.isReadable(agentsPath)) {
      log.warn("AGENTS.md exists but is not readable: {}", agentsPath)
      return null
    }
    String content
    try {
      content = Files.readString(agentsPath, StandardCharsets.UTF_8)
    } catch (IOException e) {
      log.warn("Failed to read AGENTS.md at {}", agentsPath, e)
      return null
    }
    String trimmed = content != null ? content.trim() : ""
    if (trimmed.isEmpty()) {
      cachedContent = null
      cachedModified = modified
      return null
    }
    if (maxChars > 0 && trimmed.length() > maxChars) {
      trimmed = trimmed.substring(0, maxChars).trim()
    }
    cachedContent = trimmed
    cachedModified = modified
    trimmed
  }

  private void clearCache() {
    cachedContent = null
    cachedModified = -1L
  }
}
