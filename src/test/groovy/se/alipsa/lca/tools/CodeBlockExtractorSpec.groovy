package se.alipsa.lca.tools

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class CodeBlockExtractorSpec extends Specification {

  @TempDir
  Path tempDir

  def "extracts code blocks from markdown text"() {
    given:
    String text = """
Here's a simple Groovy script:

```groovy
// HelloWorld.groovy
class HelloWorld {
  static void main(String[] args) {
    println "Hello, World!"
  }
}
```

And here's some Java code:

```java
// Main.java
public class Main {
  public static void main(String[] args) {
    System.out.println("Hello");
  }
}
```
"""

    when:
    List<CodeBlockExtractor.CodeBlock> blocks = CodeBlockExtractor.extractCodeBlocks(text)

    then:
    blocks.size() == 2
    blocks[0].language == "groovy"
    blocks[0].filePath == "HelloWorld.groovy"
    blocks[0].code.contains("class HelloWorld")

    blocks[1].language == "java"
    blocks[1].filePath == "Main.java"
    blocks[1].code.contains("public class Main")
  }

  def "extracts file path from save as pattern"() {
    given:
    String text = """
Create a Groovy script that prints hello. Save as HelloScript.groovy:

```groovy
class HelloScript {
  static void main(String[] args) {
    println "Hello"
  }
}
```
"""

    when:
    List<CodeBlockExtractor.CodeBlock> blocks = CodeBlockExtractor.extractCodeBlocks(text)

    then:
    blocks.size() == 1
    blocks[0].filePath == "HelloScript.groovy"
  }

  def "handles code blocks without file paths"() {
    given:
    String text = """
Here's some example code:

```groovy
def x = 42
println x
```
"""

    when:
    List<CodeBlockExtractor.CodeBlock> blocks = CodeBlockExtractor.extractCodeBlocks(text)

    then:
    blocks.size() == 1
    blocks[0].language == "groovy"
    blocks[0].filePath == null
    blocks[0].code.contains("def x = 42")
  }

  def "saves code blocks to files"() {
    given:
    List<CodeBlockExtractor.CodeBlock> blocks = [
      new CodeBlockExtractor.CodeBlock("groovy", "println 'Hello'", "test.groovy"),
      new CodeBlockExtractor.CodeBlock("java", "// test", "src/Test.java")
    ]

    when:
    CodeBlockExtractor.SaveResult result = CodeBlockExtractor.saveCodeBlocks(blocks, tempDir, false)

    then:
    result.saved.size() == 2
    result.skipped.isEmpty()
    Files.exists(tempDir.resolve("test.groovy"))
    Files.exists(tempDir.resolve("src/Test.java"))
    Files.readString(tempDir.resolve("test.groovy")) == "println 'Hello'"
  }

  def "dry run mode does not create files"() {
    given:
    List<CodeBlockExtractor.CodeBlock> blocks = [
      new CodeBlockExtractor.CodeBlock("groovy", "println 'Hello'", "test.groovy")
    ]

    when:
    CodeBlockExtractor.SaveResult result = CodeBlockExtractor.saveCodeBlocks(blocks, tempDir, true)

    then:
    result.dryRun
    result.saved.size() == 1
    !Files.exists(tempDir.resolve("test.groovy"))
  }

  def "blocks path traversal attacks"() {
    given:
    List<CodeBlockExtractor.CodeBlock> blocks = [
      new CodeBlockExtractor.CodeBlock("groovy", "malicious code", "../../../etc/passwd")
    ]

    when:
    CodeBlockExtractor.SaveResult result = CodeBlockExtractor.saveCodeBlocks(blocks, tempDir, false)

    then:
    result.saved.isEmpty()
    result.skipped.size() == 1
    result.skipped[0].contains("path traversal")
  }

  def "skips blocks without file paths"() {
    given:
    List<CodeBlockExtractor.CodeBlock> blocks = [
      new CodeBlockExtractor.CodeBlock("groovy", "def x = 1", null),
      new CodeBlockExtractor.CodeBlock("java", "int x = 1;", "Valid.java")
    ]

    when:
    CodeBlockExtractor.SaveResult result = CodeBlockExtractor.saveCodeBlocks(blocks, tempDir, false)

    then:
    result.saved.size() == 1
    result.skipped.size() == 1
    result.skipped[0].contains("no file path specified")
    Files.exists(tempDir.resolve("Valid.java"))
  }

  def "formats save result correctly"() {
    given:
    CodeBlockExtractor.SaveResult result = new CodeBlockExtractor.SaveResult(
      ["file1.groovy (10 lines)", "file2.java (20 lines)"],
      ["file3.groovy - error: permission denied"],
      false
    )

    when:
    String formatted = result.format()

    then:
    formatted.contains("Saved:")
    formatted.contains("✓ file1.groovy")
    formatted.contains("✓ file2.java")
    formatted.contains("Skipped:")
    formatted.contains("✗ file3.groovy")
  }

  def "extracts multiple file extensions correctly"() {
    given:
    String text = """
```groovy
// Test.groovy
class Test {}
```

```xml
<!-- pom.xml -->
<project></project>
```

```properties
# application.properties
key=value
```
"""

    when:
    List<CodeBlockExtractor.CodeBlock> blocks = CodeBlockExtractor.extractCodeBlocks(text)

    then:
    blocks.size() == 3
    blocks[0].filePath == "Test.groovy"
    blocks[1].filePath == "pom.xml"
    blocks[2].filePath == "application.properties"
  }
}
