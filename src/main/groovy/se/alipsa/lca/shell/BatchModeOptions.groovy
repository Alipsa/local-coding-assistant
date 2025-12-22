package se.alipsa.lca.shell

import groovy.transform.Canonical
import groovy.transform.CompileStatic

@Canonical
@CompileStatic
class BatchModeOptions {

  String commandText
  String batchFile
  boolean batchJson
  boolean assumeYes

  boolean isEnabled() {
    boolean hasCommand = commandText != null && commandText.trim()
    boolean hasFile = batchFile != null && batchFile.trim()
    hasCommand || hasFile
  }

  static BatchModeOptions parse(String[] args) {
    String commandText = null
    String batchFile = null
    boolean batchJson = false
    boolean assumeYes = false
    if (args == null) {
      return new BatchModeOptions(commandText, batchFile, batchJson, assumeYes)
    }
    for (int i = 0; i < args.length; i++) {
      String arg = args[i]
      if (arg == null || arg.isEmpty()) {
        continue
      }
      if (arg == "-c" || arg == "--command") {
        if (i + 1 >= args.length) {
          throw new IllegalArgumentException("Missing value for " + arg)
        }
        if (commandText != null) {
          throw new IllegalArgumentException("Duplicate command value provided.")
        }
        commandText = args[++i]
        continue
      }
      if (arg.startsWith("--command=")) {
        if (commandText != null) {
          throw new IllegalArgumentException("Duplicate command value provided.")
        }
        commandText = arg.substring("--command=".length())
        continue
      }
      if (arg.startsWith("-c=")) {
        if (commandText != null) {
          throw new IllegalArgumentException("Duplicate command value provided.")
        }
        commandText = arg.substring(3)
        continue
      }
      if (arg == "--batch-file") {
        if (i + 1 >= args.length) {
          throw new IllegalArgumentException("Missing value for --batch-file")
        }
        if (batchFile != null) {
          throw new IllegalArgumentException("Duplicate batch file value provided.")
        }
        batchFile = args[++i]
        continue
      }
      if (arg.startsWith("--batch-file=")) {
        if (batchFile != null) {
          throw new IllegalArgumentException("Duplicate batch file value provided.")
        }
        batchFile = arg.substring("--batch-file=".length())
        continue
      }
      if (arg == "--batch-json") {
        batchJson = true
        continue
      }
      if (arg == "--yes" || arg == "--assume-yes") {
        assumeYes = true
        continue
      }
    }
    if (commandText != null && commandText.trim().isEmpty()) {
      throw new IllegalArgumentException("Command string must not be empty.")
    }
    if (batchFile != null && batchFile.trim().isEmpty()) {
      throw new IllegalArgumentException("Batch file path must not be empty.")
    }
    if (commandText != null && batchFile != null) {
      throw new IllegalArgumentException("Use either --command or --batch-file, not both.")
    }
    new BatchModeOptions(commandText, batchFile, batchJson, assumeYes)
  }
}
