package se.alipsa.lca.shell

import se.alipsa.lca.agent.CodingAssistantAgent
import se.alipsa.lca.agent.CodingAssistantAgent.CodeSnippet
import se.alipsa.lca.agent.PersonaMode
import se.alipsa.lca.tools.CodeSearchTool
import se.alipsa.lca.tools.ContextBudgetManager
import se.alipsa.lca.tools.ContextPacker
import se.alipsa.lca.tools.CommandRunner
import se.alipsa.lca.tools.CommandPolicy
import se.alipsa.lca.tools.ModelRegistry
import se.alipsa.lca.tools.FileEditingTool
import se.alipsa.lca.tools.TokenEstimator
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

class CodeSearchCommandSpec extends Specification {

  SessionState sessionState = new SessionState("default-model", 0.7d, 0.35d, 0, "", true, false, "fallback")
  CodingAssistantAgent agent = Mock()
  FileEditingTool fileEditingTool = Stub()
  CodeSearchTool codeSearchTool = Mock()
  ContextPacker contextPacker = new ContextPacker()
  ContextBudgetManager budgetManager = new ContextBudgetManager(1000, 500, new TokenEstimator())
  EditorLauncher editorLauncher = Stub() {
    edit(_) >> ""
  }
  CommandRunner commandRunner = Stub()
  CommandPolicy commandPolicy = new CommandPolicy("", "")
  ModelRegistry modelRegistry = Stub() {
    listModels() >> List.of()
    checkHealth() >> new ModelRegistry.Health(true, "ok")
  }
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
      codeSearchTool,
      contextPacker,
      budgetManager,
      commandRunner,
      commandPolicy,
      modelRegistry,
      tempDir.resolve("reviews.log").toString()
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
