package se.alipsa.lca.shell

import com.embabel.agent.api.common.Ai
import se.alipsa.lca.agent.CodingAssistantAgent
import se.alipsa.lca.tools.CommandRunner
import se.alipsa.lca.tools.ContextBudgetManager
import se.alipsa.lca.tools.ContextPacker
import se.alipsa.lca.tools.FileEditingTool
import se.alipsa.lca.tools.ModelRegistry
import se.alipsa.lca.tools.TokenEstimator
import spock.lang.Specification

class ResolveModelSpec extends Specification {

  SessionState sessionState = new SessionState("default-model", 0.7d, 0.35d, 0, "", true, "fallback-model")

  def "resolveModel prefers available requested model with original casing"() {
    given:
    def cmds = commandsWithAvailable(["Qwen3", "fallback-model"])

    when:
    def res = cmds.resolveModel("qwen3")

    then:
    res.chosen == "Qwen3"
    !res.fallbackUsed
    res.requested == "qwen3"
  }

  def "resolveModel uses fallback when requested missing"() {
    given:
    def cmds = commandsWithAvailable(["fallback-model"])

    when:
    def res = cmds.resolveModel("missing")

    then:
    res.chosen == "fallback-model"
    res.fallbackUsed
    res.requested == "missing"
  }

  def "resolveModel returns requested when nothing available"() {
    given:
    def cmds = commandsWithAvailable([])

    when:
    def res = cmds.resolveModel("missing")

    then:
    res.chosen == "missing"
    !res.fallbackUsed
  }

  def "resolveModel handles null requested and empty available"() {
    given:
    def cmds = commandsWithAvailable([])

    when:
    def res = cmds.resolveModel(null)

    then:
    res.chosen == "default-model"
    !res.fallbackUsed
  }

  private TestCommands commandsWithAvailable(List<String> models) {
    CodingAssistantAgent agent = Stub()
    Ai ai = Stub()
    EditorLauncher editor = Stub()
    FileEditingTool fileEditingTool = Stub()
    se.alipsa.lca.tools.GitTool gitTool = Stub()
    CommandRunner runner = Stub()
    ModelRegistry registry = Stub() {
      listModels() >> models
      checkHealth() >> new ModelRegistry.Health(true, "ok")
      getBaseUrl() >> "http://localhost:11434"
    }
    new TestCommands(agent, ai, sessionState, editor, fileEditingTool, gitTool, runner, registry)
  }

  private class TestCommands extends ShellCommands {
    TestCommands(
      CodingAssistantAgent agent,
      Ai ai,
      SessionState state,
      EditorLauncher editor,
      FileEditingTool fileEditingTool,
      se.alipsa.lca.tools.GitTool gitTool,
      CommandRunner runner,
      ModelRegistry registry
    ) {
      super(agent, ai, state, editor, fileEditingTool, gitTool, runner, registry, ".")
    }
  }
}
