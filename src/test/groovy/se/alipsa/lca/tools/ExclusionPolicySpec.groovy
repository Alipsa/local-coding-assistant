package se.alipsa.lca.tools

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class ExclusionPolicySpec extends Specification {

  @TempDir
  Path projectRoot

  def "matches anchored, wildcard, and directory-only patterns"() {
    given:
    Path exclude = projectRoot.resolve(".aiexclude")
    Files.writeString(exclude, "/docs*/\n**/test/\n/src/*/.env\n")
    ExclusionPolicy policy = new ExclusionPolicy(projectRoot)

    expect:
    policy.isExcludedRelative("docs/index.md")
    policy.isExcludedRelative("docs-archive/readme.txt")
    !policy.isExcludedRelative("nested/docs/readme.txt")

    policy.isExcludedRelative("module/test/example.txt")
    policy.isExcludedRelative("test/example.txt")
    !policy.isExcludedRelative("testing/example.txt")

    policy.isExcludedRelative("src/app/.env")
    policy.isExcludedRelative("src/lib/.env")
    !policy.isExcludedRelative("src/.envrc")
  }
}
