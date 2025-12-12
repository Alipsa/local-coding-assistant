package se.alipsa.lca.agent

import com.embabel.agent.api.common.Ai
import com.embabel.agent.domain.io.UserInput
import se.alipsa.lca.tools.FileEditingTool
import se.alipsa.lca.tools.WebSearchTool
import se.alipsa.lca.tools.CodeSearchTool
import spock.lang.Specification

class CodingAssistantAgentSpec extends Specification {

  FileEditingTool fileEditingTool = Stub(FileEditingTool)
  CodeSearchTool codeSearchTool = Stub(CodeSearchTool)
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
    UserInput userInput = Stub(UserInput) {
      getContent() >> "Add a /search command that accepts file globs and returns context chunks."
    }
    def snippet = new CodingAssistantAgent.CodeSnippet("code")
    ai.withLlm({ it.is(agent.craftLlmOptions) }) >> ai
    ai.withPromptContributor(Personas.CODER) >> ai
    ai.createObject(_, CodingAssistantAgent.CodeSnippet) >> snippet

    when:
    def result = agent.craftCode(userInput, ai)

    then:
    result == snippet
    1 * ai.createObject({
      it.contains("repository-aware") &&
      it.contains("Plan:") &&
      it.contains("Implementation:") &&
      it.contains("Notes:") &&
      it.contains("Indent with 2 spaces") &&
      it.contains("Search and Replace Blocks") &&
      it.contains("Spock") &&
      it.contains("Coder Mode")
    }, CodingAssistantAgent.CodeSnippet)
    agent.llmModel == "test-model"
    agent.craftTemperature == 0.65d
    agent.reviewTemperature == 0.25d
  }

  def "reviewCode enforces repository fit and testing considerations"() {
    given:
    Ai ai = Mock(Ai)
    UserInput userInput = Stub(UserInput) {
      getContent() >> "User wants to expand file editing support to patches."
    }
    def codeSnippet = new CodingAssistantAgent.CodeSnippet("Implementation: // code")
    ai.withLlm({ it.is(agent.reviewLlmOptions) }) >> ai
    ai.withPromptContributor(Personas.REVIEWER) >> ai
    ai.generateText(_) >> "High risk of errors in patch handling. Missing tests."

    when:
    def review = agent.reviewCode(userInput, codeSnippet, ai)

    then:
    review.review.contains("Findings:")
    review.review.contains("Tests:")
    review.reviewer == Personas.REVIEWER
    1 * ai.generateText({
      it.contains("repository code reviewer") &&
      it.contains("testing strategy") &&
      it.contains("2-space indentation") &&
      it.contains("Spock coverage") &&
      it.contains("User request:") &&
      it.contains("security")
    })
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
    UserInput userInput = new UserInput("Need review")
    def longReview = (1..400).collect { "word$it" }.join(" ")
    def snippet = new CodingAssistantAgent.CodeSnippet("Implementation: // code")
    ai.withLlm({ it.is(agent.reviewLlmOptions) }) >> ai
    ai.withPromptContributor(_) >> ai
    ai.generateText(_) >> longReview

    when:
    def review = agent.reviewCode(userInput, snippet, ai)

    then:
    review.review.split(/\s+/).length <= agent.reviewWordCount
    review.review.contains("Findings:")
    review.review.contains("Tests:")
  }

  def "craftCode adds structured sections when missing"() {
    given:
    Ai ai = Mock(Ai)
    UserInput userInput = new UserInput("Implement search command.")
    def snippet = new CodingAssistantAgent.CodeSnippet("println 'hi'")
    ai.withLlm({ it.is(agent.craftLlmOptions) }) >> ai
    ai.withPromptContributor(Personas.CODER) >> ai
    ai.createObject(_, CodingAssistantAgent.CodeSnippet) >> snippet

    when:
    def result = agent.craftCode(userInput, ai)

    then:
    result.text.contains("Plan:")
    result.text.contains("Implementation:")
    result.text.contains("Notes:")
  }

  def "craftCode supports architect mode with reasoning emphasis"() {
    given:
    Ai ai = Mock(Ai)
    UserInput userInput = new UserInput("Design a search context packer.")
    def snippet = new CodingAssistantAgent.CodeSnippet("Plan:\n- design\nImplementation:\n// code\nNotes:\n- none")
    ai.withLlm({ it.is(agent.craftLlmOptions) }) >> ai
    ai.withPromptContributor(Personas.ARCHITECT) >> ai
    ai.createObject(_, CodingAssistantAgent.CodeSnippet) >> snippet

    when:
    def result = agent.craftCode(userInput, ai, PersonaMode.ARCHITECT)

    then:
    result.text.contains("Plan:")
    1 * ai.createObject({
      it.contains("Architect Mode") &&
      it.contains("reasoning") &&
      it.contains("repository-aware")
    }, CodingAssistantAgent.CodeSnippet)
  }

  def "applyPatch delegates to file editing tool"() {
    given:
    def patchResult = new FileEditingTool.PatchResult(true, false, false, List.of(), List.of())
    fileEditingTool.applyPatch("patch body", false) >> patchResult

    when:
    def result = agent.applyPatch("patch body", false)

    then:
    result.is(patchResult)
    1 * fileEditingTool.applyPatch("patch body", false)
  }

  def "replaceRange delegates with provided parameters"() {
    given:
    def editResult = new FileEditingTool.EditResult(true, false, "backup", "msg", "file")
    fileEditingTool.replaceRange("file.groovy", 1, 3, "new", true) >> editResult

    when:
    def result = agent.replaceRange("file.groovy", 1, 3, "new", true)

    then:
    result.is(editResult)
    1 * fileEditingTool.replaceRange("file.groovy", 1, 3, "new", true)
  }

  def "fileContext prefers symbol when provided"() {
    given:
    def ctx = new FileEditingTool.TargetedEditContext("file", 2, 2, 10, "snippet")
    fileEditingTool.contextBySymbol("file", "sym", 3) >> ctx

    when:
    def result = agent.fileContext("file", null, null, 3, "sym")

    then:
    result.is(ctx)
    1 * fileEditingTool.contextBySymbol("file", "sym", 3)
    0 * fileEditingTool.contextByRange(_, _, _, _)
  }

  def "fileContext uses range when symbol is absent"() {
    given:
    def ctx = new FileEditingTool.TargetedEditContext("file", 1, 4, 20, "snippet")
    fileEditingTool.contextByRange("file", 1, 4, 2) >> ctx

    when:
    def result = agent.fileContext("file", 1, 4, null, null)

    then:
    result.is(ctx)
    1 * fileEditingTool.contextByRange("file", 1, 4, 2)
    0 * fileEditingTool.contextBySymbol(_, _, _)
  }

  def "revertFromBackup delegates to file editing tool"() {
    given:
    def editResult = new FileEditingTool.EditResult(true, false, "backup", "restored", "file")
    fileEditingTool.revertLatestBackup("file", false) >> editResult

    when:
    def result = agent.revertFromBackup("file", false)

    then:
    result.is(editResult)
    1 * fileEditingTool.revertLatestBackup("file", false)
  }

  def "applySearchReplaceBlocks delegates"() {
    given:
    def srResult = new FileEditingTool.SearchReplaceResult(true, false, false, List.of(), "b", List.of())
    fileEditingTool.applySearchReplaceBlocks("f", "blocks", true) >> srResult

    when:
    def result = agent.applySearchReplaceBlocks("f", "blocks", true)

    then:
    result.is(srResult)
    1 * fileEditingTool.applySearchReplaceBlocks("f", "blocks", true)
  }

  def "searchFiles delegates"() {
    given:
    def hits = List.of(new CodeSearchTool.SearchHit("p", 1, 1, "snippet"))
    codeSearchTool.search("q", List.of("p"), 2, 5) >> hits

    when:
    def result = agent.searchFiles("q", List.of("p"), 2, 5)

    then:
    result == hits
    1 * codeSearchTool.search("q", List.of("p"), 2, 5)
  }
}
