package se.alipsa.lca.tools

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import groovy.transform.Canonical
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

import java.nio.file.Path
import java.nio.file.Paths

@Component
@CompileStatic
class CodeSearchTool {

  private static final Logger log = LoggerFactory.getLogger(CodeSearchTool)
  private static final ObjectMapper MAPPER = new ObjectMapper()
    .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)

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
    int ctx = contextLines < 0 ? 0 : contextLines
    List<String> command = new ArrayList<>()
    command.addAll(List.of("rg", "--json", "--no-heading", "--line-number", "--column", "--context", String.valueOf(ctx)))
    if (limit > 0) {
      command.addAll(List.of("--max-count", String.valueOf(limit)))
    }
    command.add(query)
    if (paths != null) {
      command.addAll(paths.findAll { it != null && !it.trim().isEmpty() }.collect { it.trim() })
    }
    ProcessBuilder pb = new ProcessBuilder(command)
    pb.directory(projectRoot.toFile())
    pb.redirectErrorStream(true)
    List<SearchHit> hits = new ArrayList<>()
    Map<String, Map<Integer, String>> contextByPath = new HashMap<>()
    try {
      Process process = pb.start()
      process.getInputStream().readLines().each { String line ->
        if (!line.trim()) {
          return
        }
        Map<String, Object> event = (Map<String, Object>) MAPPER.readValue(line, Map)
        String type = (String) event.get("type")
        if ("context" == type) {
          Map data = (Map) event.get("data")
          String path = ((Map) data.get("path"))?.get("text") as String
          Integer lineNumber = (Integer) data.get("line_number")
          String text = ((Map) data.get("lines"))?.get("text") as String
          if (path != null && lineNumber != null && text != null) {
            contextByPath.computeIfAbsent(path) { new HashMap<>() }.put(lineNumber, text)
          }
        } else if ("match" == type) {
          Map data = (Map) event.get("data")
          String path = ((Map) data.get("path"))?.get("text") as String
          Integer lineNumber = (Integer) data.get("line_number")
          String text = ((Map) data.get("lines"))?.get("text") as String
          List submatches = (List) data.get("submatches")
          Integer column = submatches && submatches[0] != null ? ((Map) submatches[0]).get("start") as Integer : 0
          String snippet = buildSnippet(contextByPath.getOrDefault(path, Map.of()), lineNumber, text, ctx)
          if (path != null && lineNumber != null && text != null) {
            hits.add(new SearchHit(path, lineNumber, column != null ? column + 1 : 1, snippet))
          }
        }
      }
      int exit = process.waitFor()
      if (exit != 0 && hits.isEmpty()) {
        log.warn("ripgrep exited with code {}", exit)
      }
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt()
      }
      log.error("Failed to run ripgrep search", e)
      return List.of()
    }
    if (limit > 0 && hits.size() > limit) {
      return hits.subList(0, limit)
    }
    hits
  }

  private static String buildSnippet(Map<Integer, String> context, int lineNumber, String matchLine, int contextLines) {
    StringBuilder builder = new StringBuilder()
    int start = Math.max(1, lineNumber - contextLines)
    int end = lineNumber + contextLines
    for (int i = start; i <= end; i++) {
      String text = (i == lineNumber) ? matchLine : context.get(i)
      if (text == null) {
        continue
      }
      builder.append(String.format("%4d | %s%n", i, text.stripTrailing()))
    }
    builder.toString().stripTrailing()
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
