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

  def "checkFileReferences flags a citation for a file that does not exist"() {
    when:
    def result = checker.checkFileReferences(["Missing.groovy"], [] as Set)

    then:
    result.level == GroundingLevel.UNCERTAIN
    result.issues[0].contains("Missing.groovy")
  }

  def "checkFileReferences passes when all citations exist"() {
    when:
    def result = checker.checkFileReferences(
      ["src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy"], [] as Set
    )

    then:
    result.level == GroundingLevel.GROUNDED
  }

  def "checkFileReferences matches a bare filename against a known-paths entry"() {
    when:
    def result = checker.checkFileReferences(
      ["Foo.groovy"], ["src/main/groovy/se/alipsa/lca/newpkg/Foo.groovy"] as Set
    )

    then:
    result.level == GroundingLevel.GROUNDED
  }

  def "checkFileReferences flags a fabricated citation with a non-JVM extension the reviewer also accepts"() {
    when: "the project is language-agnostic — ShellCommands.SOURCE_EXTENSIONS accepts .py/.md for review"
    def result = checker.checkFileReferences(["missing_module.py", "missing_notes.md"], [] as Set)

    then:
    result.level == GroundingLevel.UNCERTAIN
    result.issues[0].contains("missing_module.py")
    result.issues[0].contains("missing_notes.md")
  }

  def "checkFileReferences flags a fabricated deep path even though it ends with a real short known path"() {
    when: "knownPaths holds a short root-level entry, as PR reviews' changedFiles routinely do"
    def result = checker.checkFileReferences(
      ["src/does/not/exist/pom.xml"], ["pom.xml"] as Set
    )

    then: "reverse-suffix matching must not let a hallucinated path piggyback on an unrelated real filename"
    result.level == GroundingLevel.UNCERTAIN
    result.issues[0].contains("src/does/not/exist/pom.xml")
  }

  def "checkFileReferences flags one fabricated citation among several real ones, not gated by a ratio"() {
    when:
    def result = checker.checkFileReferences(
      [
        "src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy",
        "src/main/groovy/se/alipsa/lca/tools/ToolCallParser.groovy",
        "Fabricated.groovy"
      ],
      [] as Set
    )

    then: "unlike check()'s existingRatio() < 0.2 gate, any non-existing citation is flagged regardless of ratio"
    result.level == GroundingLevel.UNCERTAIN
    result.issues[0].contains("Fabricated.groovy")
    !result.issues[0].contains("ShellCommands.groovy")
  }

  def "checkFileReferences names a duplicate nonexistent citation once, not per occurrence"() {
    when:
    def result = checker.checkFileReferences(["Missing.groovy", "Missing.groovy"], [] as Set)

    then:
    result.issues.size() == 1
  }

  def "checkFileReferences ignores a prose citation that isn't shaped like a file path"() {
    when:
    def result = checker.checkFileReferences(["The error handling in review()"], [] as Set)

    then:
    result.level == GroundingLevel.GROUNDED
    result.issues.isEmpty()
  }

  def "checkFileReferences excludes an extension-less absolute path"() {
    when:
    def result = checker.checkFileReferences(["/etc/passwd"], [] as Set)

    then:
    result.level == GroundingLevel.GROUNDED
  }

  def "checkFileReferences excludes an absolute path with a recognised extension even if it exists on disk"() {
    given:
    Path absoluteFile = tempDir.resolve("Foo.groovy")
    Files.writeString(absoluteFile, "class Foo {}")

    when:
    def result = checker.checkFileReferences([absoluteFile.toString()], [] as Set)

    then: "excluded because it's absolute, not treated as found or as missing"
    result.level == GroundingLevel.GROUNDED
  }

  def "checkFileReferences excludes a non-existing absolute path with a recognised extension"() {
    given:
    String nonExistentAbsolute = tempDir.resolve("does-not-exist/Fabricated.groovy").toString()

    when:
    def result = checker.checkFileReferences([nonExistentAbsolute], [] as Set)

    // If isAbsolute() were removed, this would resolve to a real path that doesn't exist
    // and would be reported as missing (UNCERTAIN) instead.
    then: "GROUNDED because it's excluded as absolute, not because Files.exists happens to find it"
    result.level == GroundingLevel.GROUNDED
  }

  def "check(llmResponse, toolCalls) is entirely unaffected by the new method"() {
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
  }
}
