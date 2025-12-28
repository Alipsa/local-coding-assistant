package se.alipsa.lca.shell

import com.embabel.agent.core.AgentPlatform
import se.alipsa.lca.agent.CodingAssistantAgent
import se.alipsa.lca.tools.AgentsMdProvider
import se.alipsa.lca.tools.CodeSearchTool
import se.alipsa.lca.tools.ContextBudgetManager
import se.alipsa.lca.tools.ContextPacker
import se.alipsa.lca.tools.CommandRunner
import se.alipsa.lca.tools.CommandPolicy
import se.alipsa.lca.tools.GitTool
import se.alipsa.lca.tools.ModelRegistry
import se.alipsa.lca.tools.FileEditingTool
import se.alipsa.lca.tools.TokenEstimator
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

class CodeSearchCommandSpec extends Specification {

  AgentsMdProvider agentsMdProvider = Stub() {
    appendToSystemPrompt(_) >> { String base -> base }
  }
  SessionState sessionState = new SessionState(
    "default-model",
    0.7d,
    0.35d,
    0,
    "",
    true,
    false,
    "fallback",
    agentsMdProvider
  )
  CodingAssistantAgent agent = Mock()
  FileEditingTool fileEditingTool = Stub()
  GitTool gitTool = Stub()
  CodeSearchTool codeSearchTool = Mock()
  ContextPacker contextPacker = new ContextPacker()
  ContextBudgetManager budgetManager = new ContextBudgetManager(1000, 500, new TokenEstimator(), 2, -1)
  EditorLauncher editorLauncher = Stub() {
    edit(_) >> ""
  }
  CommandRunner commandRunner = Stub()
  CommandPolicy commandPolicy = new CommandPolicy("", "")
  ModelRegistry modelRegistry = Stub() {
    listModels() >> List.of()
    checkHealth() >> new ModelRegistry.Health(true, "ok")
  }
  ShellSettings shellSettings = new ShellSettings(true)
  @TempDir
  Path tempDir
  ShellCommands commands

  def setup() {
    commands = new ShellCommands(
      agent,
      Stub(com.embabel.agent.api.common.Ai),
      sessionState,
      editorLauncher,
      fileEditingTool,
      gitTool,
      codeSearchTool,
      contextPacker,
      budgetManager,
      commandRunner,
      commandPolicy,
      modelRegistry,
      Stub(AgentPlatform),
      tempDir.resolve("reviews.log").toString(),
      null,
      null,
      shellSettings
    )
  }

  def "codesearch delegates to tool and formats packed output"() {
    given:
    def hit = new CodeSearchTool.SearchHit("src/App.groovy", 10, 1, "10 | match")
    codeSearchTool.search("query", ["src"], 2, 5) >> [hit]

    when:
    def out = commands.codeSearch("query", ["src"], 2, 5, true, 100, 10)

    then:
    out.contains("Packed 1 matches")
    out.contains("src/App.groovy:10")
  }

  def "codesearch returns matches without packing"() {
    given:
    def hit = new CodeSearchTool.SearchHit("src/App.groovy", 10, 1, "10 | match")
    codeSearchTool.search("query", ["src"], 2, 1) >> [hit]

    when:
    def out = commands.codeSearch("query", ["src"], 2, 1, false, 0, 0)

    then:
    out.contains("src/App.groovy:10:1")
    out.contains("match")
  }
}
