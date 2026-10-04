package se.alipsa.lca

import groovy.transform.CompileStatic
import org.springframework.boot.diagnostics.FailureAnalysis
import org.springframework.boot.diagnostics.FailureAnalyzer

import java.util.regex.Matcher
import java.util.regex.Pattern

/** Gives local Ollama recovery instructions when Embabel cannot load models at startup. */
@CompileStatic
class MissingEmbeddingModelFailureAnalyzer implements FailureAnalyzer {

  private static final Pattern MISSING_MODEL = Pattern.compile(
    "(?s)^(LLM|Embedding model) '([^']+)' for role .+ is not available:.*"
  )
  private static final String NO_MODELS = 'No models detected.'

  @Override
  FailureAnalysis analyze(Throwable failure) {
    Set<Throwable> visited = new HashSet<>()
    Throwable cause = failure
    while (cause != null && visited.add(cause)) {
      String message = cause.message ?: ''
      if (message.startsWith(NO_MODELS)) {
        return new FailureAnalysis(
          'No local models were detected. Ollama may be stopped, unreachable or have no models installed.',
          'Start Ollama with ollama serve at the server configured by spring.ai.ollama.base-url ' +
            '(default http://localhost:11434).\n' +
            'Re-run the updated lca launcher (or lca gui) to install the required local models.',
          cause
        )
      }
      Matcher matcher = MISSING_MODEL.matcher(message)
      if (matcher.matches()) {
        boolean embeddingModel = matcher.group(1) == 'Embedding model'
        String model = matcher.group(2)
        String description = embeddingModel
          ? "The configured embedding model '${model}' is unavailable. " +
            "Embabel requires it at startup, even when memory is disabled.\n${message}"
          : "The configured chat model '${model}' is unavailable. Embabel requires it at startup.\n${message}"
        String action = embeddingModel
          ? "Ensure Ollama is running at the configured server and run: ollama pull ${model}\n" +
            'Then restart lca or lca gui.'
          : 'Ensure Ollama is running at the server configured by spring.ai.ollama.base-url.\n' +
            "Re-run the updated lca launcher (or lca gui) to install or create '${model}'. " +
            'For custom model configurations, install or create the configured model on that server.'
        return new FailureAnalysis(
          description,
          action,
          cause
        )
      }
      cause = cause.cause
    }
    return null
  }
}
