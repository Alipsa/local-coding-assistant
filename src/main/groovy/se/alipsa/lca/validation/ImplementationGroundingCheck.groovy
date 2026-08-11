package se.alipsa.lca.validation

import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import se.alipsa.lca.tools.ToolCallParser.ToolCall

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.Pattern

/**
 * Analyses LLM responses to detect hallucination signals before tool calls execute.
 * Checks whether the response is grounded in the actual project codebase.
 */
@Component
@CompileStatic
class ImplementationGroundingCheck {

  private static final Logger log = LoggerFactory.getLogger(ImplementationGroundingCheck)

  // check()/scoreFileReferences scan free-form LLM prose with .find() — a wide extension list there
  // makes bare framework mentions like "Node.js" or "Vue.js" parse as file paths and produce false
  // "files don't exist" warnings. Kept narrow to JVM-language extensions, where that ambiguity
  // barely exists in practice.
  private static final Pattern FILE_REF_PATTERN = Pattern.compile(
    /\b([\w\/\-\.]+\.(?:groovy|java|xml|yaml|yml|properties|json|sh|sql|gradle|kt))\b/
  )

  // checkFileReferences matches whole strings (.matches()) against already-parsed review-finding
  // paths, not free prose, so a wide pattern is safe there. Kept in sync with
  // ShellCommands.SOURCE_EXTENSIONS (plus sh/sql, which that set omits) — the project's review
  // target is language-agnostic, so a citation with any extension the reviewer accepts must be
  // checkable here too, not just JVM-language files.
  private static final String REVIEW_FILE_EXTENSIONS =
    "groovy|java|kt|scala|py|js|ts|tsx|jsx|go|rs|rb|c|cpp|h|hpp|cs|swift|xml|gradle|" +
    "properties|yml|yaml|json|toml|md|sh|sql"

  private static final Pattern REVIEW_FILE_REF_PATTERN = Pattern.compile(
    "\\b([\\w/\\-.]+\\.(?:${REVIEW_FILE_EXTENSIONS}))\\b".toString()
  )

  private static final Pattern COM_EXAMPLE_PATTERN = Pattern.compile(
    /\bcom\.example\b/
  )

  /** Frameworks that local LLMs commonly hallucinate when not present in the project */
  private static final List<String> KNOWN_FRAMEWORK_MARKERS = [
    'picocli', 'micronaut', 'quarkus', 'ktor', 'dropwizard', 'vertx',
    'javax.ws.rs', 'jakarta.ws.rs', 'ratpack', 'spark', 'javalin',
    'com.google.inject', 'dagger', 'guice'
  ]

  private final Path projectRoot

  ImplementationGroundingCheck() {
    this(Paths.get(".").toAbsolutePath())
  }

  ImplementationGroundingCheck(Path projectRoot) {
    this.projectRoot = projectRoot.toAbsolutePath().normalize()
  }

  /**
   * Analyse the LLM response and tool calls for hallucination signals.
   *
   * @param llmResponse The full text response from the LLM
   * @param toolCalls The parsed tool calls
   * @return GroundingResult with confidence level and issues found
   */
  GroundingResult check(String llmResponse, List<ToolCall> toolCalls) {
    List<String> issues = []

    // 1. Check file references: existing vs non-existent
    FileReferenceScore fileScore = scoreFileReferences(llmResponse)
    if (fileScore.total > 0 && fileScore.existingRatio() < 0.2d) {
      issues.add("Only ${fileScore.existing}/${fileScore.total} referenced files exist in the project".toString())
    }

    // 2. Check for com.example usage
    if (COM_EXAMPLE_PATTERN.matcher(llmResponse).find() && !projectUsesComExample()) {
      issues.add("Response uses 'com.example' package but the project does not")
    }

    // 3. Check for hallucinated frameworks
    List<String> hallucinatedFrameworks = detectHallucinatedFrameworks(llmResponse)
    if (!hallucinatedFrameworks.isEmpty()) {
      issues.add("References frameworks not in the project: ${hallucinatedFrameworks.join(', ')}".toString())
    }

    // 4. Check if all tool calls create new files with zero existing references
    if (!toolCalls.isEmpty()) {
      boolean allNewFiles = toolCalls.every { ToolCall tc ->
        tc.toolName == "writeFile" || tc.toolName == "runCommand"
      }
      boolean noExistingRefs = fileScore.existing == 0
      if (allNewFiles && noExistingRefs && toolCalls.size() > 1) {
        issues.add("All tool calls create new files with no reference to existing code")
      }
    }

    GroundingLevel level = determineLevel(issues)
    log.debug("Grounding check: level={}, issues={}", level, issues.size())
    new GroundingResult(level, issues)
  }

  private FileReferenceScore scoreFileReferences(String response) {
    def matcher = FILE_REF_PATTERN.matcher(response)
    Set<String> seen = new HashSet<>()
    int existing = 0
    int nonExisting = 0

    while (matcher.find()) {
      String ref = matcher.group(1)
      if (seen.add(ref)) {
        Path resolved = projectRoot.resolve(ref).normalize()
        if (Files.exists(resolved)) {
          existing++
        } else {
          nonExisting++
        }
      }
    }

    new FileReferenceScore(existing, nonExisting)
  }

  GroundingResult checkFileReferences(List<String> citedFiles, Set<String> additionalKnownPaths) {
    List<String> issues = []
    List<String> pathShaped = citedFiles.toUnique().findAll { String ref ->
      REVIEW_FILE_REF_PATTERN.matcher(ref).matches() && !Paths.get(ref).isAbsolute()
    }
    List<String> nonExisting = pathShaped.findAll { String ref -> !fileReferenceExists(ref, additionalKnownPaths) }
    if (!nonExisting.isEmpty()) {
      issues.add("Referenced file(s) not found in the project: ${nonExisting.join(', ')}".toString())
    }
    new GroundingResult(determineLevel(issues), issues)
  }

  private boolean fileReferenceExists(String ref, Set<String> knownPaths) {
    try {
      Files.exists(projectRoot.resolve(ref).normalize()) || isKnownPath(ref, knownPaths)
    } catch (InvalidPathException ex) {
      false
    }
  }

  private static boolean isKnownPath(String ref, Set<String> knownPaths) {
    if (knownPaths.any { it == ref || it.endsWith("/" + ref) }) {
      return true
    }
    // The reverse direction (a cited path ending in the known fragment) is only safe when the known
    // path has 2+ segments: a directory review's knownPaths are parent-relative labels (e.g.
    // "shell/ShellCommands.groovy"), so a citation of the natural full repo path needs this to be
    // recognised at all. Gating on "/" in the known path stops a single-segment root-level entry
    // like "pom.xml" from letting a fabricated deep path piggyback on it.
    knownPaths.any { it.contains("/") && ref.endsWith("/" + it) }
  }

  private boolean projectUsesComExample() {
    Path comDir = projectRoot.resolve("src/main/groovy/com/example")
    Path comJavaDir = projectRoot.resolve("src/main/java/com/example")
    Files.exists(comDir) || Files.exists(comJavaDir)
  }

  private List<String> detectHallucinatedFrameworks(String response) {
    String lower = response.toLowerCase()
    Path pomPath = projectRoot.resolve("pom.xml")
    String pomContent = ""
    if (Files.exists(pomPath)) {
      pomContent = Files.readString(pomPath).toLowerCase()
    }

    List<String> hallucinated = []
    for (String framework : KNOWN_FRAMEWORK_MARKERS) {
      if (lower.contains(framework.toLowerCase()) && !pomContent.contains(framework.toLowerCase())) {
        hallucinated.add(framework)
      }
    }
    hallucinated
  }

  private static GroundingLevel determineLevel(List<String> issues) {
    if (issues.isEmpty()) {
      return GroundingLevel.GROUNDED
    }
    if (issues.size() == 1) {
      return GroundingLevel.UNCERTAIN
    }
    if (issues.size() == 2) {
      return GroundingLevel.SUSPICIOUS
    }
    GroundingLevel.UNGROUNDED
  }

  @CompileStatic
  static class FileReferenceScore {
    final int existing
    final int nonExisting
    final int total

    FileReferenceScore(int existing, int nonExisting) {
      this.existing = existing
      this.nonExisting = nonExisting
      this.total = existing + nonExisting
    }

    double existingRatio() {
      total == 0 ? 1.0d : (double) existing / total
    }
  }

  @CompileStatic
  enum GroundingLevel {
    GROUNDED,
    UNCERTAIN,
    SUSPICIOUS,
    UNGROUNDED
  }

  @CompileStatic
  static class GroundingResult {
    final GroundingLevel level
    final List<String> issues

    GroundingResult(GroundingLevel level, List<String> issues) {
      this.level = level
      this.issues = issues
    }

    boolean shouldBlock() {
      level == GroundingLevel.UNGROUNDED
    }

    boolean shouldWarn() {
      level == GroundingLevel.SUSPICIOUS || level == GroundingLevel.UNCERTAIN
    }
  }
}
