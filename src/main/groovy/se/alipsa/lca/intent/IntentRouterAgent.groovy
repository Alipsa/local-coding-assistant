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
    "/chat,/plan,/review,/edit,/apply,/run,/gitapply,/git-push,/search"

  private final Ai ai
  private final ModelRegistry modelRegistry
  private final IntentRouterParser parser
  private final String model
  private final String fallbackModel
  private final double temperature
  private final int maxTokens
  private final double confidenceThreshold
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
    List<String> parsed = parseAllowedCommands(allowedCommandsValue)
    this.allowedCommands = parsed.isEmpty() ? List.of("/chat") : List.copyOf(parsed)
    this.allowedLookup = Collections.unmodifiableMap(buildLookup(this.allowedCommands))
  }

  IntentRouterResult route(String input) {
    if (input == null || input.trim().isEmpty()) {
      return fallbackResult("Input was empty.")
    }
    String prompt = buildPrompt(input, false)
    IntentRouterResult result = parseResponse(prompt, temperature)
    if (result == null) {
      String retryPrompt = buildPrompt(input, true)
      result = parseResponse(retryPrompt, 0.0d)
    }
    if (result == null) {
      return fallbackResult("I couldn't understand that request. Try rephrasing or use /help to see available commands.")
    }
    validateResult(result)
  }

  protected String generateResponse(String prompt, LlmOptions options) {
    ai.withLlm(options).generateText(prompt)
  }

  private IntentRouterResult parseResponse(String prompt, double temp) {
    String resolvedModel = resolveModel()
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
    if (confidence < confidenceThreshold) {
      return fallbackResult("I'm not quite sure what you want. Could you rephrase that or use a slash command like /chat, /plan, or /review?")
    }
    new IntentRouterResult(commands, confidence, result?.explanation)
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
    new IntentRouterResult(List.of(command), 0.0d, reason)
  }

  private String buildPrompt(String input, boolean strict) {
    StringBuilder builder = new StringBuilder()
    if (strict) {
      builder.append("You returned invalid JSON. Output strictly valid JSON now.\n")
    }
    builder.append("You are an intent router for a local developer CLI.\n")
    builder.append("You will be given a user input.\n")
    builder.append("You must decide which commands to execute from this allow-list:\n")
    builder.append(String.join(", ", allowedCommands)).append("\n")
    builder.append("Return ONLY JSON. No prose, no markdown, no extra text.\n")
    builder.append("Your JSON must follow this schema:\n")
    builder.append("{\"commands\":[{\"name\":\"/review\",\"args\":{}}],")
    builder.append("\"confidence\":0.0,\"explanation\":\"...\"}\n")
    builder.append("If no specific command matches, choose /chat with empty args and set confidence accordingly.\n")
    builder.append("If you are unsure, prefer fewer commands with lower confidence.\n")
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
