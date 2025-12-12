package se.alipsa.lca.tools

import groovy.transform.Canonical
import groovy.transform.CompileStatic
import org.springframework.stereotype.Component

@Component
@CompileStatic
class ContextPacker {

  PackedContext pack(List<CodeSearchTool.SearchHit> hits, int maxChars) {
    if (hits == null || hits.isEmpty()) {
      return new PackedContext("", List.of(), false)
    }
    int budget = maxChars > 0 ? maxChars : 8000
    StringBuilder builder = new StringBuilder()
    List<CodeSearchTool.SearchHit> included = new ArrayList<>()
    Set<String> seen = new LinkedHashSet<>()
    for (CodeSearchTool.SearchHit hit : hits) {
      String key = "${hit.path}:${hit.line}"
      if (seen.contains(key)) {
        continue
      }
      String block = """
File: ${hit.path}:${hit.line}
${hit.snippet}
""".stripIndent().trim() + "\n\n"
      if (builder.length() + block.length() > budget) {
        return new PackedContext(builder.toString().stripTrailing(), included, true)
      }
      builder.append(block)
      included.add(hit)
      seen.add(key)
    }
    new PackedContext(builder.toString().stripTrailing(), included, false)
  }
}

@Canonical
@CompileStatic
class PackedContext {
  String text
  List<CodeSearchTool.SearchHit> included
  boolean truncated
}
