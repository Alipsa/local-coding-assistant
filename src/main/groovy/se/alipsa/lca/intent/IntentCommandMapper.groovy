package se.alipsa.lca.intent

import groovy.transform.CompileStatic
import org.springframework.stereotype.Component

@Component
@CompileStatic
class IntentCommandMapper {

  List<String> map(String input, IntentRouterResult result) {
    String prompt = input != null ? input.trim() : ""
    if (!prompt) {
      prompt = ""
    }
    if (result == null || result.commands == null || result.commands.isEmpty()) {
      return List.of(buildChatCommand(prompt, Map.of()))
    }
    List<String> commands = new ArrayList<>()
    result.commands.each { IntentCommand command ->
      String mapped = mapCommand(prompt, command)
      if (mapped != null && mapped.trim()) {
        commands.add(mapped)
      }
    }
    if (commands.isEmpty()) {
      return List.of(buildChatCommand(prompt, Map.of()))
    }
    commands
  }

  private String mapCommand(String input, IntentCommand command) {
    if (command == null || command.name == null || command.name.trim().isEmpty()) {
      return null
    }
    Map<String, Object> args = normaliseArgs(command.args)
    String name = normaliseCommandName(command.name)
    switch (name) {
      case "/chat":
        return buildChatCommand(input, args)
      case "/plan":
        return buildPlanCommand(input, args)
      case "/review":
        return buildReviewCommand(input, args)
      case "/search":
        return buildSearchCommand(input, args)
      case "/run":
        return buildRunCommand(args)
      case "/apply":
        return buildApplyCommand(args)
      case "/gitapply":
        return buildGitApplyCommand(args)
      case "/edit":
        return buildEditCommand(input, args)
      default:
        return buildGenericCommand(name, args)
    }
  }

  private String buildChatCommand(String input, Map<String, Object> args) {
    String prompt = stringValue(args.get("prompt")) ?: input
    StringBuilder builder = new StringBuilder("/chat --prompt ")
    builder.append(quote(prompt))
    appendRemainingOptions(builder, args, Set.of("prompt"))
    builder.toString()
  }

  private String buildPlanCommand(String input, Map<String, Object> args) {
    String prompt = stringValue(args.get("prompt")) ?: input
    StringBuilder builder = new StringBuilder("/plan --prompt ")
    builder.append(quote(prompt))
    appendRemainingOptions(builder, args, Set.of("prompt"))
    builder.toString()
  }

  private String buildReviewCommand(String input, Map<String, Object> args) {
    String prompt = stringValue(args.get("prompt")) ?: input
    StringBuilder builder = new StringBuilder("/review --prompt ")
    builder.append(quote(prompt))
    List<String> paths = stringList(args.get("paths"))
    if (paths.isEmpty()) {
      paths = stringList(args.get("path"))
    }
    paths.each { String path ->
      builder.append(" --paths ").append(quote(path))
    }
    appendRemainingOptions(builder, args, Set.of("prompt", "path", "paths"))
    builder.toString()
  }

  private String buildSearchCommand(String input, Map<String, Object> args) {
    String query = stringValue(args.get("query")) ?: input
    if (!query) {
      return null
    }
    StringBuilder builder = new StringBuilder("/search --query ")
    builder.append(quote(query))
    appendRemainingOptions(builder, args, Set.of("query"))
    builder.toString()
  }

  private String buildRunCommand(Map<String, Object> args) {
    String command = stringValue(args.get("command"))
    if (!command) {
      command = stringValue(args.get("cmd"))
    }
    if (!command) {
      return null
    }
    StringBuilder builder = new StringBuilder("/run --command ")
    builder.append(quote(command))
    appendRemainingOptions(builder, args, Set.of("command", "cmd"))
    builder.toString()
  }

  private String buildApplyCommand(Map<String, Object> args) {
    String patch = stringValue(args.get("patch"))
    String patchFile = stringValue(args.get("patch-file"))
    if (!patch && !patchFile) {
      return null
    }
    StringBuilder builder = new StringBuilder("/apply")
    if (patch) {
      builder.append(" --patch ").append(quote(patch))
    }
    if (patchFile) {
      builder.append(" --patch-file ").append(quote(patchFile))
    }
    appendRemainingOptions(builder, args, Set.of("patch", "patch-file"))
    builder.toString()
  }

  private String buildGitApplyCommand(Map<String, Object> args) {
    String patch = stringValue(args.get("patch"))
    String patchFile = stringValue(args.get("patch-file"))
    if (!patch && !patchFile) {
      return null
    }
    StringBuilder builder = new StringBuilder("/gitapply")
    if (patch) {
      builder.append(" --patch ").append(quote(patch))
    }
    if (patchFile) {
      builder.append(" --patch-file ").append(quote(patchFile))
    }
    appendRemainingOptions(builder, args, Set.of("patch", "patch-file"))
    builder.toString()
  }

  private String buildEditCommand(String input, Map<String, Object> args) {
    String seed = stringValue(args.get("seed"))
    if (!seed && input) {
      seed = input
    }
    StringBuilder builder = new StringBuilder("/edit")
    if (seed) {
      builder.append(" --seed ").append(quote(seed))
    }
    appendRemainingOptions(builder, args, Set.of("seed"))
    builder.toString()
  }

  private static String buildGenericCommand(String name, Map<String, Object> args) {
    StringBuilder builder = new StringBuilder(name)
    appendRemainingOptions(builder, args, Set.of())
    builder.toString()
  }

  private static void appendRemainingOptions(
    StringBuilder builder,
    Map<String, Object> args,
    Set<String> excluded
  ) {
    if (args == null || args.isEmpty()) {
      return
    }
    args.each { String key, Object value ->
      if (!key || excluded.contains(key)) {
        return
      }
      appendOption(builder, key, value)
    }
  }

  private static void appendOption(StringBuilder builder, String key, Object value) {
    if (value == null) {
      return
    }
    String option = "--${key}"
    if (value instanceof Collection) {
      ((Collection) value).each { Object entry ->
        if (entry != null) {
          builder.append(" ").append(option).append(" ").append(quote(entry.toString()))
        }
      }
      return
    }
    if (value.getClass().isArray()) {
      Object[] array = (Object[]) value
      array.each { Object entry ->
        if (entry != null) {
          builder.append(" ").append(option).append(" ").append(quote(entry.toString()))
        }
      }
      return
    }
    if (value instanceof Boolean) {
      builder.append(" ").append(option).append(" ").append(value.toString())
      return
    }
    builder.append(" ").append(option).append(" ").append(quote(value.toString()))
  }

  private static Map<String, Object> normaliseArgs(Map<String, Object> args) {
    if (args == null || args.isEmpty()) {
      return Map.of()
    }
    Map<String, Object> normalised = new LinkedHashMap<>()
    args.each { Object key, Object value ->
      if (key == null) {
        return
      }
      normalised.put(normaliseKey(key.toString()), value)
    }
    normalised
  }

  private static String normaliseKey(String raw) {
    String trimmed = raw != null ? raw.trim() : ""
    if (!trimmed) {
      return ""
    }
    StringBuilder builder = new StringBuilder()
    for (int i = 0; i < trimmed.length(); i++) {
      char ch = trimmed.charAt(i)
      if (ch == '_' as char) {
        builder.append('-')
        continue
      }
      if (Character.isUpperCase(ch)) {
        if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '-') {
          builder.append('-')
        }
        builder.append(Character.toLowerCase(ch))
        continue
      }
      builder.append(Character.toLowerCase(ch))
    }
    builder.toString()
  }

  private static String stringValue(Object value) {
    if (value == null) {
      return null
    }
    String text = value.toString().trim()
    text ? text : null
  }

  private static List<String> stringList(Object value) {
    if (value == null) {
      return List.of()
    }
    if (value instanceof Collection) {
      List<String> values = ((Collection) value).collect { Object entry ->
        entry != null ? entry.toString() : null
      }.findAll { it } as List<String>
      return values
    }
    if (value.getClass().isArray()) {
      Object[] array = (Object[]) value
      List<String> values = array.collect { Object entry ->
        entry != null ? entry.toString() : null
      }.findAll { it } as List<String>
      return values
    }
    String text = value.toString().trim()
    text ? List.of(text) : List.of()
  }

  private static String normaliseCommandName(String value) {
    String trimmed = value.trim()
    int space = trimmed.indexOf(" ")
    String base = space > 0 ? trimmed.substring(0, space) : trimmed
    base.startsWith("/") ? base : "/${base}"
  }

  private static String quote(String value) {
    String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
    "\"${escaped}\""
  }
}
