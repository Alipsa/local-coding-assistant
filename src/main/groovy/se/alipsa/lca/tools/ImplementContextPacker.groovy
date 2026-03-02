package se.alipsa.lca.tools

import groovy.transform.Canonical
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

import java.util.regex.Matcher
import java.util.regex.Pattern

@Component
@CompileStatic
class ImplementContextPacker {

  private static final Logger log = LoggerFactory.getLogger(ImplementContextPacker)
  private static final Pattern FILE_REF_PATTERN = Pattern.compile(
    "(?:^|\\s|[\"'(,])([\\w./\\-]+\\.[a-zA-Z]{1,10})(?=[\\s\"')\",;:]|\$)"
  )
  private static final int TREE_MAX_CHARS = 1500
  private static final int TREE_DEPTH = 3
  private static final int TREE_MAX_ENTRIES = 200
  private static final int PER_FILE_MAX_CHARS = 3000

  private final TreeTool treeTool
  private final FileEditingTool fileEditingTool
  private final int maxChars

  ImplementContextPacker(
    TreeTool treeTool,
    FileEditingTool fileEditingTool,
    @Value('${implement.context.max-chars:6000}') int maxChars
  ) {
    this.treeTool = treeTool
    this.fileEditingTool = fileEditingTool
    this.maxChars = maxChars > 0 ? maxChars : 6000
  }

  List<String> extractFileReferences(String prompt) {
    if (prompt == null || prompt.trim().isEmpty()) {
      return List.of()
    }
    Set<String> refs = new LinkedHashSet<>()
    Matcher matcher = FILE_REF_PATTERN.matcher(prompt)
    while (matcher.find()) {
      String ref = matcher.group(1)
      if (ref != null && !ref.isEmpty()) {
        refs.add(ref)
      }
    }
    new ArrayList<>(refs)
  }

  PrePackedContext buildContext(String prompt) {
    StringBuilder context = new StringBuilder()
    List<String> filesRead = new ArrayList<>()
    boolean treeTruncated = false
    boolean filesTruncated = false
    int budget = maxChars

    // Build compact project tree
    try {
      TreeTool.TreeResult treeResult = treeTool.buildTree(TREE_DEPTH, true, TREE_MAX_ENTRIES)
      if (treeResult.success && treeResult.treeText != null) {
        String treeText = treeResult.treeText
        if (treeText.length() > TREE_MAX_CHARS) {
          treeText = treeText.substring(0, TREE_MAX_CHARS) + "\n... (truncated)"
          treeTruncated = true
        }
        context.append("=== PROJECT STRUCTURE ===\n")
        context.append(treeText)
        context.append("\n\n")
        budget -= context.length()
      }
    } catch (Exception e) {
      log.warn("Failed to build project tree for implement context", e)
    }

    // Read referenced files
    List<String> refs = extractFileReferences(prompt)
    if (!refs.isEmpty() && budget > 200) {
      context.append("=== REFERENCED FILES ===\n")
      for (String ref : refs) {
        if (budget <= 200) {
          filesTruncated = true
          break
        }
        try {
          String content = fileEditingTool.readFile(ref)
          if (content != null) {
            String header = "--- ${ref} ---\n"
            int available = Math.min(PER_FILE_MAX_CHARS, budget - header.length() - 50)
            if (content.length() > available) {
              content = content.substring(0, Math.max(0, available)) + "\n... (truncated)"
              filesTruncated = true
            }
            context.append(header)
            context.append(content)
            context.append("\n\n")
            budget -= header.length() + content.length() + 2
            filesRead.add(ref)
          }
        } catch (Exception e) {
          log.debug("Could not read referenced file {}: {}", ref, e.message)
          context.append("--- ${ref} --- (not found)\n\n")
        }
      }
    }

    String contextBlock = context.toString().stripTrailing()
    new PrePackedContext(contextBlock, filesRead, treeTruncated || filesTruncated)
  }

  @Canonical
  @CompileStatic
  static class PrePackedContext {
    String contextBlock
    List<String> filesRead
    boolean truncated
  }
}
