package se.alipsa.lca.tools

import groovy.transform.Canonical
import groovy.transform.CompileStatic

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Utility for extracting code blocks from LLM responses and saving them to files.
 */
@CompileStatic
class CodeBlockExtractor {

  private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile(
    /(?s)```(\w+)?\s*\n(.*?)```/
  )

  private static final Pattern FILE_PATH_PATTERN = Pattern.compile(
    /(?m)^(?:\/\/|#|<!--|--)\s*([a-zA-Z0-9_\/\-\.]+\.(groovy|java|kt|gradle|properties|xml|json|yaml|yml|md|sh|sql))\s*(?:-->)?\s*$/
  )

  private static final Pattern SAVE_AS_PATTERN = Pattern.compile(
    /(?i)(?:save|create|write)(?:\s+(?:as|to|in|file))?\s+([a-zA-Z0-9_\/\-\.]+\.(groovy|java|kt|gradle|properties|xml|json|yaml|yml|md|sh|sql))/
  )

  /**
   * Extracts code blocks from markdown-formatted text.
   *
   * @param text The text containing code blocks
   * @return List of extracted code blocks
   */
  static List<CodeBlock> extractCodeBlocks(String text) {
    if (text == null || text.trim().isEmpty()) {
      return []
    }

    List<CodeBlock> blocks = []
    Matcher matcher = CODE_BLOCK_PATTERN.matcher(text)

    while (matcher.find()) {
      String language = matcher.group(1)?.trim()
      String code = matcher.group(2)

      if (code != null && !code.trim().isEmpty()) {
        // Try to extract file path from the code block
        String filePath = extractFilePath(code, text)
        blocks.add(new CodeBlock(language, code.trim(), filePath))
      }
    }

    blocks
  }

  /**
   * Extracts a file path from code block comments or surrounding text.
   *
   * @param code The code block content
   * @param fullText The full text for context
   * @return File path if found, null otherwise
   */
  private static String extractFilePath(String code, String fullText) {
    // First, check for file path in the first few lines of the code block
    String[] lines = code.split('\n')
    for (int i = 0; i < Math.min(5, lines.length); i++) {
      Matcher fileMatcher = FILE_PATH_PATTERN.matcher(lines[i])
      if (fileMatcher.find()) {
        return fileMatcher.group(1)
      }
    }

    // Check for "save as" pattern in the full text
    Matcher saveMatcher = SAVE_AS_PATTERN.matcher(fullText)
    if (saveMatcher.find()) {
      return saveMatcher.group(1)
    }

    null
  }

  /**
   * Saves code blocks to files in the specified directory.
   *
   * @param blocks The code blocks to save
   * @param baseDir The base directory for saving files
   * @param dryRun If true, only reports what would be saved without actually saving
   * @return Summary of save operations
   */
  static SaveResult saveCodeBlocks(List<CodeBlock> blocks, Path baseDir, boolean dryRun = false) {
    List<String> saved = []
    List<String> skipped = []

    for (CodeBlock block : blocks) {
      if (block.filePath == null) {
        skipped.add("Code block (${block.language ?: 'unknown'}) - no file path specified".toString())
        continue
      }

      try {
        Path targetPath = baseDir.resolve(block.filePath)

        // Security check: prevent path traversal
        if (!targetPath.normalize().startsWith(baseDir.normalize())) {
          skipped.add("${block.filePath} - path traversal attempt blocked".toString())
          continue
        }

        if (dryRun) {
          saved.add("${block.filePath} (${block.code.split('\n').length} lines)".toString())
        } else {
          // Create parent directories if needed
          Files.createDirectories(targetPath.parent)

          // Write the file
          Files.writeString(targetPath, block.code)
          saved.add("${block.filePath} (${block.code.split('\n').length} lines)".toString())
        }
      } catch (Exception e) {
        skipped.add("${block.filePath} - error: ${e.message}".toString())
      }
    }

    new SaveResult(saved, skipped, dryRun)
  }

  /**
   * Represents a code block extracted from text.
   */
  @Canonical
  @CompileStatic
  static class CodeBlock {
    String language
    String code
    String filePath
  }

  /**
   * Represents the result of a save operation.
   */
  @Canonical
  @CompileStatic
  static class SaveResult {
    List<String> saved
    List<String> skipped
    boolean dryRun

    String format() {
      StringBuilder sb = new StringBuilder()

      if (!saved.isEmpty()) {
        sb.append(dryRun ? "Would save:\n" : "Saved:\n")
        saved.each { sb.append("  ✓ ${it}\n") }
      }

      if (!skipped.isEmpty()) {
        sb.append("Skipped:\n")
        skipped.each { sb.append("  ✗ ${it}\n") }
      }

      if (saved.isEmpty() && skipped.isEmpty()) {
        sb.append("No code blocks found with file paths.\n")
      }

      sb.toString().trim()
    }

    boolean hasFiles() {
      !saved.isEmpty()
    }
  }
}
