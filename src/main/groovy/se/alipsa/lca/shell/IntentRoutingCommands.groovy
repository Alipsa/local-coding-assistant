package se.alipsa.lca.shell

import groovy.transform.CompileStatic
import org.springframework.shell.standard.ShellComponent
import org.springframework.shell.standard.ShellMethod
import org.springframework.shell.standard.ShellOption
import se.alipsa.lca.intent.IntentCommandRouter
import se.alipsa.lca.intent.IntentRoutingFormatter
import se.alipsa.lca.intent.IntentRoutingPlan
import se.alipsa.lca.intent.IntentRoutingSettings
import se.alipsa.lca.intent.IntentRoutingState

import java.util.Locale

@ShellComponent
@CompileStatic
class IntentRoutingCommands {

  private final IntentCommandRouter router
  private final IntentRoutingState routingState
  private final IntentRoutingSettings routingSettings

  private static final Set<String> ON_VALUES = Set.of("on", "enable", "enabled", "true", "yes", "y")
  private static final Set<String> OFF_VALUES = Set.of("off", "disable", "disabled", "false", "no", "n")

  IntentRoutingCommands(
    IntentCommandRouter router,
    IntentRoutingState routingState,
    IntentRoutingSettings routingSettings
  ) {
    this.router = router
    this.routingState = routingState
    this.routingSettings = routingSettings
  }

  @ShellMethod(key = ["/route"], value = "Route natural language into CLI commands (preview only).")
  String route(@ShellOption(help = "Input text to route") String prompt) {
    if (prompt == null || prompt.trim().isEmpty()) {
      throw new IllegalArgumentException("prompt must not be blank.")
    }
    IntentRoutingPlan plan = router.route(prompt)
    IntentRoutingFormatter.format(plan)
  }

  @ShellMethod(key = ["/intent-debug"], value = "Toggle intent routing debug output for this session.")
  String intentDebug(
    @ShellOption(defaultValue = ShellOption.NULL, help = "on or off") String mode
  ) {
    if (routingState == null) {
      return "Intent routing state is unavailable."
    }
    if (mode == null || mode.trim().isEmpty()) {
      return formatDebugStatus()
    }
    String normalised = mode.trim().toLowerCase(Locale.UK)
    if (ON_VALUES.contains(normalised)) {
      routingState.setDebugEnabled(true)
      return formatDebugStatus()
    }
    if (OFF_VALUES.contains(normalised)) {
      routingState.setDebugEnabled(false)
      return formatDebugStatus()
    }
    throw new IllegalArgumentException("Unknown mode '${mode}'. Use on or off.")
  }

  private String formatDebugStatus() {
    String state = routingState.isDebugEnabled() ? "enabled" : "disabled"
    "Intent routing debug is ${state}."
  }
}
