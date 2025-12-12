package se.alipsa.lca.tools

import groovy.transform.Canonical
import groovy.transform.CompileStatic
import groovy.io.FileType
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files

@Component
@CompileStatic
class CodeSearchTool {

  private static final Logger log = LoggerFactory.getLogger(CodeSearchTool)

  private final Path projectRoot

  CodeSearchTool() {
    this(Paths.get(".").toAbsolutePath().normalize())
  }

  CodeSearchTool(Path projectRoot) {
    this.projectRoot = projectRoot.toAbsolutePath().normalize()
  }

  List<SearchHit> search(String query, List<String> paths, int contextLines, int limit) {
    if (query == null || query.trim().isEmpty()) {
      return List.of()
    }
    if (containsUnsafe(query)) {
      throw new IllegalArgumentException("Query contains unsupported characters")
    }
    int ctx = contextLines < 0 ? 0 : contextLines
    List<SearchHit> hits = new ArrayList<>()
    List<Path> targets = resolveTargets(paths)
    for (Path target : targets) {
      if (Files.isDirectory(target)) {
        target.toFile().eachFileRecurse(FileType.FILES) { File f ->
          if (limit > 0 && hits.size() >= limit) {
            return
          }
          processFile(query, ctx, limit, hits, f.toPath())
        }
      } else {
        processFile(query, ctx, limit, hits, target)
      }
      if (limit > 0 && hits.size() >= limit) {
        break
      }
    }
    if (limit > 0 && hits.size() > limit) {
      return hits.subList(0, limit)
    }
    hits
  }

  private void processFile(String query, int contextLines, int limit, List<SearchHit> hits, Path file) {
    if (!Files.isRegularFile(file)) {
      return
    }
    if (isIgnored(file)) {
      return
    }
    long size = Files.size(file)
    // Skip extremely large files to avoid memory pressure
    if (size > 5 * 1024 * 1024) {
      return
    }
    List<String> lines = Files.readAllLines(file)
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i)
      int idx = line.indexOf(query)
      if (idx >= 0) {
        int lineNumber = i + 1
        String snippet = buildSnippetFromLines(lines, lineNumber, contextLines)
        String relative = projectRoot.relativize(file).toString()
        hits.add(new SearchHit(relative, lineNumber, idx + 1, snippet))
        if (limit > 0 && hits.size() >= limit) {
          return
        }
      }
    }
  }

  private List<Path> resolveTargets(List<String> paths) {
    List<Path> targets = new ArrayList<>()
    if (paths != null) {
      paths.findAll { it != null && !it.trim().isEmpty() }
        .each { String p ->
          String validated = validatePath(p.trim())
          Path resolved = projectRoot.resolve(validated).normalize()
          if (Files.exists(resolved)) {
            targets.add(resolved)
          }
        }
    }
    if (targets.isEmpty()) {
      targets.add(projectRoot)
    }
    targets
  }

  private static String buildSnippetFromLines(List<String> lines, int lineNumber, int contextLines) {
    StringBuilder builder = new StringBuilder()
    int start = Math.max(1, lineNumber - contextLines)
    int end = lineNumber + contextLines
    for (int i = start; i <= end; i++) {
      if (i < 1 || i > lines.size()) {
        continue
      }
      String text = lines.get(i - 1)
      builder.append(String.format("%4d | %s%n", i, text.stripTrailing()))
    }
    builder.toString().stripTrailing()
  }

  private boolean isIgnored(Path file) {
    String relative = projectRoot.relativize(file).toString()
    if (relative.startsWith(".git") || relative.contains("/.git/")) {
      return true
    }
    if (relative.startsWith(".lca") || relative.contains("/.lca/")) {
      return true
    }
    String lower = relative.toLowerCase()
    return lower.endsWith(".env") || lower.contains("/.idea/") || lower.contains("/build/") || lower.contains("/target/")
  }

  private boolean containsUnsafe(String query) {
    String[] forbidden = ['|', '`', ';', '&', '$', '(', ')', '\n', '\r']
    forbidden.any { query.contains(it) }
  }

  private String validatePath(String path) {
    Path resolved = projectRoot.resolve(path).normalize()
    if (!resolved.startsWith(projectRoot)) {
      throw new IllegalArgumentException("Path must be within project root: " + path)
    }
    path
  }

  @Canonical
  @CompileStatic
  static class SearchHit {
    String path
    int line
    int column
    String snippet
  }
}
