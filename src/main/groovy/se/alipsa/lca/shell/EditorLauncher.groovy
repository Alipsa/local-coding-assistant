package se.alipsa.lca.shell

import groovy.transform.CompileStatic
import org.springframework.stereotype.Component

import java.nio.file.Files
import java.nio.file.Path

@Component
@CompileStatic
class EditorLauncher {

  String edit(String initialContent) {
    Path tempFile = Files.createTempFile("lca-prompt-", ".txt")
    Files.writeString(tempFile, initialContent ?: "")
    String editor = System.getenv("EDITOR") ?: "vi"
    ProcessBuilder pb = new ProcessBuilder(editor, tempFile.toString())
    pb.inheritIO()
    Process process = pb.start()
    int exit = process.waitFor()
    if (exit != 0) {
      throw new IllegalStateException("Editor exited with code $exit using $editor")
    }
    String text = Files.readString(tempFile)
    Files.deleteIfExists(tempFile)
    text
  }
}
