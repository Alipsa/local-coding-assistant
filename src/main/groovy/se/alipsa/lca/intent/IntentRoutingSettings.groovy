package se.alipsa.lca.intent

import groovy.transform.CompileStatic
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

import java.util.Collections
import java.util.Locale

@Component
@CompileStatic
class IntentRoutingSettings {

  private final boolean enabled
  private final Set<String> destructiveCommands

  IntentRoutingSettings(
    @Value('${assistant.intent.enabled:true}') boolean enabled,
    @Value('${assistant.intent.destructive-commands:/edit,/apply,/run,/gitapply,/git-push}') String destructiveCommands
  ) {
    this.enabled = enabled
    this.destructiveCommands = parseCommands(destructiveCommands)
  }

  boolean isEnabled() {
    enabled
  }

  boolean isDestructiveCommand(String command) {
    if (command == null || command.trim().isEmpty()) {
      return false
    }
    destructiveCommands.contains(normaliseCommand(command))
  }

  Set<String> getDestructiveCommands() {
    destructiveCommands
  }

  private static Set<String> parseCommands(String value) {
    if (value == null || value.trim().isEmpty()) {
      return Set.of()
    }
    Set<String> commands = new LinkedHashSet<>()
    value.split(",").each { String raw ->
      String trimmed = raw != null ? raw.trim() : ""
      if (!trimmed) {
        return
      }
      commands.add(normaliseCommand(trimmed))
    }
    commands.isEmpty() ? Set.of() : Collections.unmodifiableSet(commands)
  }

  private static String normaliseCommand(String value) {
    String trimmed = value.trim()
    String base = trimmed.startsWith("/") ? trimmed : "/${trimmed}"
    base.toLowerCase(Locale.ROOT)
  }
}
