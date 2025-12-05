package se.alipsa.lca.agent

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import org.junit.jupiter.api.io.TempDir

class FileEditingAgentTest {

    private FileEditingAgent fileEditingAgent
    @TempDir
    Path tempDir
    private Path tempFile

    @BeforeEach
    void setUp() {
        fileEditingAgent = new FileEditingAgent(tempDir)
        tempFile = tempDir.resolve("test-file.txt")
    }

    @AfterEach
    void tearDown() {
        // No need to delete tempDir, JUnit takes care of it
    }

    @Test
    void testWriteFile() {
        String content = "Hello, world!"
        String result = fileEditingAgent.writeFile("test-file.txt", content)
        assertEquals("Successfully wrote to test-file.txt", result)
        assertEquals(content, Files.readString(tempFile))
    }

    @Test
    void testReplace() {
        String initialContent = "Hello, world!"
        Files.writeString(tempFile, initialContent)
        String result = fileEditingAgent.replace("test-file.txt", "world", "Groovy")
        assertEquals("Successfully replaced content in test-file.txt", result)
        assertEquals("Hello, Groovy!", Files.readString(tempFile))
    }

    @Test
    void testDeleteFile() {
        Files.writeString(tempFile, "some content")
        String result = fileEditingAgent.deleteFile("test-file.txt")
        assertEquals("Successfully deleted test-file.txt", result)
        assertFalse(Files.exists(tempFile))
    }

    @Test
    void testPathOutsideProject() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            fileEditingAgent.writeFile("../test-file.txt", "some content")
        })
        assertEquals("File path must be within the project directory", exception.getMessage())
    }
}
