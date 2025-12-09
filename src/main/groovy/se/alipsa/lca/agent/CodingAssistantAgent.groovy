package se.alipsa.lca.agent

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.Export
import com.embabel.agent.api.common.Ai
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.domain.library.HasContent
import com.embabel.agent.prompt.persona.RoleGoalBackstory
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.core.types.Timestamped
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import groovy.transform.Canonical
import groovy.transform.CompileStatic
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.lang.NonNull
import se.alipsa.lca.tools.FileEditingTool
import se.alipsa.lca.tools.WebSearchTool

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Objects

@CompileStatic
class Personas {
  static final RoleGoalBackstory CODER = RoleGoalBackstory
    .withRole("Repository-Aware Software Engineer")
    .andGoal(
      "Deliver production-ready Groovy/Spring Boot code that aligns with the local coding assistant "
        + "project structure and tests"
    )
    .andBackstory(
      "Builds local-only development tooling, maps changes to concrete files, and keeps outputs structured "
        + "for downstream tools."
    )

  static final RoleGoalBackstory REVIEWER = RoleGoalBackstory
    .withRole("Repository Code Reviewer")
    .andGoal(
      "Identify correctness, maintainability, and integration risks in proposed changes before they land in "
        + "the codebase"
    )
    .andBackstory(
      "Critically inspects local assistant features, expects 2-space indentation, @CompileStatic, and test "
        + "coverage notes."
    )
}

@Agent(description = "Generate a code snippet or function based on user input and review it")
@Profile("!test")
@CompileStatic
class CodingAssistantAgent {

  @Canonical
  @CompileStatic
  static class CodeSnippet {
    String text
  }

  @Canonical
  @CompileStatic
  static class ReviewedCodeSnippet implements HasContent, Timestamped {
    CodeSnippet codeSnippet
    String review
    RoleGoalBackstory reviewer

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
${reviewer.getRole()}, ${getTimestamp().atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("EEEE, MMMM dd, yyyy"))}
""".stripIndent().trim()
    }
  }

  private final int snippetWordCount
  private final int reviewWordCount
  protected final String llmModel
  protected final double craftTemperature
  protected final double reviewTemperature
  protected final LlmOptions craftLlmOptions
  protected final LlmOptions reviewLlmOptions
  private final FileEditingTool fileEditingAgent
  private final WebSearchTool webSearchAgent

  CodingAssistantAgent(
    @Value('${snippetWordCount:200}') int snippetWordCount,
    @Value('${reviewWordCount:150}') int reviewWordCount,
    @Value('${assistant.llm.model:${embabel.models.default-llm:qwen3-coder:30b}}') String llmModel,
    @Value('${assistant.llm.temperature.craft:0.7}') double craftTemperature,
    @Value('${assistant.llm.temperature.review:0.35}') double reviewTemperature,
    FileEditingTool fileEditingAgent,
    WebSearchTool webSearchAgent
  ) {
    this.snippetWordCount = snippetWordCount
    this.reviewWordCount = reviewWordCount
    this.llmModel = llmModel
    this.craftTemperature = craftTemperature
    this.reviewTemperature = reviewTemperature
    this.craftLlmOptions = LlmOptions.withModel(llmModel).withTemperature(craftTemperature)
    this.reviewLlmOptions = LlmOptions.withModel(llmModel).withTemperature(reviewTemperature)
    this.fileEditingAgent = fileEditingAgent
    this.webSearchAgent = webSearchAgent
  }

  @AchievesGoal(
    description = "The code snippet has been crafted and reviewed by a senior engineer",
    export = @Export(remote = true, name = "writeAndReviewCode")
  )
  @Action
  ReviewedCodeSnippet reviewCode(UserInput userInput, CodeSnippet codeSnippet, Ai ai) {
    Objects.requireNonNull(ai, "Ai must not be null")
    String reviewPrompt = buildReviewPrompt(userInput, codeSnippet)
    String review = ai
      .withLlm(reviewLlmOptions)
      .withPromptContributor(Personas.REVIEWER)
      .generateText(reviewPrompt)

    new ReviewedCodeSnippet(codeSnippet, review, Personas.REVIEWER)
  }

  @Action
  CodeSnippet craftCode(UserInput userInput, Ai ai) {
    Objects.requireNonNull(ai, "Ai must not be null")
    String craftPrompt = buildCraftCodePrompt(userInput)
    ai
      .withLlm(craftLlmOptions)
      .withPromptContributor(Personas.CODER)
      .createObject(craftPrompt, CodeSnippet)
  }

  @Action(description = "Write content to a file. This will overwrite the file if it exists.")
  String writeFile(String filePath, String content) {
    fileEditingAgent.writeFile(filePath, content)
  }

  @Action(description = "Replace content in a file.")
  String replace(String filePath, String oldString, String newString) {
    fileEditingAgent.replace(filePath, oldString, newString)
  }

  @Action(description = "Delete a file.")
  String deleteFile(String filePath) {
    fileEditingAgent.deleteFile(filePath)
  }

  @Action(description = "Search the web for a given query")
  @JsonDeserialize(as = ArrayList.class, contentAs = WebSearchTool.SearchResult.class)
  List<WebSearchTool.SearchResult> search(String query) {
    webSearchAgent.search(query)
  }

  protected String buildCraftCodePrompt(UserInput userInput) {
    """
You are a repository-aware Groovy/Spring Boot coding assistant for a local-only CLI project.
Follow these rules:
- Indent with 2 spaces and keep lines under 120 characters.
- Use Groovy 5.0.3 with @CompileStatic when possible; avoid deprecated APIs.
- Map changes to existing files when possible and mention target file paths.
- Prefer Search and Replace Blocks for multi-file updates; avoid TODO placeholders.
- Include imports, validation, and error handling; favor testable designs and mention Spock coverage ideas.
Keep narrative text under ${snippetWordCount} words; code may exceed that to stay correct.

User request:
${userInput.getContent()}

Respond with:
Plan:
- short bullet list of steps rooted in the repository
Implementation:
```groovy
// target-file-or-class
// implementation
```
Notes:
- risks, required configuration, and follow-up tasks
""".stripIndent().trim()
  }

  protected String buildReviewPrompt(UserInput userInput, CodeSnippet codeSnippet) {
    """
You are a repository code reviewer for a Groovy 5 / Spring Boot 3.5 local coding assistant.
Assess the proposal for correctness, repository fit, error handling, and testing strategy.
Ensure 2-space indentation, @CompileStatic suitability, and avoidance of deprecated APIs.
Reference likely target files or layers and call out missing Spock coverage.
Limit narrative to ${reviewWordCount} words.

Code snippet to review:
${codeSnippet.text}

User request:
${userInput.getContent()}

Respond with sections:
Findings: bullet points starting with High/Medium/Low
Tests: list the specific tests or scenarios to validate
""".stripIndent().trim()
  }
}
