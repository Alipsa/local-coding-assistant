package se.alipsa.lca.memory

import se.alipsa.lca.tools.Workspace
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class ProjectScopeResolverSpec extends Specification {

  @TempDir
  Path tempDir

  def "resolves to the nearest ancestor directory containing a .git directory"() {
    given:
    Path repoRoot = tempDir.resolve("repo")
    Path subDir = repoRoot.resolve("src/main")
    Files.createDirectories(subDir)
    Files.createDirectory(repoRoot.resolve(".git"))
    Workspace workspace = Mock(Workspace) {
      getBaseDir() >> subDir
    }
    ProjectScopeResolver resolver = new ProjectScopeResolver(workspace)

    expect:
    resolver.currentProjectId() == repoRoot.toAbsolutePath().normalize().toString()
  }

  def "resolves to the nearest ancestor with a .git file (worktree)"() {
    given:
    Path repoRoot = tempDir.resolve("worktree-repo")
    Files.createDirectories(repoRoot)
    Files.writeString(repoRoot.resolve(".git"), "gitdir: /elsewhere/.git/worktrees/foo\n")
    Workspace workspace = Mock(Workspace) {
      getBaseDir() >> repoRoot
    }
    ProjectScopeResolver resolver = new ProjectScopeResolver(workspace)

    expect:
    resolver.currentProjectId() == repoRoot.toAbsolutePath().normalize().toString()
  }

  def "falls back to the directory itself when no .git is found up to the filesystem root"() {
    given:
    Path noGitDir = tempDir.resolve("no-git-here")
    Files.createDirectories(noGitDir)
    Workspace workspace = Mock(Workspace) {
      getBaseDir() >> noGitDir
    }
    ProjectScopeResolver resolver = new ProjectScopeResolver(workspace)

    expect:
    resolver.currentProjectId() == noGitDir.toAbsolutePath().normalize().toString()
  }

  def "re-reads Workspace.baseDir on every call rather than caching"() {
    given:
    Path first = tempDir.resolve("first")
    Path second = tempDir.resolve("second")
    Files.createDirectories(first)
    Files.createDirectories(second)
    Workspace workspace = Mock(Workspace) {
      getBaseDir() >>> [first, second]
    }
    ProjectScopeResolver resolver = new ProjectScopeResolver(workspace)

    when:
    String firstId = resolver.currentProjectId()
    String secondId = resolver.currentProjectId()

    then:
    firstId == first.toAbsolutePath().normalize().toString()
    secondId == second.toAbsolutePath().normalize().toString()
  }
}
