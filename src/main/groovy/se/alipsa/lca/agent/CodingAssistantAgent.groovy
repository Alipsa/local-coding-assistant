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
import se.alipsa.lca.tools.CodeSearchTool

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

  static final RoleGoalBackstory ARCHITECT = RoleGoalBackstory
    .withRole("Software Architect")
    .andGoal("Explain design trade-offs and guide structural changes for the local coding assistant")
    .andBackstory(
      "Balances clarity with pragmatism, highlights risks, and keeps designs aligned with Groovy 5 and "
        + "Spring Boot 3.5 conventions."
    )
}

@CompileStatic
enum PersonaMode {
  CODER,
  ARCHITECT,
  REVIEWER
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
  private final CodeSearchTool codeSearchTool

  CodingAssistantAgent(
    @Value('${snippetWordCount:200}') int snippetWordCount,
    @Value('${reviewWordCount:150}') int reviewWordCount,
    @Value('${assistant.llm.model:qwen3-coder:30b}') String llmModel,
    @Value('${assistant.llm.temperature.craft:0.7}') double craftTemperature,
    @Value('${assistant.llm.temperature.review:0.35}') double reviewTemperature,
    FileEditingTool fileEditingAgent,
    WebSearchTool webSearchAgent,
    CodeSearchTool codeSearchTool
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
    this.codeSearchTool = codeSearchTool
  }

  @AchievesGoal(
    description = "The code snippet has been crafted and reviewed by a senior engineer",
    export = @Export(remote = true, name = "writeAndReviewCode")
  )
  @Action
  ReviewedCodeSnippet reviewCode(UserInput userInput, CodeSnippet codeSnippet, Ai ai) {
    reviewCode(userInput, codeSnippet, ai, null, null)
  }

  @Action
  ReviewedCodeSnippet reviewCode(
    UserInput userInput,
    CodeSnippet codeSnippet,
    Ai ai,
    LlmOptions llmOverride,
    String systemPromptOverride
  ) {
    Objects.requireNonNull(ai, "Ai must not be null")
    LlmOptions options = llmOverride ?: reviewLlmOptions
    String reviewPrompt = buildReviewPrompt(userInput, codeSnippet, systemPromptOverride)
    String review = ai
      .withLlm(options)
      .withPromptContributor(Personas.REVIEWER)
      .generateText(reviewPrompt)
    String formattedReview = enforceReviewFormat(review)

    new ReviewedCodeSnippet(codeSnippet, formattedReview, Personas.REVIEWER)
  }

  @Action
  CodeSnippet craftCode(UserInput userInput, Ai ai) {
    craftCode(userInput, ai, PersonaMode.CODER)
  }

  @Action
  CodeSnippet craftCode(UserInput userInput, Ai ai, PersonaMode personaMode) {
    craftCode(userInput, ai, personaMode, null, null)
  }

  @Action
  CodeSnippet craftCode(
    UserInput userInput,
    Ai ai,
    PersonaMode personaMode,
    LlmOptions llmOverride,
    String systemPromptOverride
  ) {
    Objects.requireNonNull(ai, "Ai must not be null")
    Objects.requireNonNull(personaMode, "Persona mode must not be null")
    def template = personaTemplate(personaMode)
    LlmOptions options = llmOverride ?: craftLlmOptions
    String craftPrompt = buildCraftCodePrompt(userInput, personaMode, systemPromptOverride)
    CodeSnippet snippet = ai
      .withLlm(options)
      .withPromptContributor(template.persona)
      .createObject(craftPrompt, CodeSnippet)
    snippet.text = enforceCodeFormat(snippet.text)
    snippet
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

  @Action(description = "Apply a unified diff patch with backup and conflict detection.")
  FileEditingTool.PatchResult applyPatch(String patchText, boolean dryRun) {
    fileEditingAgent.applyPatch(patchText, dryRun)
  }

  @Action(description = "Replace a specific line range in a file.")
  FileEditingTool.EditResult replaceRange(
    String filePath,
    int startLine,
    int endLine,
    String newContent,
    boolean dryRun
  ) {
    fileEditingAgent.replaceRange(filePath, startLine, endLine, newContent, dryRun)
  }

  @Action(description = "Show file context around a line range or symbol to guide edits.")
  FileEditingTool.TargetedEditContext fileContext(
    String filePath,
    Integer startLine,
    Integer endLine,
    Integer padding,
    String symbol
  ) {
    int pad = padding != null ? padding.intValue() : 2
    if (symbol != null && symbol.trim()) {
      return fileEditingAgent.contextBySymbol(filePath, symbol, pad)
    }
    if (startLine == null || endLine == null) {
      throw new IllegalArgumentException("Start and end lines are required when symbol is not provided")
    }
    fileEditingAgent.contextByRange(filePath, startLine, endLine, pad)
  }

  @Action(description = "Restore a file from the most recent patch backup.")
  FileEditingTool.EditResult revertFromBackup(String filePath, boolean dryRun) {
    fileEditingAgent.revertLatestBackup(filePath, dryRun)
  }

  @Action(description = "Apply Search-and-Replace blocks to a file with backups.")
  FileEditingTool.SearchReplaceResult applySearchReplaceBlocks(String filePath, String blocksText, boolean dryRun) {
    fileEditingAgent.applySearchReplaceBlocks(filePath, blocksText, dryRun)
  }

  @Action(description = "Search the web for a given query")
  @JsonDeserialize(as = ArrayList.class, contentAs = WebSearchTool.SearchResult.class)
  List<WebSearchTool.SearchResult> search(String query) {
    webSearchAgent.search(query)
  }

  @Action(description = "Search repository files for a pattern.")
  @JsonDeserialize(as = ArrayList.class, contentAs = CodeSearchTool.SearchHit.class)
  List<CodeSearchTool.SearchHit> searchFiles(String query, List<String> paths, int contextLines, int limit) {
    codeSearchTool.search(query, paths, contextLines, limit)
  }

  protected String buildCraftCodePrompt(
    UserInput userInput,
    PersonaMode personaMode,
    String systemPromptOverride
  ) {
    def template = personaTemplate(personaMode)
    String extraSystem = systemPromptOverride?.trim()
    """
You are a repository-aware Groovy/Spring Boot coding assistant for a local-only CLI project.
Follow these rules:
- Indent with 2 spaces and keep lines under 120 characters.
- Use Groovy 5.0.3 with @CompileStatic when possible; avoid deprecated APIs.
- Map changes to existing files when possible and mention target file paths.
- Prefer Search and Replace Blocks for multi-file updates; avoid TODO placeholders.
- Include imports, validation, and error handling; favor testable designs and mention Spock coverage ideas.
${template.instructions}
${extraSystem ? "Additional system guidance: ${extraSystem}\n" : ""}
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

  protected String buildReviewPrompt(
    UserInput userInput,
    CodeSnippet codeSnippet,
    String systemPromptOverride
  ) {
    String extraSystem = systemPromptOverride?.trim()
    """
You are a repository code reviewer for a Groovy 5 / Spring Boot 3.5 local coding assistant.
Assess the proposal for correctness, repository fit, error handling, and testing strategy.
Ensure 2-space indentation, @CompileStatic suitability, and avoidance of deprecated APIs.
Reference likely target files or layers and call out missing Spock coverage.
Prioritize security flaws, unsafe file handling, missing validation, and unclear error paths.
Format findings as bullet lines using: [Severity] file:line - comment (severity: High/Medium/Low; file may be 'general').
${extraSystem ? "Additional system guidance: ${extraSystem}\n" : ""}
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

  protected String enforceReviewFormat(String review) {
    String limitedReview = enforceWordLimit(review, reviewWordCount)
    boolean hasFindings = limitedReview =~ /(?im)^Findings:/
    boolean hasTests = limitedReview =~ /(?im)^Tests:/
    if (hasFindings && hasTests) {
      return limitedReview
    }
    StringBuilder builder = new StringBuilder()
    if (!hasFindings) {
      builder.append("Findings:\n- ").append(limitedReview.trim()).append('\n')
    } else {
      builder.append(limitedReview.trim()).append('\n')
    }
    if (!hasTests) {
      builder.append("Tests:\n- Cover happy-path and failure-path behavior with Spock.")
    }
    enforceWordLimit(builder.toString().trim(), reviewWordCount)
  }

  protected String enforceWordLimit(String text, int limit) {
    if (text == null || limit <= 0) {
      return ""
    }
    String[] words = text.trim().split(/\s+/)
    if (words.length <= limit) {
      return text.trim()
    }
    String limited = words[0..<limit].join(" ")
    return limited + "..."
  }

  protected String enforceCodeFormat(String codeText) {
    if (codeText == null) {
      return ""
    }
    boolean hasPlan = codeText =~ /(?im)^Plan:/
    boolean hasImplementation = codeText =~ /(?im)^Implementation:/
    boolean hasNotes = codeText =~ /(?im)^Notes:/
    if (hasPlan && hasImplementation && hasNotes) {
      return codeText
    }
    """
Plan:
- See implementation below
Implementation:
${codeText.trim()}
Notes:
- Structured sections added automatically for consistency.
""".stripIndent().trim()
  }

  protected PersonaTemplate personaTemplate(PersonaMode mode) {
    switch (mode) {
      case PersonaMode.ARCHITECT:
        return new PersonaTemplate(
          Personas.ARCHITECT,
          "Architect Mode: Describe reasoning and trade-offs before code; include architecture notes."
        )
      case PersonaMode.REVIEWER:
        return new PersonaTemplate(
          Personas.REVIEWER,
          "Reviewer Mode: Be critical and flag security issues, unsafe IO, missing validation, and "
            + "deployment risks."
        )
      case PersonaMode.CODER:
      default:
        return new PersonaTemplate(
          Personas.CODER,
          "Coder Mode: Keep narration minimal; prefer code blocks and concise repository-scoped steps."
        )
    }
  }

  @Canonical
  @CompileStatic
  static class PersonaTemplate {
    RoleGoalBackstory persona
    String instructions
  }
}
