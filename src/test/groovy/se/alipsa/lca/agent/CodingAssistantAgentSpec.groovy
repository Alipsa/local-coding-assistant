package se.alipsa.lca.agent

import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.common.PromptRunner
import com.embabel.agent.domain.io.UserInput
import se.alipsa.lca.tools.FileEditingTool
import se.alipsa.lca.tools.WebSearchTool
import se.alipsa.lca.tools.CodeSearchTool
import spock.lang.Specification

class CodingAssistantAgentSpec extends Specification {

  FileEditingTool fileEditingTool = Mock(FileEditingTool)
  CodeSearchTool codeSearchTool = Mock(CodeSearchTool)
  WebSearchTool webSearchTool = Stub(WebSearchTool)
  CodingAssistantAgent agent = new CodingAssistantAgent(
    220,
    180,
    "test-model",
    0.65d,
    0.25d,
    fileEditingTool,
    webSearchTool,
    codeSearchTool
  )

  def "craftCode builds a repository-aware plan and output format"() {
    given:
    Ai ai = Mock(Ai)
    PromptRunner runner = Mock(PromptRunner)
    UserInput userInput = Stub(UserInput) {
      getContent() >> "Add a /search command that accepts file globs and returns context chunks."
    }
    def snippet = new CodingAssistantAgent.CodeSnippet("code")
    when:
    def result = agent.craftCode(userInput, ai)

    then:
    1 * ai.withLlm(agent.craftLlmOptions) >> runner
    1 * runner.withPromptContributor(Personas.CODER) >> runner
    1 * runner.createObject({
      it.contains("repository-aware") &&
      it.contains("Plan:") &&
      it.contains("Implementation:") &&
      it.contains("Notes:") &&
      it.contains("Indent with 2 spaces") &&
      it.contains("Search and Replace Blocks") &&
      it.contains("Spock") &&
      it.contains("Coder Mode")
    }, CodingAssistantAgent.CodeSnippet) >> snippet
    result == snippet
    agent.llmModel == "test-model"
    agent.craftTemperature == 0.65d
    agent.reviewTemperature == 0.25d
  }

  def "reviewCode enforces repository fit and testing considerations"() {
    given:
    Ai ai = Mock(Ai)
    PromptRunner runner = Mock(PromptRunner)
    UserInput userInput = Stub(UserInput) {
      getContent() >> "User wants to expand file editing support to patches."
    }
    def codeSnippet = new CodingAssistantAgent.CodeSnippet("Implementation: // code")
    when:
    def review = agent.reviewCode(userInput, codeSnippet, ai)

    then:
    1 * ai.withLlm(agent.reviewLlmOptions) >> runner
    1 * runner.withPromptContributor(Personas.REVIEWER) >> runner
    1 * runner.generateText({
      it.contains("repository code reviewer") &&
      it.contains("testing strategy") &&
      it.contains("2-space indentation") &&
      it.contains("Spock coverage") &&
      it.contains("User request:") &&
      it.contains("security")
    }) >> "High risk of errors in patch handling. Missing tests."
    review.review.contains("Findings:")
    review.review.contains("Tests:")
    review.reviewer == Personas.REVIEWER
  }

  def "craftCode rejects null Ai"() {
    when:
    agent.craftCode(new UserInput("hi"), null)

    then:
    thrown(NullPointerException)
  }

  def "reviewCode enforces word limit"() {
    given:
    Ai ai = Mock(Ai)
    PromptRunner runner = Mock(PromptRunner)
    UserInput userInput = new UserInput("Need review")
    def longReview = (1..400).collect { "word$it" }.join(" ")
    def snippet = new CodingAssistantAgent.CodeSnippet("Implementation: // code")
    when:
    def review = agent.reviewCode(userInput, snippet, ai)

    then:
    1 * ai.withLlm(agent.reviewLlmOptions) >> runner
    1 * runner.withPromptContributor(_) >> runner
    1 * runner.generateText(_) >> longReview
    review.review.split(/\s+/).length <= agent.reviewWordCount
    review.review.contains("Findings:")
    review.review.contains("Tests:")
  }

  def "craftCode adds structured sections when missing"() {
    given:
    Ai ai = Mock(Ai)
    PromptRunner runner = Mock(PromptRunner)
    UserInput userInput = new UserInput("Implement search command.")
    def snippet = new CodingAssistantAgent.CodeSnippet("println 'hi'")
    when:
    def result = agent.craftCode(userInput, ai)

    then:
    1 * ai.withLlm(agent.craftLlmOptions) >> runner
    1 * runner.withPromptContributor(Personas.CODER) >> runner
    1 * runner.createObject(_, CodingAssistantAgent.CodeSnippet) >> snippet
    result.text.contains("Plan:")
    result.text.contains("Implementation:")
    result.text.contains("Notes:")
  }

  def "craftCode supports architect mode with reasoning emphasis"() {
    given:
    Ai ai = Mock(Ai)
    PromptRunner runner = Mock(PromptRunner)
    UserInput userInput = new UserInput("Design a search context packer.")
    def snippet = new CodingAssistantAgent.CodeSnippet("Plan:\n- design\nImplementation:\n// code\nNotes:\n- none")
    when:
    def result = agent.craftCode(userInput, ai, PersonaMode.ARCHITECT)

    then:
    1 * ai.withLlm(agent.craftLlmOptions) >> runner
    1 * runner.withPromptContributor(Personas.ARCHITECT) >> runner
    1 * runner.createObject({
      it.contains("Architect Mode") &&
      it.contains("reasoning") &&
      it.contains("repository-aware")
    }, CodingAssistantAgent.CodeSnippet) >> snippet
    result.text.contains("Plan:")
  }

  def "applyPatch delegates to file editing tool"() {
    given:
    def patchResult = new FileEditingTool.PatchResult(true, false, false, List.of(), List.of())

    when:
    def result = agent.applyPatch("patch body", false)

    then:
    1 * fileEditingTool.applyPatch("patch body", false) >> patchResult
    result.is(patchResult)
  }

  def "replaceRange delegates with provided parameters"() {
    given:
    def editResult = new FileEditingTool.EditResult(true, false, "backup", "msg", "file")

    when:
    def result = agent.replaceRange("file.groovy", 1, 3, "new", true)

    then:
    1 * fileEditingTool.replaceRange("file.groovy", 1, 3, "new", true) >> editResult
    result.is(editResult)
  }

  def "fileContext prefers symbol when provided"() {
    given:
    def ctx = new FileEditingTool.TargetedEditContext("file", 2, 2, 10, "snippet")

    when:
    def result = agent.fileContext("file", null, null, 3, "sym")

    then:
    1 * fileEditingTool.contextBySymbol("file", "sym", 3) >> ctx
    result.is(ctx)
    0 * fileEditingTool.contextByRange(_, _, _, _)
  }

  def "fileContext uses range when symbol is absent"() {
    given:
    def ctx = new FileEditingTool.TargetedEditContext("file", 1, 4, 20, "snippet")

    when:
    def result = agent.fileContext("file", 1, 4, null, null)

    then:
    1 * fileEditingTool.contextByRange("file", 1, 4, 2) >> ctx
    result.is(ctx)
    0 * fileEditingTool.contextBySymbol(_, _, _)
  }

  def "revertFromBackup delegates to file editing tool"() {
    given:
    def editResult = new FileEditingTool.EditResult(true, false, "backup", "restored", "file")

    when:
    def result = agent.revertFromBackup("file", false)

    then:
    1 * fileEditingTool.revertLatestBackup("file", false) >> editResult
    result.is(editResult)
  }

  def "applySearchReplaceBlocks delegates"() {
    given:
    def srResult = new FileEditingTool.SearchReplaceResult(true, false, false, List.of(), "b", List.of())

    when:
    def result = agent.applySearchReplaceBlocks("f", "blocks", true)

    then:
    1 * fileEditingTool.applySearchReplaceBlocks("f", "blocks", true) >> srResult
    result.is(srResult)
  }

  def "searchFiles delegates"() {
    given:
    def hits = List.of(new CodeSearchTool.SearchHit("p", 1, 1, "snippet"))

    when:
    def result = agent.searchFiles("q", List.of("p"), 2, 5)

    then:
    1 * codeSearchTool.search("q", List.of("p"), 2, 5) >> hits
    result == hits
  }
}
