package se.alipsa.lca.agent

import com.embabel.agent.api.common.Ai
import com.embabel.common.ai.model.LlmOptions
import spock.lang.Specification

class ReviewAgentSpec extends Specification {

  def "review uses security persona when requested"() {
    given:
    CodingAssistantAgent assistant = Mock()
    ReviewAgent agent = new ReviewAgent(assistant)
    ReviewRequest request = new ReviewRequest("prompt", "payload", LlmOptions.withModel("m"), "system", true)
    def reviewed = new CodingAssistantAgent.ReviewedCodeSnippet(
      new CodingAssistantAgent.CodeSnippet("payload"),
      "Findings:\n- [High] general - issue\nTests:\n- test",
      Personas.SECURITY_REVIEWER
    )
    def capturedPersona = null
    def capturedOptions = null
    1 * assistant.reviewCode(
      _,
      _,
      _ as Ai,
      _ as LlmOptions,
      "system",
      _
    ) >> { userInput, snippet, aiArg, options, system, persona ->
      capturedOptions = options as LlmOptions
      capturedPersona = persona
      reviewed
    }

    when:
    def response = agent.review(request, Stub(Ai))

    then:
    response.review.contains("Findings:")
    capturedOptions?.modelSelectionCriteria?.name == "m"
    capturedPersona?.role == Personas.SECURITY_REVIEWER.role
  }
}
