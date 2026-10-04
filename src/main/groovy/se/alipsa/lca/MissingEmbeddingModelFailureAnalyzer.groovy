package se.alipsa.lca

import groovy.transform.CompileStatic
import org.springframework.boot.diagnostics.FailureAnalysis
import org.springframework.boot.diagnostics.FailureAnalyzer

import java.util.regex.Matcher
import java.util.regex.Pattern

/** Gives recovery instructions when Embabel rejects a configured embedding model at startup. */
@CompileStatic
class MissingEmbeddingModelFailureAnalyzer implements FailureAnalyzer {

  private static final Pattern MISSING_MODEL = Pattern.compile(
    "(?s)^Embedding model '([^']+)' for role .+ is not available:.*"
  )

  @Override
  FailureAnalysis analyze(Throwable failure) {
    Set<Throwable> visited = new HashSet<>()
    Throwable cause = failure
    while (cause != null && visited.add(cause)) {
      Matcher matcher = MISSING_MODEL.matcher(cause.message ?: '')
      if (matcher.matches()) {
        String model = matcher.group(1)
        return new FailureAnalysis(
          "The configured embedding model '${model}' is unavailable. " +
            "Embabel requires it at startup, even when memory is disabled.\n${cause.message}",
          "Ensure Ollama is running at the configured server and run: ollama pull ${model}\n" +
            "Then restart lca or lca gui.",
          cause
        )
      }
      cause = cause.cause
    }
    return null
  }
}
