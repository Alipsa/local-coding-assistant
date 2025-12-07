package se.alipsa.lca.agent

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.Export
import com.embabel.agent.api.common.Ai
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.domain.library.HasContent
import com.embabel.agent.prompt.persona.Persona
import com.embabel.agent.prompt.persona.RoleGoalBackstory
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.core.types.Timestamped
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import groovy.transform.Canonical
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.lang.NonNull
import se.alipsa.lca.tools.FileEditingTool
import se.alipsa.lca.tools.WebSearchTool

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class Personas {
    static final RoleGoalBackstory CODER = RoleGoalBackstory
            .withRole("Expert Software Engineer")
            .andGoal("Write high-quality, efficient, and well-documented code snippets or functions")
            .andBackstory("Has 20 years of experience in multiple programming languages, specializing in problem-solving and clean code architecture");

    static final Persona REVIEWER = new Persona(
            "Code Review Expert",
            "Senior Staff Engineer",
            "Thorough and constructive",
            "Ensure code quality, maintainability, and adherence to best practices"
    );
}


@Agent(description = "Generate a code snippet or function based on user input and review it")
@Profile("!test")
class CodingAssistantAgent {

    @Canonical
    static class CodeSnippet {
        String text
    }

    @Canonical
    static class ReviewedCodeSnippet implements HasContent, Timestamped {
        CodeSnippet codeSnippet
        String review
        Persona reviewer

        @Override
        @NonNull
        Instant getTimestamp() {
            Instant.now()
        }

        @Override
        @NonNull
        String getContent() {
            """
                            # Code Snippet
                            ${codeSnippet.text}
                            
                            # Review
                            ${review}
                            
                            # Reviewer
                            ${reviewer.getName()}, ${getTimestamp().atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("EEEE, MMMM dd, yyyy"))}
                            """.trim()
        }
    }

    private final int snippetWordCount
    private final int reviewWordCount
    private final FileEditingTool fileEditingAgent
    private final WebSearchTool webSearchAgent

    CodingAssistantAgent(
        @Value('${snippetWordCount:200}') int snippetWordCount,
        @Value('${reviewWordCount:150}') int reviewWordCount,
        FileEditingTool fileEditingAgent,
        WebSearchTool webSearchAgent
    ) {
        this.snippetWordCount = snippetWordCount
        this.reviewWordCount = reviewWordCount
        this.fileEditingAgent = fileEditingAgent
        this.webSearchAgent = webSearchAgent
    }

    @AchievesGoal(
            description = "The code snippet has been crafted and reviewed by a senior engineer",
            export = @Export(remote = true, name = "writeAndReviewCode")
    )
    @Action
    ReviewedCodeSnippet reviewCode(UserInput userInput, CodeSnippet codeSnippet, Ai ai) {
        def review = ai
                .withAutoLlm()
                .withPromptContributor(Personas.REVIEWER)
                .generateText("""
                                You will be given a code snippet to review.
                                Review it in ${reviewWordCount} words or less.
                                Consider whether or not the code is correct, efficient, and well-documented.
                                Also consider whether the code is appropriate given the original user input.
                                
                                # Code Snippet
                                ${codeSnippet.text}
                                
                                # User input that inspired the code
                                ${userInput.getContent()}
                                """.trim());

        new ReviewedCodeSnippet(
                codeSnippet,
                review,
                Personas.REVIEWER
        )
    }

    @Action
    CodeSnippet craftCode(UserInput userInput, Ai ai) {
        ai
                // Higher temperature for more creative output
                .withLlm(LlmOptions
                        .withAutoLlm() // You can also choose a specific model or role here
                        .withTemperature(.7)
                )
                .withPromptContributor(Personas.CODER)
                .createObject("""
                                Craft a code snippet or function in ${snippetWordCount} words or less.
                                The code should be efficient, correct, and well-documented.
                                Use the user's input as inspiration.
                                
                                # User input
                                ${userInput.getContent()}
                                """.trim(), CodeSnippet)
    }

    @Action(description = "Write content to a file. This will overwrite the file if it exists.")
    String writeFile(String filePath, String content) {
        return fileEditingAgent.writeFile(filePath, content)
    }

    @Action(description = "Replace content in a file.")
    String replace(String filePath, String oldString, String newString) {
        return fileEditingAgent.replace(filePath, oldString, newString)
    }

    @Action(description = "Delete a file.")
    String deleteFile(String filePath) {
        return fileEditingAgent.deleteFile(filePath)
    }

    @Action(description = "Search the web for a given query")
    @JsonDeserialize(as = ArrayList.class, contentAs = WebSearchTool.SearchResult.class)
    List<WebSearchTool.SearchResult> search(String query) {
        return webSearchAgent.search(query)
    }
}
