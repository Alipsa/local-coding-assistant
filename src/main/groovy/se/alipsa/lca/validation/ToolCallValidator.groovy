package se.alipsa.lca.validation

import groovy.transform.Canonical
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import se.alipsa.lca.tools.ToolCallParser.ToolCall

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Validates parsed tool calls against the actual project filesystem before execution.
 * Blocks fabricated paths and targets, warns on shallow new directories.
 */
@Component
@CompileStatic
class ToolCallValidator {

  private static final Logger log = LoggerFactory.getLogger(ToolCallValidator)
  private static final int DEEP_MISSING_THRESHOLD = 3

  private final Path projectRoot

  ToolCallValidator() {
    this(Paths.get(".").toAbsolutePath())
  }

  ToolCallValidator(Path projectRoot) {
    this.projectRoot = projectRoot.toAbsolutePath().normalize()
  }

  /**
   * Validate a list of tool calls against the filesystem.
   *
   * @param toolCalls The parsed tool calls to validate
   * @return ToolCallValidationResult with blocked/warned/safe lists
   */
  ToolCallValidationResult validate(List<ToolCall> toolCalls) {
    List<BlockedCall> blocked = []
    List<WarnedCall> warned = []
    List<ToolCall> safe = []

    for (ToolCall call : toolCalls) {
      switch (call.toolName) {
        case "writeFile":
          validateWriteFile(call, blocked, warned, safe)
          break
        case "replace":
          validateReplace(call, blocked, warned, safe)
          break
        case "deleteFile":
          validateDeleteFile(call, blocked, warned, safe)
          break
        default:
          safe.add(call)
      }
    }

    new ToolCallValidationResult(blocked, warned, safe)
  }

  private void validateWriteFile(ToolCall call, List<BlockedCall> blocked,
                                 List<WarnedCall> warned, List<ToolCall> safe) {
    if (call.arguments.size() < 2) {
      blocked.add(new BlockedCall(call, "writeFile requires 2 arguments"))
      return
    }
    String filePath = call.arguments[0]
    Path target = projectRoot.resolve(filePath).normalize()

    int missingLevels = countMissingDirectoryLevels(target)
    if (missingLevels >= DEEP_MISSING_THRESHOLD) {
      blocked.add(new BlockedCall(call,
        ("Path '${filePath}' requires creating ${missingLevels} missing directory levels " +
          "- this looks like a fabricated project structure").toString()))
      return
    }

    // Check for package declaration mismatch in content
    String content = call.arguments[1]
    String packageMismatch = detectPackageMismatch(filePath, content)
    if (packageMismatch != null) {
      blocked.add(new BlockedCall(call, packageMismatch))
      return
    }

    if (missingLevels > 0) {
      warned.add(new WarnedCall(call,
        "Path '${filePath}' creates ${missingLevels} new directory level(s)".toString()))
    }

    safe.add(call)
  }

  private void validateReplace(ToolCall call, List<BlockedCall> blocked,
                               List<WarnedCall> warned, List<ToolCall> safe) {
    if (call.arguments.size() < 3) {
      blocked.add(new BlockedCall(call, "replace requires 3 arguments"))
      return
    }
    String filePath = call.arguments[0]
    Path target = projectRoot.resolve(filePath).normalize()

    if (!Files.exists(target)) {
      blocked.add(new BlockedCall(call,
        "Cannot replace in non-existent file '${filePath}'".toString()))
      return
    }

    safe.add(call)
  }

  private void validateDeleteFile(ToolCall call, List<BlockedCall> blocked,
                                  List<WarnedCall> warned, List<ToolCall> safe) {
    if (call.arguments.size() < 1) {
      blocked.add(new BlockedCall(call, "deleteFile requires 1 argument"))
      return
    }
    String filePath = call.arguments[0]
    Path target = projectRoot.resolve(filePath).normalize()

    if (!Files.exists(target)) {
      warned.add(new WarnedCall(call,
        "File '${filePath}' does not exist - delete has no effect".toString()))
    }

    safe.add(call)
  }

  private int countMissingDirectoryLevels(Path target) {
    Path parent = target.getParent()
    int missing = 0
    while (parent != null && parent.startsWith(projectRoot) && !Files.exists(parent)) {
      missing++
      parent = parent.getParent()
    }
    missing
  }

  /**
   * Detect if a writeFile's package declaration doesn't match the file path.
   * E.g. writing to src/main/groovy/se/alipsa/lca/Foo.groovy with package com.example.cli
   */
  static String detectPackageMismatch(String filePath, String content) {
    if (content == null || filePath == null) {
      return null
    }
    def matcher = content =~ /(?m)^package\s+([\w.]+)/
    if (!matcher.find()) {
      return null
    }
    String declaredPackage = matcher.group(1)
    String pathPackage = extractPackageFromPath(filePath)
    if (pathPackage != null && pathPackage != declaredPackage) {
      return ("Package declaration '${declaredPackage}' does not match file path '${filePath}' " +
        "(expected '${pathPackage}')").toString()
    }
    null
  }

  private static String extractPackageFromPath(String filePath) {
    // Match src/main/groovy/... or src/main/java/... or src/test/groovy/...
    def pathMatcher = filePath =~ /src\/(?:main|test)\/(?:groovy|java)\/(.+)\//
    if (!pathMatcher.find()) {
      return null
    }
    pathMatcher.group(1).replace('/', '.')
  }

  @Canonical
  @CompileStatic
  static class BlockedCall {
    ToolCall call
    String reason
  }

  @Canonical
  @CompileStatic
  static class WarnedCall {
    ToolCall call
    String reason
  }

  @CompileStatic
  static class ToolCallValidationResult {
    final List<BlockedCall> blocked
    final List<WarnedCall> warned
    final List<ToolCall> safe

    ToolCallValidationResult(List<BlockedCall> blocked, List<WarnedCall> warned, List<ToolCall> safe) {
      this.blocked = blocked
      this.warned = warned
      this.safe = safe
    }

    boolean isSafeToExecute() {
      blocked.isEmpty()
    }

    boolean hasWarnings() {
      !warned.isEmpty()
    }
  }
}
