package se.alipsa.lca.intent

import com.embabel.agent.api.common.Ai
import com.embabel.common.ai.model.LlmOptions
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import se.alipsa.lca.tools.ModelRegistry

import java.util.Collections
import java.util.Locale
import java.util.Objects

@Component
@CompileStatic
class IntentRouterAgent {

  private static final Logger log = LoggerFactory.getLogger(IntentRouterAgent)
  private static final String DEFAULT_ALLOW_LIST =
    "/chat,/plan,/review,/implement,/edit,/apply,/run,/gitapply,/git-push,/search,/codesearch"

  private final Ai ai
  private final ModelRegistry modelRegistry
  private final IntentRouterParser parser
  private final String model
  private final String fallbackModel
  private final double temperature
  private final int maxTokens
  private final double confidenceThreshold
  private final double secondOpinionThreshold
  private final List<String> allowedCommands
  private final Map<String, String> allowedLookup

  IntentRouterAgent(
    Ai ai,
    ModelRegistry modelRegistry,
    IntentRouterParser parser,
    @Value('${assistant.intent.model:tinyllama}') String model,
    @Value('${assistant.intent.fallback-model:gpt-oss:20b}') String fallbackModel,
    @Value('${assistant.intent.temperature:0.0}') double temperature,
    @Value('${assistant.intent.max-tokens:256}') int maxTokens,
    @Value('${assistant.intent.confidence-threshold:0.8}') double confidenceThreshold,
    @Value('${assistant.intent.second-opinion-threshold:0.6}') double secondOpinionThreshold,
    @Value('${assistant.intent.allowed-commands:' + DEFAULT_ALLOW_LIST + '}') String allowedCommandsValue
  ) {
    this.ai = Objects.requireNonNull(ai, "ai must not be null")
    this.modelRegistry = Objects.requireNonNull(modelRegistry, "modelRegistry must not be null")
    this.parser = parser != null ? parser : new IntentRouterParser()
    this.model = normaliseValue(model)
    this.fallbackModel = normaliseValue(fallbackModel)
    this.temperature = temperature
    this.maxTokens = maxTokens > 0 ? maxTokens : 256
    this.confidenceThreshold = confidenceThreshold > 0 ? confidenceThreshold : 0.8d
    this.secondOpinionThreshold = secondOpinionThreshold > 0 ? secondOpinionThreshold : 0.6d
    List<String> parsed = parseAllowedCommands(allowedCommandsValue)
    this.allowedCommands = parsed.isEmpty() ? List.of("/chat") : List.copyOf(parsed)
    this.allowedLookup = Collections.unmodifiableMap(buildLookup(this.allowedCommands))
  }

  IntentRouterResult route(String input) {
    if (input == null || input.trim().isEmpty()) {
      return fallbackResult("Input was empty.")
    }
    String prompt = buildPrompt(input, false)
    IntentRouterResult result = parseResponse(prompt, temperature, model)
    if (result == null) {
      String retryPrompt = buildPrompt(input, true)
      result = parseResponse(retryPrompt, 0.0d, model)
    }
    if (result == null) {
      return fallbackResult("I couldn't understand that request. Try rephrasing or use /help to see available commands.")
    }

    // Pre-validate to normalize commands and confidence (but don't apply threshold yet)
    IntentRouterResult preValidated = preValidateResult(result)

    // If confidence is between secondOpinionThreshold and confidenceThreshold, get second opinion
    if (preValidated.confidence >= secondOpinionThreshold && preValidated.confidence < confidenceThreshold &&
        fallbackModel != null && !fallbackModel.equals(model)) {
      log.debug("Primary model confidence {}%, getting second opinion from fallback model",
        preValidated.confidence * 100)
      IntentRouterResult secondOpinion = parseResponse(prompt, temperature, fallbackModel)
      if (secondOpinion == null) {
        String retryPrompt = buildPrompt(input, true)
        secondOpinion = parseResponse(retryPrompt, 0.0d, fallbackModel)
      }
      if (secondOpinion != null) {
        IntentRouterResult preValidatedSecondOpinion = preValidateResult(secondOpinion)
        // Use the result with higher confidence (both are already >= secondOpinionThreshold)
        if (preValidatedSecondOpinion.confidence > preValidated.confidence) {
          log.debug("Using second opinion ({}%) over primary ({}%)",
            preValidatedSecondOpinion.confidence * 100, preValidated.confidence * 100)
          preValidatedSecondOpinion.modelUsed = fallbackModel
          preValidatedSecondOpinion.usedSecondOpinion = true
          // Accept the better result even if below normal threshold (it's above secondOpinionThreshold)
          return preValidatedSecondOpinion
        } else {
          log.debug("Keeping primary result ({}%) over second opinion ({}%)",
            preValidated.confidence * 100, preValidatedSecondOpinion.confidence * 100)
          preValidated.modelUsed = model
          // Accept the primary result even if below normal threshold (it's above secondOpinionThreshold)
          return preValidated
        }
      }
    }

    preValidated.modelUsed = model
    // Only apply threshold check if we didn't go through second opinion process
    applyConfidenceThreshold(preValidated)
  }

  protected String generateResponse(String prompt, LlmOptions options) {
    ai.withLlm(options).generateText(prompt)
  }

  private IntentRouterResult parseResponse(String prompt, double temp, String targetModel) {
    String resolvedModel = resolveSpecificModel(targetModel)
    LlmOptions options = resolvedModel != null ? LlmOptions.withModel(resolvedModel) : LlmOptions.withDefaultLlm()
    options = options.withTemperature(temp)
    if (maxTokens > 0) {
      options = options.withMaxTokens(maxTokens)
    }
    String response = generateResponse(prompt, options)
    try {
      return parser.parse(response)
    } catch (IllegalArgumentException e) {
      log.debug("Failed to parse router response.", e)
      return null
    }
  }

  private IntentRouterResult validateResult(IntentRouterResult result) {
    IntentRouterResult preValidated = preValidateResult(result)
    applyConfidenceThreshold(preValidated)
  }

  private IntentRouterResult preValidateResult(IntentRouterResult result) {
    double confidence = normaliseConfidence(result?.confidence)
    List<IntentCommand> commands = new ArrayList<>()
    boolean invalid = false
    if (result?.commands != null) {
      result.commands.each { IntentCommand command ->
        IntentCommand normalised = normaliseCommand(command)
        if (normalised == null) {
          invalid = true
          return
        }
        commands.add(normalised)
      }
    }
    if (invalid) {
      return fallbackResult("I don't recognize that command. Use /help to see what's available, or just describe what you need.")
    }
    if (commands.isEmpty()) {
      return fallbackResult("I couldn't figure out what to do with that. Try being more specific, like 'review my code' or 'show me the project structure'.")
    }
    new IntentRouterResult(commands, confidence, result?.explanation, result?.modelUsed, result?.usedSecondOpinion ?: false)
  }

  private IntentRouterResult applyConfidenceThreshold(IntentRouterResult result) {
    if (result.confidence < confidenceThreshold) {
      return fallbackResult("I'm not quite sure what you want. Could you rephrase that or use a slash command like /chat, /plan, or /review?")
    }
    result
  }

  private IntentCommand normaliseCommand(IntentCommand command) {
    if (command == null || command.name == null || command.name.trim().isEmpty()) {
      return null
    }
    String raw = command.name.trim()
    String name = normaliseCommandName(raw)
    String lookupKey = name.toLowerCase(Locale.ROOT)
    String canonical = allowedLookup.get(lookupKey)
    if (canonical == null) {
      return null
    }
    Map<String, Object> args = command.args != null ? command.args : Collections.emptyMap()
    new IntentCommand(canonical, args)
  }

  private IntentRouterResult fallbackResult(String reason) {
    IntentCommand command = new IntentCommand("/chat", Collections.emptyMap())
    new IntentRouterResult(List.of(command), 0.0d, reason, null, false)
  }

  private String buildPrompt(String input, boolean strict) {
    StringBuilder builder = new StringBuilder()
    if (strict) {
      builder.append("You returned invalid JSON. Output strictly valid JSON now.\n")
    }
    builder.append("You are an intent router for a local developer CLI.\n")
    builder.append("You will be given a user input (may be single or multi-line).\n")
    builder.append("Extract the user's intent and map it to commands from this allow-list:\n\n")
    builder.append("Command descriptions:\n")
    builder.append("- /chat: General conversation, questions, explanations, or when intent is unclear\n")
    builder.append("- /plan: Create implementation plans, architectural suggestions, design proposals\n")
    builder.append("- /review: Code review for correctness, security, style (not for getting suggestions)\n")
    builder.append("- /implement: Actually create or modify files to implement changes\n")
    builder.append("- /edit: Open editor to draft or modify a prompt\n")
    builder.append("- /search: Web search for information\n")
    builder.append("- /codesearch: Search repository files for code patterns\n")
    builder.append("- /run: Execute project commands\n")
    builder.append("- /apply: Apply diffs or patches\n")
    builder.append("- /gitapply: Apply patches using git\n")
    builder.append("- /git-push: Push changes to git remote\n\n")
    builder.append("Allowed commands: ").append(String.join(", ", allowedCommands)).append("\n\n")
    builder.append("IMPORTANT routing rules:\n")
    builder.append("- Phrases like 'suggestion for', 'how might', 'approach to', 'design for', 'propose' → ALWAYS use /plan\n")
    builder.append("- Phrases like 'give a suggestion', 'provide suggestions', 'what would be', 'how should I' → use /plan\n")
    builder.append("- Phrases like 'review this code', 'check for bugs', 'validate' (WITHOUT asking for suggestions) → use /review\n")
    builder.append("- Phrases like 'create', 'implement', 'build', 'add feature' (requesting actual implementation) → use /implement\n")
    builder.append("- Phrases like 'search repository', 'search the code', 'find in files', 'grep for' → use /codesearch\n")
    builder.append("- Phrases like 'search the web', 'look up', 'google' → use /search\n")
    builder.append("- When user asks to 'review... AND give suggestions/ideas', the PRIMARY intent is suggestions → use /plan\n")
    builder.append("- 'Please review... and suggest how...' → PRIMARY intent is architectural suggestions → use /plan\n")
    builder.append("- For multi-line input, focus on the main request/question, not code examples or context.\n")
    builder.append("- If multiple intents are possible, choose the one matching the PRIMARY goal (suggestions > review > chat)\n")
    builder.append("- If truly ambiguous (confidence < 0.7), use /chat with lower confidence\n\n")
    builder.append("Return ONLY JSON. No prose, no markdown, no extra text.\n")
    builder.append("Your JSON must follow this schema:\n")
    builder.append("{\"commands\":[{\"name\":\"/review\",\"args\":{}}],")
    builder.append("\"confidence\":0.0,\"explanation\":\"...\"}\n\n")
    builder.append("User input:\n")
    builder.append(input.trim())
    builder.toString()
  }

  private String resolveModel() {
    String desired = model ?: fallbackModel
    if (desired == null) {
      return null
    }
    List<String> available = modelRegistry.listModels()
    if (available == null || available.isEmpty()) {
      return desired
    }
    String matchedDesired = available.find { it != null && it.equalsIgnoreCase(desired) }
    if (matchedDesired != null) {
      return matchedDesired
    }
    if (fallbackModel != null) {
      String matchedFallback = available.find { it != null && it.equalsIgnoreCase(fallbackModel) }
      if (matchedFallback != null) {
        return matchedFallback
      }
    }
    desired
  }

  private String resolveSpecificModel(String targetModel) {
    if (targetModel == null) {
      return resolveModel()
    }
    List<String> available = modelRegistry.listModels()
    if (available == null || available.isEmpty()) {
      return targetModel
    }
    String matched = available.find { it != null && it.equalsIgnoreCase(targetModel) }
    matched ?: targetModel
  }

  private static List<String> parseAllowedCommands(String value) {
    if (value == null || value.trim().isEmpty()) {
      return List.of()
    }
    List<String> commands = new ArrayList<>()
    value.split(",").each { String raw ->
      String trimmed = raw.trim()
      if (!trimmed) {
        return
      }
      String normalised = trimmed.startsWith("/") ? trimmed : "/${trimmed}"
      commands.add(normalised)
    }
    commands
  }

  private static Map<String, String> buildLookup(List<String> commands) {
    Map<String, String> lookup = new LinkedHashMap<>()
    commands.each { String command ->
      if (command != null && command.trim()) {
        lookup.put(command.toLowerCase(Locale.ROOT), command)
      }
    }
    lookup
  }

  private static String normaliseValue(String value) {
    value != null && value.trim() ? value.trim() : null
  }

  private static String normaliseCommandName(String value) {
    String trimmed = value.trim()
    int space = trimmed.indexOf(" ")
    String base = space > 0 ? trimmed.substring(0, space) : trimmed
    base.startsWith("/") ? base : "/${base}"
  }

  private static double normaliseConfidence(double confidence) {
    if (Double.isNaN(confidence) || Double.isInfinite(confidence)) {
      return 0.0d
    }
    double clamped = Math.max(0.0d, Math.min(1.0d, confidence))
    clamped
  }
}
