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
  private static final Set<String> DEFAULT_VALUES = Set.of("default", "reset", "auto")

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

  @ShellMethod(key = ["/intent"], value = "Enable or disable natural language routing for this session.")
  String intent(
    @ShellOption(defaultValue = ShellOption.NULL, help = "on, off, or default") String mode
  ) {
    if (routingState == null) {
      return "Intent routing state is unavailable."
    }
    if (mode == null || mode.trim().isEmpty()) {
      return formatRoutingStatus()
    }
    String normalised = mode.trim().toLowerCase(Locale.UK)
    if (ON_VALUES.contains(normalised)) {
      routingState.setEnabledOverride(true)
      return formatRoutingStatus()
    }
    if (OFF_VALUES.contains(normalised)) {
      routingState.setEnabledOverride(false)
      return formatRoutingStatus()
    }
    if (DEFAULT_VALUES.contains(normalised)) {
      routingState.clearEnabledOverride()
      return formatRoutingStatus()
    }
    throw new IllegalArgumentException("Unknown mode '${mode}'. Use on, off, or default.")
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

  private String formatRoutingStatus() {
    Boolean override = routingState.getEnabledOverride()
    boolean enabled = routingState.isEnabled(routingSettings)
    String source = override == null ? "configuration" : "session override"
    String state = enabled ? "enabled" : "disabled"
    "Intent routing is ${state} (${source})."
  }

  private String formatDebugStatus() {
    String state = routingState.isDebugEnabled() ? "enabled" : "disabled"
    "Intent routing debug is ${state}."
  }
}
