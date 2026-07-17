package se.alipsa.lca.tools

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * Package-scope internals of {@link FileEditingTool} (see {@code FileEditingSpec} in
 * {@code se.alipsa.lca.agent} for its public-facing behaviour).
 */
class FileEditingToolSpec extends Specification {

  @TempDir
  Path tempDir

  def "getRealProjectRoot falls back to the uncanonicalised root when it can't be resolved"() {
    given:
    Path missing = tempDir.resolve("does-not-exist")
    FileEditingTool tool = new FileEditingTool(missing)

    expect:
    tool.getRealProjectRoot() == missing
  }

  def "getRealProjectRoot canonicalises and caches an existing root"() {
    given:
    FileEditingTool tool = new FileEditingTool(tempDir)

    expect:
    tool.getRealProjectRoot() == tempDir.toRealPath()
    tool.getRealProjectRoot() == tool.getRealProjectRoot()
  }
}
