package se.alipsa.lca.tools

import org.junit.jupiter.api.Assumptions
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.attribute.PosixFilePermission
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

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

  def "readAgents reloads when AGENTS.md changes"() {
    given:
    Path agents = tempDir.resolve("AGENTS.md")
    Files.writeString(agents, "one")
    AgentsMdProvider provider = new AgentsMdProvider(new FileEditingTool(tempDir), 0)

    expect:
    provider.readAgents() == "one"

    when:
    Files.writeString(agents, "two")
    Files.setLastModifiedTime(agents, FileTime.fromMillis(System.currentTimeMillis() + 1000))

    then:
    provider.readAgents() == "two"
  }

  def "readAgents truncates when maxChars is set"() {
    given:
    Files.writeString(tempDir.resolve("AGENTS.md"), "abcdef")
    AgentsMdProvider provider = new AgentsMdProvider(new FileEditingTool(tempDir), 4)

    expect:
    provider.readAgents() == "abcd"
  }

  def "readAgents truncates without splitting surrogate pairs"() {
    given:
    String emoji = new String(Character.toChars(0x1F600))
    Files.writeString(tempDir.resolve("AGENTS.md"), "ab${emoji}cd")
    AgentsMdProvider provider = new AgentsMdProvider(new FileEditingTool(tempDir), 3)

    expect:
    provider.readAgents() == "ab${emoji}"
  }

  def "readAgents returns null for empty file"() {
    given:
    Files.writeString(tempDir.resolve("AGENTS.md"), "  \n")
    AgentsMdProvider provider = new AgentsMdProvider(new FileEditingTool(tempDir), 0)

    expect:
    provider.readAgents() == null
    provider.appendToSystemPrompt("base") == "base"
  }

  def "readAgents returns null for unreadable file"() {
    given:
    Path agents = tempDir.resolve("AGENTS.md")
    Files.writeString(agents, "secret")
    Assumptions.assumeTrue(Files.getFileStore(agents).supportsFileAttributeView("posix"))
    Files.setPosixFilePermissions(agents, Set.of(PosixFilePermission.OWNER_WRITE))
    Assumptions.assumeTrue(!Files.isReadable(agents))
    AgentsMdProvider provider = new AgentsMdProvider(new FileEditingTool(tempDir), 0)

    expect:
    provider.readAgents() == null
  }

  def "readAgents handles IO exceptions"() {
    given:
    Path agentsDir = tempDir.resolve("AGENTS.md")
    Files.createDirectory(agentsDir)
    AgentsMdProvider provider = new AgentsMdProvider(new FileEditingTool(tempDir), 0)

    expect:
    provider.readAgents() == null
  }
}
