package se.alipsa.lca.tools

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class AgentsMdProviderSpec extends Specification {

  @TempDir
  Path tempDir

  def "appendToSystemPrompt returns base when AGENTS.md missing"() {
    given:
    AgentsMdProvider provider = new AgentsMdProvider(new FileEditingTool(tempDir), 0)

    expect:
    provider.appendToSystemPrompt("base") == "base"
  }

  def "appendToSystemPrompt appends agents content"() {
    given:
    Files.writeString(tempDir.resolve("AGENTS.md"), "Project rules")
    AgentsMdProvider provider = new AgentsMdProvider(new FileEditingTool(tempDir), 0)

    when:
    String prompt = provider.appendToSystemPrompt("base")

    then:
    prompt == "base\n\nAGENTS.md:\nProject rules"
  }
}
