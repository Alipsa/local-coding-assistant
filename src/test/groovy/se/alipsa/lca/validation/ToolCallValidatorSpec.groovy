package se.alipsa.lca.validation

import se.alipsa.lca.tools.ToolCallParser.ToolCall
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class ToolCallValidatorSpec extends Specification {

  @TempDir
  Path tempDir

  ToolCallValidator validator

  def setup() {
    // Create a realistic project structure
    Files.createDirectories(tempDir.resolve("src/main/groovy/se/alipsa/lca"))
    Files.createDirectories(tempDir.resolve("src/test/groovy/se/alipsa/lca"))
    Files.writeString(tempDir.resolve("src/main/groovy/se/alipsa/lca/App.groovy"),
      "package se.alipsa.lca\nclass App {}")
    Files.writeString(tempDir.resolve("pom.xml"), "<project/>")
    validator = new ToolCallValidator(tempDir)
  }

  def "writeFile to existing directory passes"() {
    given:
    def call = new ToolCall("writeFile", [
      "src/main/groovy/se/alipsa/lca/NewClass.groovy",
      "package se.alipsa.lca\nclass NewClass {}"
    ])

    when:
    def result = validator.validate([call])

    then:
    result.safeToExecute
    result.safe.size() == 1
    result.blocked.isEmpty()
  }

  def "writeFile with deeply nested non-existent path is blocked"() {
    given:
    def call = new ToolCall("writeFile", [
      "src/main/java/com/example/cli/commands/MyCommand.java",
      "package com.example.cli.commands;\npublic class MyCommand {}"
    ])

    when:
    def result = validator.validate([call])

    then:
    !result.safeToExecute
    result.blocked.size() == 1
    result.blocked[0].reason.contains("missing directory levels")
  }

  def "writeFile creating shallow new directory warns but passes"() {
    given:
    // Only 1 missing level (utils/ doesn't exist but se/alipsa/lca/ does)
    def call = new ToolCall("writeFile", [
      "src/main/groovy/se/alipsa/lca/utils/Helper.groovy",
      "package se.alipsa.lca.utils\nclass Helper {}"
    ])

    when:
    def result = validator.validate([call])

    then:
    result.safeToExecute
    result.hasWarnings()
    result.warned.size() == 1
    result.warned[0].reason.contains("1 new directory level")
  }

  def "replace targeting non-existent file is blocked"() {
    given:
    def call = new ToolCall("replace", [
      "src/main/groovy/se/alipsa/lca/NonExistent.groovy",
      "old text",
      "new text"
    ])

    when:
    def result = validator.validate([call])

    then:
    !result.safeToExecute
    result.blocked.size() == 1
    result.blocked[0].reason.contains("non-existent file")
  }

  def "replace targeting existing file passes"() {
    given:
    def call = new ToolCall("replace", [
      "src/main/groovy/se/alipsa/lca/App.groovy",
      "class App {}",
      "class App { void run() {} }"
    ])

    when:
    def result = validator.validate([call])

    then:
    result.safeToExecute
    result.safe.size() == 1
  }

  def "package declaration mismatch is blocked"() {
    given:
    def call = new ToolCall("writeFile", [
      "src/main/groovy/se/alipsa/lca/Foo.groovy",
      "package com.example.cli\nclass Foo {}"
    ])

    when:
    def result = validator.validate([call])

    then:
    !result.safeToExecute
    result.blocked.size() == 1
    result.blocked[0].reason.contains("Package declaration")
    result.blocked[0].reason.contains("com.example.cli")
  }

  def "runCommand calls pass through without filesystem checks"() {
    given:
    def call = new ToolCall("runCommand", ["chmod +x script.sh"])

    when:
    def result = validator.validate([call])

    then:
    result.safeToExecute
    result.safe.size() == 1
  }

  def "mixed valid and invalid calls are correctly categorised"() {
    given:
    def validReplace = new ToolCall("replace", [
      "src/main/groovy/se/alipsa/lca/App.groovy", "old", "new"
    ])
    def invalidReplace = new ToolCall("replace", [
      "src/main/groovy/com/example/Missing.groovy", "old", "new"
    ])
    def validWrite = new ToolCall("writeFile", [
      "src/main/groovy/se/alipsa/lca/Valid.groovy",
      "package se.alipsa.lca\nclass Valid {}"
    ])

    when:
    def result = validator.validate([validReplace, invalidReplace, validWrite])

    then:
    !result.safeToExecute
    result.blocked.size() == 1
    result.safe.size() == 2
  }

  def "deleteFile for non-existent file warns but still passes"() {
    given:
    def call = new ToolCall("deleteFile", ["nonexistent.txt"])

    when:
    def result = validator.validate([call])

    then:
    result.safeToExecute
    result.hasWarnings()
    result.warned[0].reason.contains("does not exist")
  }

  def "detectPackageMismatch returns null when packages match"() {
    expect:
    ToolCallValidator.detectPackageMismatch(
      "src/main/groovy/se/alipsa/lca/Foo.groovy",
      "package se.alipsa.lca\nclass Foo {}"
    ) == null
  }

  def "detectPackageMismatch returns message when packages differ"() {
    when:
    String result = ToolCallValidator.detectPackageMismatch(
      "src/main/groovy/se/alipsa/lca/Foo.groovy",
      "package com.example.cli\nclass Foo {}"
    )

    then:
    result != null
    result.contains("com.example.cli")
    result.contains("se.alipsa.lca")
  }

  def "detectPackageMismatch returns null for files without package declaration"() {
    expect:
    ToolCallValidator.detectPackageMismatch(
      "scripts/run.sh",
      "#!/bin/bash\necho hello"
    ) == null
  }

  def "detectPackageMismatch returns null for non-source paths"() {
    expect:
    ToolCallValidator.detectPackageMismatch(
      "config/application.yml",
      "package se.alipsa.lca\nserver.port=8080"
    ) == null
  }

  def "empty tool call list returns safe result"() {
    when:
    def result = validator.validate([])

    then:
    result.safeToExecute
    !result.hasWarnings()
    result.safe.isEmpty()
    result.blocked.isEmpty()
  }
}
