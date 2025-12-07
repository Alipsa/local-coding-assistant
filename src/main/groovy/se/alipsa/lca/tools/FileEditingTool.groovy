package se.alipsa.lca.tools

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@Component
class FileEditingTool {

    private static final Logger log = LoggerFactory.getLogger(FileEditingTool.class)
    private final Path projectRoot

  FileEditingTool() {
        this(Paths.get(".").toAbsolutePath())
    }

  FileEditingTool(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath()
    }

    private Path resolvePath(String filePath) {
        Path resolvedPath = projectRoot.resolve(filePath).normalize()
        if (!resolvedPath.startsWith(projectRoot)) {
            throw new IllegalArgumentException("File path must be within the project directory")
        }
        return resolvedPath
    }

    String writeFile(String filePath, String content) {
        try {
            Path path = resolvePath(filePath)
            Files.writeString(path, content)
            return "Successfully wrote to $filePath"
        } catch (IOException e) {
            log.error("Error writing to file {}: {}", filePath, e.getMessage())
            return "Error writing to file: ${e.getMessage()}"
        }
    }

    String replace(String filePath, String oldString, String newString) {
        try {
            Path path = resolvePath(filePath)
            String content = Files.readString(path)
            String newContent = content.replace(oldString, newString)
            Files.writeString(path, newContent)
            return "Successfully replaced content in $filePath"
        } catch (IOException e) {
            log.error("Error replacing content in file {}: {}", filePath, e.getMessage())
            return "Error replacing content in file: ${e.getMessage()}"
        }
    }

    String deleteFile(String filePath) {
        try {
            Path path = resolvePath(filePath)
            Files.delete(path)
            return "Successfully deleted $filePath"
        } catch (IOException e) {
            log.error("Error deleting file {}: {}", filePath, e.getMessage())
            return "Error deleting file: ${e.getMessage()}"
        }
    }
}
