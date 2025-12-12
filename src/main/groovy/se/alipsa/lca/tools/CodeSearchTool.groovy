package se.alipsa.lca.tools

import groovy.transform.Canonical
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-process code search utility that replaces the previous ripgrep dependency.
 * Traverses project paths, applies basic safety filters, and returns snippets with
 * configurable context lines. Path validation ensures queries stay within the project root.
 */
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
    long cap = limit > 0 ? limit : Long.MAX_VALUE
    List<SearchHit> hits = new ArrayList<>()
    List<Path> targets = resolveTargets(paths)
    for (Path target : targets) {
      if (cap > 0 && hits.size() >= cap) {
        break
      }
      java.util.stream.Stream<Path> stream = Files.isDirectory(target) ? Files.walk(target) : java.util.stream.Stream.of(target)
      try {
        Iterator<Path> iterator = stream.filter { Path p -> Files.isRegularFile(p) }.iterator()
        while (iterator.hasNext() && hits.size() < cap) {
          Path file = iterator.next()
          List<SearchHit> fileHits = processFile(query, ctx, file)
          if (!fileHits.isEmpty()) {
            long remaining = cap - hits.size()
            if (remaining < fileHits.size()) {
              hits.addAll(fileHits.subList(0, (int) remaining))
            } else {
              hits.addAll(fileHits)
            }
          }
        }
      } finally {
        stream.close()
      }
    }
    hits
  }

  private List<SearchHit> processFile(String query, int contextLines, Path file) {
    if (!Files.isRegularFile(file)) {
      return List.of()
    }
    if (isIgnored(file)) {
      return List.of()
    }
    long size = Files.size(file)
    // Skip extremely large files to avoid memory pressure
    if (size > 5 * 1024 * 1024) {
      return List.of()
    }
    List<SearchHit> fileHits = new ArrayList<>()
    java.util.ArrayDeque<LineEntry> previous = new java.util.ArrayDeque<>()
    AtomicInteger lineCounter = new AtomicInteger(0)
    Files.newBufferedReader(file).withCloseable { reader ->
      LineEntry current
      while ((current = nextLine(reader, lineCounter)) != null) {
        if (previous.size() == contextLines + 1) {
          previous.removeFirst()
        }
        previous.addLast(current)
        int idx = current.text.indexOf(query)
        if (idx >= 0) {
          List<LineEntry> ahead = new ArrayList<>()
          for (int i = 0; i < contextLines; i++) {
            LineEntry aheadLine = nextLine(reader, lineCounter)
            if (aheadLine == null) {
              break
            }
            ahead.add(aheadLine)
          }
          String snippet = buildSnippet(previous, ahead, contextLines)
          String relative = projectRoot.relativize(file).toString()
          fileHits.add(new SearchHit(relative, current.number, idx + 1, snippet))
          // queue ahead lines for continued scanning
          for (LineEntry entry : ahead) {
            if (previous.size() == contextLines + 1) {
              previous.removeFirst()
            }
            previous.addLast(entry)
            int aheadIdx = entry.text.indexOf(query)
            if (aheadIdx >= 0) {
              String aheadSnippet = buildSnippet(previous, List.of(), contextLines)
              fileHits.add(new SearchHit(relative, entry.number, aheadIdx + 1, aheadSnippet))
            }
          }
        }
      }
    }
    fileHits
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

  private static String buildSnippet(
    java.util.ArrayDeque<LineEntry> previous,
    List<LineEntry> ahead,
    int contextLines
  ) {
    Map<Integer, String> lineMap = new LinkedHashMap<>()
    previous.each { lineMap.put(it.number, it.text) }
    ahead.each { lineMap.put(it.number, it.text) }
    int center = previous.peekLast()?.number ?: 1
    int start = Math.max(1, center - contextLines)
    int end = center + contextLines
    StringBuilder builder = new StringBuilder()
    for (int i = start; i <= end; i++) {
      String text = lineMap.get(i)
      if (text == null) {
        continue
      }
      builder.append(String.format("%4d | %s%n", i, text.stripTrailing()))
    }
    builder.toString().stripTrailing()
  }

  private boolean isIgnored(Path file) {
    String relative = projectRoot.relativize(file).toString()
    String lower = relative.toLowerCase()
    if (lower.startsWith(".git") || lower.contains("/.git/")) {
      return true
    }
    if (lower.startsWith(".lca") || lower.contains("/.lca/")) {
      return true
    }
    if (lower.contains("/node_modules/") || lower.startsWith("node_modules")) {
      return true
    }
    if (lower.contains("/build/") || lower.contains("/target/") || lower.contains("/dist/") || lower.contains("/out/")) {
      return true
    }
    if (lower.contains("/.idea/") || lower.contains("/.vscode/") || lower.contains("/.gradle/") || lower.contains("/.m2/")) {
      return true
    }
    if (lower.endsWith(".env") || lower.endsWith(".lock") || lower.endsWith(".jar") || lower.endsWith(".class")) {
      return true
    }
    false
  }

  private static LineEntry nextLine(BufferedReader reader, AtomicInteger counter) {
    String raw = reader.readLine()
    if (raw == null) {
      return null
    }
    new LineEntry(counter.incrementAndGet(), raw)
  }

  @Canonical
  @CompileStatic
  private static class LineEntry {
    int number
    String text
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
