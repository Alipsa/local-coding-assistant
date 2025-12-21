package se.alipsa.lca.tools

import groovy.transform.CompileStatic

import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.PathMatcher

@CompileStatic
class ExclusionPolicy {

  private static final String EXCLUDE_FILE = ".aiexclude"
  private static final List<String> DEFAULT_PATTERNS = List.of(
    ".git/",
    ".lca/",
    ".idea/",
    ".vscode/",
    ".gradle/",
    ".m2/",
    ".env",
    "*.pem",
    "id_rsa",
    "id_dsa",
    "credentials.*",
    ".ssh/",
    ".gnupg/",
    "node_modules/",
    "build/",
    "target/",
    "dist/",
    "out/",
    "*.lock",
    "*.jar",
    "*.class",
    EXCLUDE_FILE
  )

  private final Path projectRoot
  private final FileSystem fileSystem
  private final List<String> patterns
  private final Map<String, PathMatcher> matcherCache = new LinkedHashMap<>()

  ExclusionPolicy(Path projectRoot) {
    this.projectRoot = projectRoot.toAbsolutePath().normalize()
    this.fileSystem = FileSystems.getDefault()
    List<String> configPatterns = readPatterns(this.projectRoot.resolve(EXCLUDE_FILE))
    List<String> combined = new ArrayList<>()
    combined.addAll(DEFAULT_PATTERNS)
    combined.addAll(configPatterns)
    this.patterns = combined
  }

  boolean isExcluded(Path path) {
    if (path == null) {
      return false
    }
    Path candidate = path.isAbsolute() ? path.normalize() : projectRoot.resolve(path).normalize()
    Path relative
    try {
      relative = projectRoot.relativize(candidate)
    } catch (IllegalArgumentException ignored) {
      return true
    }
    return isExcludedRelative(relative.toString())
  }

  boolean isExcludedRelative(String relativePath) {
    if (relativePath == null || relativePath.trim().isEmpty()) {
      return false
    }
    String normalized = normalizePath(relativePath)
    for (String pattern : patterns) {
      if (matchesPattern(normalized, pattern)) {
        return true
      }
    }
    false
  }

  private boolean matchesPattern(String normalized, String rawPattern) {
    String pattern = rawPattern?.trim()
    if (pattern == null || pattern.isEmpty() || pattern.startsWith("#")) {
      return false
    }
    boolean anchored = pattern.startsWith("/")
    if (anchored) {
      pattern = pattern.substring(1)
    }
    boolean dirOnly = pattern.endsWith("/")
    if (dirOnly) {
      pattern = pattern.substring(0, pattern.length() - 1)
    }
    if (pattern.isEmpty()) {
      return false
    }
    if (!containsWildcard(pattern) && !pattern.contains("/")) {
      String fileName = lastSegment(normalized)
      if (fileName == pattern) {
        return true
      }
      if (dirOnly && containsDirSegment(normalized, pattern, anchored)) {
        return true
      }
      return false
    }
    boolean dirMatch = dirOnly && containsDirSegment(normalized, pattern, anchored)
    String glob = toGlob(pattern, anchored)
    if (dirOnly && !glob.endsWith("/**")) {
      glob = glob + "/**"
    }
    PathMatcher matcher = matcherFor(glob)
    dirMatch || matcher.matches(Paths.get(normalized))
  }

  private boolean containsDirSegment(String normalized, String dirName, boolean anchored) {
    if (dirName.startsWith("**/")) {
      return containsDirSegment(normalized, dirName.substring(3), false)
    }
    if (containsWildcard(dirName)) {
      String glob = toGlob(dirName, anchored)
      if (!glob.endsWith("/**")) {
        glob = glob + "/**"
      }
      return matcherFor(glob).matches(Paths.get(normalized))
    }
    if (anchored) {
      return normalized == dirName || normalized.startsWith(dirName + "/")
    }
    if (normalized == dirName || normalized.startsWith(dirName + "/") || normalized.endsWith("/" + dirName)) {
      return true
    }
    return normalized.contains("/" + dirName + "/")
  }

  private String toGlob(String pattern, boolean anchored) {
    if (anchored) {
      return pattern
    }
    if (pattern.startsWith("**/")) {
      return pattern
    }
    return "**/" + pattern
  }

  private PathMatcher matcherFor(String glob) {
    PathMatcher matcher = matcherCache.get(glob)
    if (matcher != null) {
      return matcher
    }
    PathMatcher created = fileSystem.getPathMatcher("glob:" + glob)
    matcherCache.put(glob, created)
    created
  }

  private static boolean containsWildcard(String pattern) {
    pattern.contains("*") || pattern.contains("?") || pattern.contains("[")
  }

  private static String normalizePath(String path) {
    path.replace('\\', '/')
  }

  private static String lastSegment(String path) {
    int idx = path.lastIndexOf('/')
    idx >= 0 ? path.substring(idx + 1) : path
  }

  private static List<String> readPatterns(Path file) {
    if (!Files.exists(file)) {
      return List.of()
    }
    Files.readAllLines(file).collect { it?.trim() }.findAll { it }
  }
}
