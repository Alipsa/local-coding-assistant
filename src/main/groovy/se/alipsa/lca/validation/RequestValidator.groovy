package se.alipsa.lca.validation

import groovy.transform.CompileStatic
import org.springframework.stereotype.Component
import se.alipsa.lca.intent.ContextResolver

import java.util.regex.Pattern

/**
 * Validates implementation requests for completeness and detects ambiguous requests
 * that need clarification before proceeding.
 */
@Component
@CompileStatic
class RequestValidator {

  private static final Pattern GENERIC_VERB_PATTERN = Pattern.compile(
    /^(write|create|add|make|implement|build|generate)\s+(a|an|the)\s+(\w+)/,
    Pattern.CASE_INSENSITIVE
  )

  private static final Map<String, List<String>> AMBIGUOUS_TERMS = [
    'query': ['SQL query for database', 'GraphQL query for API', 'Search query', 'Other (specify)'],
    'test': ['Unit test (Spock)', 'Integration test', 'End-to-end test', 'Other (specify)'],
    'service': ['REST service', 'Spring service component', 'Background service', 'Other (specify)'],
    'endpoint': ['REST endpoint', 'GraphQL endpoint', 'WebSocket endpoint', 'Other (specify)'],
    'script': ['Bash script', 'Groovy script', 'SQL script', 'Other (specify)'],
    'api': ['REST API', 'GraphQL API', 'gRPC API', 'Other (specify)'],
    'controller': ['Spring MVC controller', 'REST controller', 'WebSocket controller', 'Other (specify)']
  ]

  private static final List<String> TECHNOLOGY_KEYWORDS = [
    'groovy', 'java', 'sql', 'graphql', 'rest', 'spring', 'spock', 'junit',
    'bash', 'shell', 'grpc', 'websocket', 'jpa', 'hibernate', 'mongodb'
  ]

  private static final Pattern FILE_PATH_PATTERN = Pattern.compile(
    /\b[\w\/\-\.]+\.(?:groovy|java|sql|properties|yaml|yml|xml|json|sh)\b/,
    Pattern.CASE_INSENSITIVE
  )

  private final ValidationSettings settings
  private final ContextResolver contextResolver

  RequestValidator(ValidationSettings settings, ContextResolver contextResolver) {
    this.settings = settings
    this.contextResolver = contextResolver
  }

  /**
   * Validates an implementation request and determines if clarification is needed.
   *
   * @param prompt The user's implementation request
   * @param sessionId The session ID for context resolution
   * @return ValidationResult indicating if clarification is needed
   */
  ValidationResult validate(String prompt, String sessionId) {
    if (!settings.enabled) {
      return ValidationResult.ok()
    }

    if (prompt == null || prompt.trim().isEmpty()) {
      return ValidationResult.needsClarification(
        "Empty request",
        ["Please provide a description of what you want to implement."]
      )
    }

    String trimmedPrompt = prompt.trim()

    // Check for generic verb pattern first (e.g., "write a query")
    // This takes priority over length check since it's more specific
    def genericMatch = GENERIC_VERB_PATTERN.matcher(trimmedPrompt)
    if (genericMatch.find()) {
      String thing = genericMatch.group(3).toLowerCase()

      // Check if the "thing" is in our ambiguous terms and lacks context
      if (AMBIGUOUS_TERMS.containsKey(thing)) {
        // Only flag if it also lacks file context, tech context, and location hints
        if (!hasFileContext(trimmedPrompt) && !hasTechnologyContext(trimmedPrompt) && !hasLocationHints(trimmedPrompt)) {
          return ValidationResult.needsClarification(
            "Generic request needs specifics",
            buildClarifyingQuestions(thing),
            thing
          )
        }
      }

      // Even if not in our map, check if it lacks context
      if (!hasFileContext(trimmedPrompt) && !hasTechnologyContext(trimmedPrompt) && !hasLocationHints(trimmedPrompt)) {
        return ValidationResult.needsClarification(
          "Request lacks context",
          [
            "What type of ${thing} do you want to create?".toString(),
            "Where should it be placed?",
            "What technology/framework should be used?"
          ],
          thing
        )
      }
    }

    // Quick length check (after generic pattern, as generic pattern is more specific)
    if (trimmedPrompt.length() < settings.minPromptLength) {
      return ValidationResult.needsClarification(
        "Request too short",
        ["Can you provide more details about what you want to implement?"]
      )
    }

    // Check for ambiguous keywords without qualifiers
    for (Map.Entry<String, List<String>> entry : AMBIGUOUS_TERMS.entrySet()) {
      String keyword = entry.key
      if (containsKeywordWithoutQualifier(trimmedPrompt, keyword)) {
        return ValidationResult.needsClarification(
          "Ambiguous term needs specification",
          buildKeywordQuestions(keyword),
          keyword
        )
      }
    }

    // Check for missing file context
    // Only flag if we lack file context, technology context, location hints, AND context references
    if (!hasFileContext(trimmedPrompt) && !hasTechnologyContext(trimmedPrompt) && !hasLocationHints(trimmedPrompt)) {
      // Try to resolve using ContextResolver as a last resort
      if (settings.useContextResolver) {
        def resolution = contextResolver.resolve(trimmedPrompt, sessionId)
        if (!resolution.hasReferences) {
          return ValidationResult.needsClarification(
            "No target location specified",
            [
              "Where should the code be placed?",
              "Which file or package should contain this code?"
            ]
          )
        }
      } else {
        return ValidationResult.needsClarification(
          "No target location specified",
          [
            "Where should the code be placed?",
            "Which file or package should contain this code?"
          ]
        )
      }
    }

    // All checks passed
    return ValidationResult.ok()
  }

  private List<String> buildClarifyingQuestions(String thing) {
    List<String> options = AMBIGUOUS_TERMS.get(thing)
    if (options != null) {
      return options
    }
    return ["Please provide more details about the ${thing} you want to create.".toString()]
  }

  private List<String> buildKeywordQuestions(String keyword) {
    List<String> options = AMBIGUOUS_TERMS.get(keyword)
    if (options != null) {
      return options
    }
    return ["What type of ${keyword} do you need?".toString()]
  }

  private boolean containsKeywordWithoutQualifier(String prompt, String keyword) {
    String lower = prompt.toLowerCase()

    // Check if keyword is present as a whole word
    if (!lower.matches(/.*\b${keyword}\b.*/)) {
      return false
    }

    // Check if it already has qualifiers/context
    // If any technology keyword is present, assume it's qualified
    for (String tech : TECHNOLOGY_KEYWORDS) {
      if (lower.contains(tech)) {
        return false // Has qualifier
      }
    }

    // Check if it has a file path (context)
    if (hasFileContext(prompt)) {
      return false // Has context
    }

    // Keyword is present but unqualified
    return true
  }

  private boolean hasFileContext(String prompt) {
    // Check for explicit file paths
    if (FILE_PATH_PATTERN.matcher(prompt).find()) {
      return true
    }

    // Check for file/directory references
    String lower = prompt.toLowerCase()
    return lower.contains('in the file') ||
           lower.contains('in file') ||
           lower.contains('in directory') ||
           lower.contains('in package') ||
           lower.contains('in src/')
  }

  private boolean hasLocationHints(String prompt) {
    String lower = prompt.toLowerCase()
    return lower.contains('in ') ||
           lower.contains('inside ') ||
           lower.contains('within ') ||
           lower.contains('to the ') ||
           lower.contains('under ')
  }

  private boolean hasTechnologyContext(String prompt) {
    String lower = prompt.toLowerCase()
    for (String tech : TECHNOLOGY_KEYWORDS) {
      if (lower.contains(tech)) {
        return true
      }
    }
    return false
  }

}
