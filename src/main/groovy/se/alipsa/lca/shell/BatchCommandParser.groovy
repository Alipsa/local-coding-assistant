package se.alipsa.lca.shell

import groovy.transform.CompileStatic

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

@CompileStatic
class BatchCommandParser {

  static List<String> splitCommands(String input) {
    if (input == null) {
      return List.of()
    }
    List<String> commands = new ArrayList<>()
    StringBuilder current = new StringBuilder()
    boolean inSingle = false
    boolean inDouble = false
    boolean escaping = false
    for (int i = 0; i < input.length(); i++) {
      char ch = input.charAt(i)
      if (escaping) {
        current.append(ch)
        escaping = false
        continue
      }
      if (ch == '\\' && !inSingle) {
        escaping = true
        current.append(ch)
        continue
      }
      if (ch == '\'' && !inDouble) {
        inSingle = !inSingle
        current.append(ch)
        continue
      }
      if (ch == '"' && !inSingle) {
        inDouble = !inDouble
        current.append(ch)
        continue
      }
      if (ch == ';' && !inSingle && !inDouble) {
        addCommand(commands, current)
        current.setLength(0)
        continue
      }
      current.append(ch)
    }
    if (escaping || inSingle || inDouble) {
      throw new IllegalArgumentException("Unterminated quote or escape sequence in batch command string.")
    }
    addCommand(commands, current)
    commands
  }

  static List<String> readCommands(Path file) {
    if (file == null) {
      return List.of()
    }
    if (!Files.exists(file)) {
      throw new IllegalArgumentException("Batch file not found: ${file}")
    }
    if (!Files.isReadable(file)) {
      throw new IllegalArgumentException("Batch file is not readable: ${file}")
    }
    List<String> commands = new ArrayList<>()
    Files.newBufferedReader(file, StandardCharsets.UTF_8).withCloseable { reader ->
      String line
      while ((line = reader.readLine()) != null) {
        if (line.trim().isEmpty()) {
          continue
        }
        commands.addAll(splitCommands(line))
      }
    }
    commands
  }

  static List<String> readCommandsFromStdIn() {
    List<String> commands = new ArrayList<>()
    new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).withCloseable { reader ->
      String line
      while ((line = reader.readLine()) != null) {
        if (line.trim().isEmpty()) {
          continue
        }
        commands.addAll(splitCommands(line))
      }
    }
    commands
  }

  private static void addCommand(List<String> commands, StringBuilder current) {
    String value = current.toString().trim()
    if (value) {
      commands.add(value)
    }
  }
}
