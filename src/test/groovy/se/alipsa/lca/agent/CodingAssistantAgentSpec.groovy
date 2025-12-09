package se.alipsa.lca.agent

import com.embabel.agent.api.common.Ai
import com.embabel.agent.domain.io.UserInput
import se.alipsa.lca.tools.FileEditingTool
import se.alipsa.lca.tools.WebSearchTool
import spock.lang.Specification

class CodingAssistantAgentSpec extends Specification {

  FileEditingTool fileEditingTool = Stub(FileEditingTool)
  WebSearchTool webSearchTool = Stub(WebSearchTool)
  CodingAssistantAgent agent = new CodingAssistantAgent(
    220,
    180,
    "test-model",
    0.65d,
    0.25d,
    fileEditingTool,
    webSearchTool
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
    review.review.split(/\\s+/).length <= agent.reviewWordCount
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
}
