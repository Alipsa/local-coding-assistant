package se.alipsa.lca.shell

import se.alipsa.lca.intent.IntentCommandRouter
import se.alipsa.lca.intent.IntentRoutingSettings
import se.alipsa.lca.intent.IntentRoutingState
import spock.lang.Specification

class IntentRoutingCommandsSpec extends Specification {

  def "intent debug toggles state"() {
    given:
    IntentRoutingState state = new IntentRoutingState()
    IntentRoutingSettings settings = new IntentRoutingSettings(true, "/edit")
    IntentRoutingCommands commands = new IntentRoutingCommands(Stub(IntentCommandRouter), state, settings)

    when:
    String on = commands.intentDebug("on")

    then:
    on.contains("enabled")
    state.debugEnabled

    when:
    String off = commands.intentDebug("off")

    then:
    off.contains("disabled")
    !state.debugEnabled
  }
}
