package se.alipsa.lca.shell

import groovy.transform.CompileStatic
import org.springframework.stereotype.Component

@Component
@CompileStatic
class CommandInputNormaliser {

  private static final String CHAT_COMMAND = "/chat"
  private static final String PASTE_COMMAND = "/paste"
  private static final String CONFIG_COMMAND = "/config"
  private final ShellSettings shellSettings
  private static final Set<String> BUILTIN_COMMANDS = Set.of(
    "clear",
    "history",
    "stacktrace",
    "script"
  )
  private static final Set<String> CONFIG_SHORTHAND_KEYS = Set.of(
    "auto-paste",
    "local-only",
    "web-search",
    "intent"
  )

  CommandInputNormaliser(ShellSettings shellSettings) {
    this.shellSettings = shellSettings != null ? shellSettings : new ShellSettings(true)
  }

  List<String> normaliseWords(String raw) {
    if (!isPasteCandidate(raw)) {
      return null
    }
    List.of(PASTE_COMMAND, "--content", raw, "--send", "true")
  }

  String normalise(String raw) {
    if (raw == null) {
      return null
    }
    String trimmed = raw.trim()
    if (!trimmed) {
      return raw
    }
    if (trimmed.startsWith("/")) {
      String rewritten = rewriteConfigCommand(trimmed)
      if (rewritten != null) {
        return rewritten
      }
      if (isBuiltinCommand(trimmed)) {
        return trimmed.substring(1)
      }
      return raw
    }
    "${CHAT_COMMAND} --prompt ${quote(trimmed)}"
  }

  boolean isPasteCandidate(String raw) {
    if (raw == null) {
      return false
    }
    if (raw.startsWith("/")) {
      return false
    }
    if (!shellSettings.isAutoPasteEnabled()) {
      return false
    }
    if (!raw.contains("\n") && !raw.contains("\r")) {
      return false
    }
    raw.trim() as boolean
  }

  private static String quote(String value) {
    String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
    "\"${escaped}\""
  }

  private static boolean isBuiltinCommand(String value) {
    if (value.length() < 2) {
      return false
    }
    int end = value.indexOf(" ")
    String command = end == -1 ? value.substring(1) : value.substring(1, end)
    BUILTIN_COMMANDS.contains(command)
  }

  private static String rewriteConfigCommand(String trimmed) {
    if (!trimmed.startsWith(CONFIG_COMMAND + " ")) {
      return null
    }
    String[] parts = trimmed.split("\\s+", 3)
    if (parts.length < 3) {
      return null
    }
    String key = parts[1]
    if (!CONFIG_SHORTHAND_KEYS.contains(key)) {
      return null
    }
    String value = parts[2]
    "${CONFIG_COMMAND} --${key} ${value}"
  }
}
