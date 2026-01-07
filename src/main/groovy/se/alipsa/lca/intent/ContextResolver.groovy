package se.alipsa.lca.intent

import groovy.transform.CompileStatic
import org.springframework.stereotype.Component
import se.alipsa.lca.shell.SessionState

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Resolves conversational references like "that file", "it", "the controller" to actual file paths
 * based on recent context from the session.
 */
@Component
@CompileStatic
class ContextResolver {

  private static final Pattern PRONOUN_PATTERN = Pattern.compile(
    /\b(?:that|this|the|it)\s+(?:file|class|controller|service|component|test|spec)\b/,
    Pattern.CASE_INSENSITIVE
  )

  private static final Pattern IT_PATTERN = Pattern.compile(
    /\bit\b/,
    Pattern.CASE_INSENSITIVE
  )

  private final SessionState sessionState

  ContextResolver(SessionState sessionState) {
    this.sessionState = sessionState
  }

  /**
   * Attempts to resolve file references in the input text to actual paths.
   *
   * @param input The user input text
   * @param sessionId The session ID for context lookup
   * @return A resolution result containing detected references and resolved paths
   */
  ResolutionResult resolve(String input, String sessionId) {
    if (input == null || input.trim().isEmpty()) {
      return new ResolutionResult(input, null, false)
    }

    // Check if input contains pronouns or references
    boolean hasPronouns = containsPronouns(input)
    if (!hasPronouns) {
      return new ResolutionResult(input, null, false)
    }

    // Get recent file paths from session
    List<String> recentPaths = sessionState.getRecentFilePaths(sessionId, 5)
    if (recentPaths.isEmpty()) {
      return new ResolutionResult(input, null, false)
    }

    // First try to match by file type (more specific)
    String matchedPath = matchByFileType(input, recentPaths)
    if (matchedPath != null) {
      return new ResolutionResult(input, List.of(matchedPath), true)
    }

    // For simple cases like "review it" or "check that file", return the most recent path
    if (isSimpleReference(input)) {
      String mostRecent = recentPaths.get(0)
      return new ResolutionResult(input, List.of(mostRecent), true)
    }

    // Default: return most recent file if pronouns detected
    return new ResolutionResult(input, List.of(recentPaths.get(0)), true)
  }

  private boolean containsPronouns(String input) {
    String lower = input.toLowerCase()
    if (PRONOUN_PATTERN.matcher(lower).find()) {
      return true
    }
    if (IT_PATTERN.matcher(lower).find()) {
      return true
    }
    false
  }

  private boolean isSimpleReference(String input) {
    String trimmed = input.trim().toLowerCase()
    // Patterns like: "review it", "check that file", "show me the class"
    List<String> simplePatterns = [
      /^(?:review|check|show|edit|revert|fix|update)\s+(?:it|that|this|the\s+(?:file|class|controller|component))$/,
      /^(?:it|that|this|the\s+(?:file|class|controller))$/
    ]
    simplePatterns.any { String pattern ->
      trimmed.matches(pattern)
    }
  }

  private String matchByFileType(String input, List<String> recentPaths) {
    String lower = input.toLowerCase()

    // Extract file type hints from input
    Map<String, List<String>> typePatterns = [
      'controller': ['Controller'],
      'service': ['Service'],
      'component': ['Component'],
      'test': ['Test', 'Spec'],
      'spec': ['Spec'],
      'repository': ['Repository'],
      'config': ['Config', 'Configuration']
    ]

    for (Map.Entry<String, List<String>> entry : typePatterns.entrySet()) {
      String type = entry.key
      if (lower.contains(type)) {
        // Find first recent path that matches this type
        for (String path : recentPaths) {
          for (String pattern : entry.value) {
            if (path.contains(pattern)) {
              return path
            }
          }
        }
      }
    }

    null
  }

  /**
   * Result of context resolution.
   */
  @CompileStatic
  static class ResolutionResult {
    final String originalInput
    final List<String> resolvedPaths
    final boolean hasReferences

    ResolutionResult(String originalInput, List<String> resolvedPaths, boolean hasReferences) {
      this.originalInput = originalInput
      this.resolvedPaths = resolvedPaths ?: List.of()
      this.hasReferences = hasReferences
    }
  }
}
