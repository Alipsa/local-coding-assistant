package se.alipsa.lca.tools

import groovy.transform.Canonical
import groovy.transform.CompileStatic
import org.springframework.stereotype.Component

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The single, mutable session base directory shared by all path-aware tools. This mirrors the
 * Claude Code / Codex model of one working directory used by every tool. The base dir can be
 * changed at runtime (e.g. via the GUI header's folder chooser); tools read {@link #getBaseDir}
 * on each operation so the change takes effect immediately.
 */
@Component
@CompileStatic
class Workspace {

  private volatile Path baseDir = Paths.get(".").toAbsolutePath().normalize()

  Path getBaseDir() {
    baseDir
  }

  /**
   * Change the base directory. Accepts absolute or relative paths (resolved against the current
   * base dir) and a leading {@code ~}. The target must be an existing directory.
   */
  ChangeResult changeBaseDir(String input) {
    if (input == null || input.trim().isEmpty()) {
      return new ChangeResult(false, baseDir, "No directory provided.")
    }
    String raw = stripQuotes(input.trim())
    Path resolved
    try {
      resolved = resolve(raw)
    } catch (Exception e) {
      return new ChangeResult(false, baseDir, "Invalid path: ${e.message}".toString())
    }
    if (!Files.exists(resolved)) {
      return new ChangeResult(false, baseDir, "No such directory: ${resolved}".toString())
    }
    if (!Files.isDirectory(resolved)) {
      return new ChangeResult(false, baseDir, "Not a directory: ${resolved}".toString())
    }
    baseDir = resolved
    new ChangeResult(true, resolved, "Base dir changed to ${resolved}".toString())
  }

  private Path resolve(String raw) {
    String expanded = raw
    if (expanded == "~") {
      expanded = System.getProperty("user.home")
    } else if (expanded.startsWith("~/")) {
      expanded = System.getProperty("user.home") + expanded.substring(1)
    }
    Path candidate = Paths.get(expanded)
    Path absolute = candidate.isAbsolute() ? candidate : baseDir.resolve(candidate)
    absolute.toAbsolutePath().normalize()
  }

  private static String stripQuotes(String value) {
    if (value.length() >= 2
      && ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'")))) {
      return value.substring(1, value.length() - 1)
    }
    value
  }

  @Canonical
  @CompileStatic
  static class ChangeResult {
    boolean success
    Path baseDir
    String message
  }
}
