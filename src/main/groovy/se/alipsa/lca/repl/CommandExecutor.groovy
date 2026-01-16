package se.alipsa.lca.repl

import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import se.alipsa.lca.agent.PersonaMode
import se.alipsa.lca.review.ReviewSeverity
import se.alipsa.lca.shell.ShellCommands

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Executes commands by parsing command strings and calling ShellCommands methods.
 * Acts as a bridge between IntentRouter output and actual command execution.
 */
@Component
@CompileStatic
class CommandExecutor {

  private static final Logger log = LoggerFactory.getLogger(CommandExecutor)
  private static final Pattern COMMAND_PATTERN = ~/^\/(\w+)\s*(.*)/

  private final ShellCommands shellCommands

  CommandExecutor(ShellCommands shellCommands) {
    this.shellCommands = shellCommands
  }

  /**
   * Execute a command string (e.g., "/chat what is groovy" or "/plan --prompt design a feature").
   * Parses the command and arguments, then calls the appropriate ShellCommands method.
   */
  String execute(String commandLine) {
    if (commandLine == null || commandLine.trim().isEmpty()) {
      return null
    }

    String trimmed = commandLine.trim()
    Matcher matcher = COMMAND_PATTERN.matcher(trimmed)

    if (!matcher.matches()) {
      log.warn("Invalid command format: {}", commandLine)
      return "Invalid command format. Expected: /command [args]"
    }

    String command = matcher.group(1)
    String args = matcher.group(2)?.trim() ?: ""

    log.debug("Executing command: /{} with args: {}", command, args)

    switch (command.toLowerCase()) {
      case "chat":
        return executeChat(args)
      case "plan":
        return executePlan(args)
      case "implement":
        return executeImplement(args)
      case "review":
        return executeReview(args)
      case "search":
        return executeSearch(args)
      case "run":
        return executeRun(args)
      case "edit":
        return executeEdit(args)
      case "paste":
        return executePaste(args)
      case "gitapply":
      case "git-apply":
        return executeGitApply(args)
      case "apply":
        return executeApply(args)
      case "status":
        return executeStatus(args)
      case "diff":
        return executeDiff(args)
      case "tree":
        return executeTree(args)
      case "codesearch":
        return executeCodeSearch(args)
      case "help":
        return shellCommands.help()
      case "health":
        return shellCommands.health()
      case "exit":
      case "quit":
        // Trigger system exit
        System.exit(0)
        return null // Never reached
      default:
        return "Unknown command: /${command}. Type /help for available commands."
    }
  }

  private String executeChat(String args) {
    Map<String, Object> parsed = parseArgs(args)
    shellCommands.chat(
      extractWords(parsed) as String[],
      parsed.session as String ?: "default",
      parsePersona(parsed.persona as String) ?: PersonaMode.CODER,
      parsed.model as String,
      parsed.temperature as Double,
      parsed.reviewTemperature as Double,
      parsed.maxTokens as Integer,
      parsed.systemPrompt as String,
      parseBoolean(parsed.autoSave) ?: false
    )
  }

  private String executePlan(String args) {
    Map<String, Object> parsed = parseArgs(args)
    shellCommands.plan(
      extractWords(parsed) as String[],
      parsed.session as String ?: "default",
      parsePersona(parsed.persona as String) ?: PersonaMode.ARCHITECT,
      parsed.model as String,
      parsed.temperature as Double,
      parsed.reviewTemperature as Double,
      parsed.maxTokens as Integer,
      parsed.systemPrompt as String
    )
  }

  private String executeImplement(String args) {
    Map<String, Object> parsed = parseArgs(args)
    shellCommands.implement(
      extractWords(parsed) as String[],
      parsed.session as String ?: "default",
      parsed.model as String,
      parsed.temperature as Double,
      parsed.reviewTemperature as Double,
      parsed.maxTokens as Integer,
      parseBoolean(parsed.autoSave) ?: false
    )
  }

  private String executeReview(String args) {
    Map<String, Object> parsed = parseArgs(args)
    // Parse paths from remaining words or paths flag
    List<String> paths = null
    if (parsed.paths) {
      paths = (parsed.paths as String).split(',').toList()
    } else if (parsed.words && !(parsed.words as List).isEmpty()) {
      paths = parsed.words as List<String>
    }

    shellCommands.review(
      parsed.code as String ?: "",
      extractPromptValue(parsed),
      parsed.session as String ?: "default",
      parsed.model as String,
      parsed.reviewTemperature as Double,
      parsed.maxTokens as Integer,
      parsed.systemPrompt as String,
      paths,
      parseBoolean(parsed.staged) ?: false,
      ReviewSeverity.LOW, // minSeverity
      parseBoolean(parsed.noColor) ?: false,
      parseBoolean(parsed.logReview) ?: true,
      parseBoolean(parsed.security) ?: false,
      parseBoolean(parsed.sast) ?: false
    )
  }

  private String executeSearch(String args) {
    Map<String, Object> parsed = parseArgs(args)
    String query = extractPromptValue(parsed)
    shellCommands.search(
      query,
      parseInt(parsed.limit) ?: 5,
      parsed.session as String ?: "default",
      parsed.provider as String ?: "duckduckgo",
      parseLong(parsed.timeout) ?: 15000L,
      parseBoolean(parsed.headless) ?: true,
      parsed.enableWebSearch != null ? parseBoolean(parsed.enableWebSearch) : null
    )
  }

  private String executeRun(String args) {
    Map<String, Object> parsed = parseArgs(args)
    String command = extractPromptValue(parsed)
    shellCommands.runCommand(
      command,
      parseLong(parsed.timeout) ?: 60000L,
      parseInt(parsed.maxOutputChars) ?: 8000,
      parsed.session as String ?: "default",
      parseBoolean(parsed.confirm) ?: true,
      false // agentRequested
    )
  }

  private String executeStatus(String args) {
    Map<String, Object> parsed = parseArgs(args)
    shellCommands.gitStatus(
      parseBoolean(parsed.shortFormat) ?: false
    )
  }

  private String executeEdit(String args) {
    Map<String, Object> parsed = parseArgs(args)
    shellCommands.edit(
      parsed.seed as String,
      parseBoolean(parsed.send) ?: false,
      parsed.session as String ?: "default",
      parsePersona(parsed.persona as String) ?: PersonaMode.CODER
    )
  }

  private String executePaste(String args) {
    Map<String, Object> parsed = parseArgs(args)
    shellCommands.paste(
      parsed.content as String,
      parsed.endMarker as String ?: "/end",
      parseBoolean(parsed.send) ?: false,
      parsed.session as String ?: "default",
      parsePersona(parsed.persona as String) ?: PersonaMode.CODER
    )
  }

  private String executeGitApply(String args) {
    Map<String, Object> parsed = parseArgs(args)
    String patch = parsed.patch as String
    String patchFile = parsed.patchFile as String
    shellCommands.gitApply(
      patch,
      patchFile,
      parseBoolean(parsed.cached) ?: false,
      parseBoolean(parsed.check) ?: true,
      parseBoolean(parsed.confirm) ?: true
    )
  }

  private String executeApply(String args) {
    Map<String, Object> parsed = parseArgs(args)
    String patch = parsed.patch as String ?: extractPromptValue(parsed)
    String patchFile = parsed.patchFile as String
    shellCommands.applyPatch(
      patch,
      patchFile,
      parseBoolean(parsed.dryRun) ?: true,
      parseBoolean(parsed.confirm) ?: true
    )
  }

  private String executeDiff(String args) {
    Map<String, Object> parsed = parseArgs(args)
    // Parse paths from remaining words
    List<String> paths = (parsed.words as List<String>) ?: []
    shellCommands.gitDiff(
      parseBoolean(parsed.staged) ?: false,
      parseInt(parsed.context) ?: 3,
      paths.isEmpty() ? null : paths,
      parseBoolean(parsed.stat) ?: false
    )
  }

  private String executeTree(String args) {
    Map<String, Object> parsed = parseArgs(args)
    shellCommands.tree(
      parseInt(parsed.depth) ?: 3,
      parseBoolean(parsed.files) ?: false,
      parseInt(parsed.limit) ?: 100
    )
  }

  private String executeCodeSearch(String args) {
    Map<String, Object> parsed = parseArgs(args)
    String query = parsed.query as String ?: extractPromptValue(parsed)
    List<String> paths = null
    if (parsed.paths) {
      paths = (parsed.paths as String).split(',').toList()
    } else if (parsed.words && !(parsed.words as List).isEmpty() && !parsed.query) {
      // If no --query flag, treat first word as query and rest as paths
      List<String> words = parsed.words as List<String>
      query = words[0]
      if (words.size() > 1) {
        paths = words.subList(1, words.size())
      }
    }

    shellCommands.codeSearch(
      query,
      paths,
      parseInt(parsed.context) ?: 2,
      parseInt(parsed.limit) ?: 20,
      parseBoolean(parsed.pack) ?: false,
      parseInt(parsed.maxChars) ?: 8000,
      parseInt(parsed.maxTokens) ?: 0
    )
  }

  /**
   * Parse command arguments into a map.
   * Supports:
   * - Flags: --flag value
   * - Quoted strings: --prompt "some text"
   * - Positional args (everything not part of flags)
   */
  private Map<String, Object> parseArgs(String args) {
    Map<String, Object> result = [words: []]

    if (args == null || args.trim().isEmpty()) {
      return result
    }

    // Pattern to match --flag value or --flag "value with spaces"
    Pattern flagPattern = ~/--(\w+)(?:\s+(?:"([^"]*)"|'([^']*)'|(\S+)))?/

    Matcher matcher = flagPattern.matcher(args)
    int lastEnd = 0
    List<String> words = []

    while (matcher.find()) {
      // Collect any positional words before this flag
      if (matcher.start() > lastEnd) {
        String between = args.substring(lastEnd, matcher.start()).trim()
        if (between) {
          words.addAll(between.split(/\s+/))
        }
      }

      String flag = matcher.group(1)
      String value = matcher.group(2) ?: matcher.group(3) ?: matcher.group(4)

      if (value != null) {
        result[flag] = value
      } else {
        // Flag without value (boolean flag)
        result[flag] = "true"
      }

      lastEnd = matcher.end()
    }

    // Collect any remaining positional words
    if (lastEnd < args.length()) {
      String remaining = args.substring(lastEnd).trim()
      if (remaining) {
        words.addAll(remaining.split(/\s+/))
      }
    }

    result.words = words
    return result
  }

  /**
   * Extract words for commands that use String[] prompt parameter.
   * First tries --prompt flag, then falls back to positional words.
   */
  private List<String> extractWords(Map<String, Object> parsed) {
    if (parsed.prompt) {
      return [parsed.prompt as String]
    }
    return (parsed.words as List<String>) ?: []
  }

  /**
   * Extract prompt value as a single string.
   * Used by commands that expect String prompt instead of String[].
   */
  private String extractPromptValue(Map<String, Object> parsed) {
    if (parsed.prompt) {
      return parsed.prompt as String
    }
    List<String> words = parsed.words as List<String>
    return words ? words.join(" ") : ""
  }

  private PersonaMode parsePersona(String value) {
    if (value == null) return null
    try {
      return PersonaMode.valueOf(value.toUpperCase())
    } catch (IllegalArgumentException e) {
      log.warn("Invalid persona: {}", value)
      return null
    }
  }

  private Boolean parseBoolean(Object value) {
    if (value == null) return null
    if (value instanceof Boolean) return (Boolean) value
    String str = value.toString().toLowerCase()
    return str == "true" || str == "yes" || str == "1"
  }

  private Integer parseInt(Object value) {
    if (value == null) return null
    try {
      return Integer.parseInt(value.toString())
    } catch (NumberFormatException e) {
      log.warn("Invalid integer: {}", value)
      return null
    }
  }

  private Long parseLong(Object value) {
    if (value == null) return null
    try {
      return Long.parseLong(value.toString())
    } catch (NumberFormatException e) {
      log.warn("Invalid long: {}", value)
      return null
    }
  }
}
