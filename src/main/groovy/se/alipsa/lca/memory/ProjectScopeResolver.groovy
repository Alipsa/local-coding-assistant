package se.alipsa.lca.memory

import groovy.transform.CompileStatic
import org.springframework.stereotype.Component
import se.alipsa.lca.tools.Workspace

import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves which project a directory belongs to, on top of {@link Workspace}'s "which
 * directory is current" (Workspace.baseDir is process-wide and read live on every call).
 */
@Component
@CompileStatic
class ProjectScopeResolver {

  private final Workspace workspace

  ProjectScopeResolver(Workspace workspace) {
    this.workspace = workspace
  }

  /**
   * Not cached: Workspace.baseDir is mutable at runtime (e.g. via the GUI's folder
   * chooser), so a stale cached projectId would silently misfile memories after a
   * directory change.
   */
  String currentProjectId() {
    resolve(workspace.baseDir)
  }

  /**
   * Walks dir and its parents looking for a .git entry (directory or file - a file means
   * a worktree, see `git worktree`) so that cd-ing into a subdirectory of the same repo
   * mid-session still resolves to the same project. Falls back to the canonical absolute
   * path of dir itself if no .git is found anywhere up to the filesystem root.
   */
  private static String resolve(Path dir) {
    Path current = dir.toAbsolutePath().normalize()
    Path candidate = current
    while (candidate != null) {
      if (Files.exists(candidate.resolve(".git"))) {
        return candidate.toString()
      }
      candidate = candidate.parent
    }
    current.toString()
  }
}
