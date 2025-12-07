package se.alipsa.lca.agent

import se.alipsa.lca.tools.FileEditingTool
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class FileEditingSpec extends Specification {

    FileEditingTool fileEditingAgent
    @TempDir
    Path tempDir
    Path tempFile

    def setup() {
        fileEditingAgent = new FileEditingTool(tempDir)
        tempFile = tempDir.resolve("test-file.txt")
    }

    def "writes a file"() {
        when:
        def result = fileEditingAgent.writeFile("test-file.txt", "Hello, world!")

        then:
        result == "Successfully wrote to test-file.txt"
        Files.readString(tempFile) == "Hello, world!"
    }

    def "replaces content"() {
        given:
        Files.writeString(tempFile, "Hello, world!")

        when:
        def result = fileEditingAgent.replace("test-file.txt", "world", "Groovy")

        then:
        result == "Successfully replaced content in test-file.txt"
        Files.readString(tempFile) == "Hello, Groovy!"
    }

    def "deletes a file"() {
        given:
        Files.writeString(tempFile, "some content")

        when:
        def result = fileEditingAgent.deleteFile("test-file.txt")

        then:
        result == "Successfully deleted test-file.txt"
        !Files.exists(tempFile)
    }

    def "rejects paths outside project root"() {
        when:
        fileEditingAgent.writeFile("../test-file.txt", "some content")

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message == "File path must be within the project directory"
    }
}
