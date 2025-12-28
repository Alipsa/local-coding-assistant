package se.alipsa.lca.shell

import groovy.transform.CompileStatic
import org.springframework.stereotype.Component

@Component
@CompileStatic
class CommandInputNormaliser {

  private static final String CHAT_COMMAND = "/chat"
  private static final String PASTE_COMMAND = "/paste"
  private final ShellSettings shellSettings
  private static final Set<String> BUILTIN_COMMANDS = Set.of(
    "help",
    "clear",
    "history",
    "stacktrace",
    "script",
    "completion"
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
}
