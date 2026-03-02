package se.alipsa.lca.validation

import se.alipsa.lca.tools.ToolCallParser.ToolCall
import se.alipsa.lca.validation.ImplementationGroundingCheck.GroundingLevel
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class ImplementationGroundingCheckSpec extends Specification {

  @TempDir
  Path tempDir

  ImplementationGroundingCheck checker

  def setup() {
    // Create a realistic project structure
    Files.createDirectories(tempDir.resolve("src/main/groovy/se/alipsa/lca/shell"))
    Files.createDirectories(tempDir.resolve("src/main/groovy/se/alipsa/lca/tools"))
    Files.writeString(tempDir.resolve("src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy"),
      "package se.alipsa.lca.shell\nclass ShellCommands {}")
    Files.writeString(tempDir.resolve("src/main/groovy/se/alipsa/lca/tools/ToolCallParser.groovy"),
      "package se.alipsa.lca.tools\nclass ToolCallParser {}")
    Files.writeString(tempDir.resolve("pom.xml"), """<project>
  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>com.embabel.agent</groupId>
      <artifactId>embabel-agent-starter</artifactId>
    </dependency>
  </dependencies>
</project>""")
    checker = new ImplementationGroundingCheck(tempDir)
  }

  def "grounded response referencing existing files passes"() {
    given:
    String response = "I'll modify src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy to add the feature."
    def calls = [
      new ToolCall("replace", [
        "src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy", "old", "new"
      ])
    ]

    when:
    def result = checker.check(response, calls)

    then:
    result.level == GroundingLevel.GROUNDED
    !result.shouldBlock()
    !result.shouldWarn()
  }

  def "response with com.example when project does not use it is flagged"() {
    given:
    String response = "I'll create com.example.cli.MyApplication to handle the task."
    def calls = [
      new ToolCall("writeFile", [
        "src/main/java/com/example/cli/MyApplication.java",
        "package com.example.cli;\npublic class MyApplication {}"
      ])
    ]

    when:
    def result = checker.check(response, calls)

    then:
    result.shouldWarn() || result.shouldBlock()
    result.issues.any { it.contains("com.example") }
  }

  def "response referencing hallucinated framework is flagged"() {
    given:
    String response = "I'll use picocli to implement the CLI command:\n" +
      "writeFile('src/main/java/MyCommand.java', 'import picocli.CommandLine;')"
    def calls = [
      new ToolCall("writeFile", ["src/main/java/MyCommand.java", "import picocli.CommandLine;"])
    ]

    when:
    def result = checker.check(response, calls)

    then:
    result.shouldWarn() || result.shouldBlock()
    result.issues.any { it.contains("picocli") }
  }

  def "response referencing actual project framework is not flagged"() {
    given:
    String response = "I'll use spring-boot-starter-web to add the REST endpoint."
    def calls = [
      new ToolCall("replace", [
        "src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy", "old", "new"
      ])
    ]

    when:
    def result = checker.check(response, calls)

    then:
    result.level == GroundingLevel.GROUNDED
  }

  def "all new files with no existing references is flagged"() {
    given:
    String response = "I'll create the application with the following structure."
    def calls = [
      new ToolCall("writeFile", ["src/main/java/Foo.java", "class Foo {}"]),
      new ToolCall("writeFile", ["src/main/java/Bar.java", "class Bar {}"])
    ]

    when:
    def result = checker.check(response, calls)

    then:
    result.shouldWarn() || result.shouldBlock()
    result.issues.any { it.contains("no reference to existing code") }
  }

  def "single writeFile without other issues is grounded"() {
    given:
    String response = "Creating a new utility class."
    def calls = [
      new ToolCall("writeFile", ["src/main/groovy/se/alipsa/lca/NewUtil.groovy", "class NewUtil {}"])
    ]

    when:
    def result = checker.check(response, calls)

    then:
    result.level == GroundingLevel.GROUNDED
  }

  def "ungrounded response with multiple issues blocks execution"() {
    given:
    String response = """I'll use picocli and micronaut to create the com.example.cli application.
Creating src/main/java/com/example/cli/App.java and src/main/java/com/example/cli/Commands.java."""
    def calls = [
      new ToolCall("writeFile", [
        "src/main/java/com/example/cli/App.java",
        "package com.example.cli;\nimport picocli.CommandLine;"
      ]),
      new ToolCall("writeFile", [
        "src/main/java/com/example/cli/Commands.java",
        "package com.example.cli;\nimport io.micronaut.context.annotation.Bean;"
      ])
    ]

    when:
    def result = checker.check(response, calls)

    then:
    result.shouldBlock()
    result.level == GroundingLevel.UNGROUNDED
    result.issues.size() >= 3
  }

  def "empty response with no tool calls is grounded"() {
    when:
    def result = checker.check("", [])

    then:
    result.level == GroundingLevel.GROUNDED
    !result.shouldBlock()
  }

  def "response with only non-existent file references is flagged"() {
    given:
    String response = "I found src/main/java/com/fake/One.java, src/main/java/com/fake/Two.java, " +
      "and src/main/java/com/fake/Three.java in the project."
    def calls = [
      new ToolCall("replace", [
        "src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy", "old", "new"
      ])
    ]

    when:
    def result = checker.check(response, calls)

    then:
    result.issues.any { it.contains("referenced files exist") }
  }

  def "mixed existing and non-existing references above threshold passes"() {
    given:
    // 2 existing + 1 non-existing = 66% existing, above 20% threshold
    String response = "I'll modify src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy " +
      "and src/main/groovy/se/alipsa/lca/tools/ToolCallParser.groovy, " +
      "plus create src/main/groovy/se/alipsa/lca/NewFile.groovy."
    def calls = []

    when:
    def result = checker.check(response, calls)

    then:
    !result.issues.any { it.contains("referenced files exist") }
  }
}
