package se.alipsa.lca.tools

import groovy.transform.Canonical
import groovy.transform.CompileStatic
import org.springframework.stereotype.Component

import java.nio.file.Path
import java.nio.file.Paths

@Component
@CompileStatic
class TreeTool {

  private final GitTool gitTool

  TreeTool() {
    this(Paths.get(".").toAbsolutePath().normalize(), new GitTool())
  }

  TreeTool(Path projectRoot, GitTool gitTool) {
    Path root = projectRoot != null ? projectRoot.toAbsolutePath().normalize() : Paths.get(".").toAbsolutePath().normalize()
    this.gitTool = gitTool != null ? gitTool : new GitTool(root)
  }

  TreeResult buildTree(int maxDepth, boolean directoriesOnly, int maxEntries) {
    int depthLimit = maxDepth < -1 ? -1 : maxDepth
    int entryLimit = Math.max(0, maxEntries)
    if (!gitTool.isGitRepo()) {
      return new TreeResult(false, false, false, 0, "", "Not a git repository.")
    }
    GitTool.GitResult result = gitTool.listFiles()
    if (!result.success) {
      String message = result.error ?: result.output ?: "Failed to list files."
      return new TreeResult(false, result.repoPresent, false, 0, "", message)
    }
    List<String> files = result.output?.readLines()?.findAll { it != null && !it.trim().isEmpty() } ?: List.of()
    TreeNode root = new TreeNode(".", true)
    boolean truncatedBuild = false
    int added = 0
    for (String path : files) {
      if (entryLimit > 0 && added >= entryLimit) {
        truncatedBuild = true
        break
      }
      TreeNode current = root
      String[] parts = path.split("/")
      int stop = directoriesOnly ? Math.max(0, parts.length - 1) : parts.length
      for (int i = 0; i < stop; i++) {
        String name = parts[i]
        boolean isDir = i < parts.length - 1 || directoriesOnly
        TreeNode child = current.children.get(name)
        if (child == null) {
          child = new TreeNode(name, isDir)
          current.children.put(name, child)
          added++
          if (entryLimit > 0 && added >= entryLimit) {
            truncatedBuild = true
            break
          }
        }
        current = child
      }
      if (truncatedBuild) {
        break
      }
    }
    StringBuilder output = new StringBuilder()
    int[] rendered = new int[] { 0 }
    renderTree(root, output, 0, depthLimit, entryLimit, rendered)
    boolean truncatedRender = entryLimit > 0 && rendered[0] >= entryLimit
    boolean truncated = truncatedBuild || truncatedRender
    String text = output.toString().stripTrailing()
    new TreeResult(true, true, truncated, rendered[0], text, null)
  }

  private static void renderTree(
    TreeNode node,
    StringBuilder output,
    int depth,
    int maxDepth,
    int maxEntries,
    int[] rendered
  ) {
    if (depth == 0) {
      output.append(node.name).append("\n")
    }
    if (maxDepth >= 0 && depth >= maxDepth) {
      return
    }
    List<TreeNode> children = new ArrayList<>(node.children.values())
    children.sort { TreeNode a, TreeNode b ->
      if (a.directory != b.directory) {
        return a.directory ? -1 : 1
      }
      a.name <=> b.name
    }
    for (TreeNode child : children) {
      if (maxEntries > 0 && rendered[0] >= maxEntries) {
        return
      }
      String indent = "  " * (depth + 1)
      output.append(indent).append(child.name)
      if (child.directory) {
        output.append("/")
      }
      output.append("\n")
      rendered[0]++
      if (child.directory) {
        renderTree(child, output, depth + 1, maxDepth, maxEntries, rendered)
      }
    }
  }

  @CompileStatic
  private static class TreeNode {
    final String name
    final boolean directory
    final Map<String, TreeNode> children = new LinkedHashMap<>()

    TreeNode(String name, boolean directory) {
      this.name = name
      this.directory = directory
    }
  }

  @Canonical
  @CompileStatic
  static class TreeResult {
    boolean success
    boolean repoPresent
    boolean truncated
    int entryCount
    String treeText
    String message
  }
}
