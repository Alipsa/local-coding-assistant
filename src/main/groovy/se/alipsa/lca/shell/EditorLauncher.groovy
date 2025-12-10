package se.alipsa.lca.shell

import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

import java.nio.file.Files
import java.nio.file.Path

@Component
@CompileStatic
class EditorLauncher {

  private static final Logger log = LoggerFactory.getLogger(EditorLauncher)

  String edit(String initialContent) {
    Path tempFile = Files.createTempFile("lca-prompt-", ".txt")
    try {
      Files.writeString(tempFile, initialContent ?: "")
      String editor = resolveEditor()
      ProcessBuilder pb = new ProcessBuilder(editor, tempFile.toString())
      pb.inheritIO()
      Process process = pb.start()
      int exit = process.waitFor()
      if (exit != 0) {
        throw new IllegalStateException(
          "Editor exited with code $exit using $editor. Set EDITOR to a working executable."
        )
      }
      return Files.readString(tempFile)
    } finally {
      try {
        Files.deleteIfExists(tempFile)
      } catch (IOException ex) {
        log.warn("Failed to delete temp prompt file {}", tempFile, ex)
      }
    }
  }

  protected String resolveEditor() {
    String editor = System.getenv("EDITOR")
    if (editor == null || editor.trim().isEmpty()) {
      return "vi"
    }
    String trimmed = editor.trim()
    if (trimmed.contains(";") || trimmed.contains("&")) {
      throw new IllegalArgumentException("EDITOR contains unsupported characters")
    }
    return trimmed
  }
}
