package se.alipsa.lca.agent

import com.embabel.agent.api.common.Ai
import com.embabel.agent.domain.io.UserInput
import se.alipsa.lca.tools.FileEditingTool
import se.alipsa.lca.tools.WebSearchTool
import spock.lang.Specification

class CodingAssistantAgentSpec extends Specification {

  FileEditingTool fileEditingTool = Stub(FileEditingTool)
  WebSearchTool webSearchTool = Stub(WebSearchTool)
  CodingAssistantAgent agent = new CodingAssistantAgent(220, 180, fileEditingTool, webSearchTool)

  def "craftCode builds a repository-aware plan and output format"() {
    given:
    Ai ai = Mock(Ai)
    UserInput userInput = Stub(UserInput) {
      getContent() >> "Add a /search command that accepts file globs and returns context chunks."
    }
    def snippet = new CodingAssistantAgent.CodeSnippet("code")
    ai.withLlm(_) >> ai
    ai.withPromptContributor(_) >> ai
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
      it.contains("Spock")
    }, CodingAssistantAgent.CodeSnippet)
  }

  def "reviewCode enforces repository fit and testing considerations"() {
    given:
    Ai ai = Mock(Ai)
    UserInput userInput = Stub(UserInput) {
      getContent() >> "User wants to expand file editing support to patches."
    }
    def codeSnippet = new CodingAssistantAgent.CodeSnippet("Implementation: // code")
    ai.withAutoLlm() >> ai
    ai.withPromptContributor(_) >> ai
    ai.generateText(_) >> "review text"

    when:
    def review = agent.reviewCode(userInput, codeSnippet, ai)

    then:
    review.review == "review text"
    review.reviewer == Personas.REVIEWER
    1 * ai.generateText({
      it.contains("repository code reviewer") &&
      it.contains("testing strategy") &&
      it.contains("2-space indentation") &&
      it.contains("Spock coverage") &&
      it.contains("User request:")
    })
  }
}
