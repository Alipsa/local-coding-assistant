package se.alipsa.lca.agent

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@Component
class FileEditingAgent {

    private static final Logger log = LoggerFactory.getLogger(FileEditingAgent.class)
    private final Path projectRoot

    FileEditingAgent() {
        this(Paths.get(".").toAbsolutePath())
    }

    FileEditingAgent(Path projectRoot) {
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
