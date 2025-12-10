package se.alipsa.lca.api

import com.embabel.agent.api.common.Ai
import com.embabel.agent.domain.io.UserInput
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import se.alipsa.lca.agent.CodingAssistantAgent
import spock.lang.Specification

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class CodingAssistantControllerSpec extends Specification {

  CodingAssistantAgent agent = Mock(CodingAssistantAgent)
  Ai ai = Mock(Ai)
  MockMvc mvc = MockMvcBuilders.standaloneSetup(new CodingAssistantController(agent, ai)).build()

  def "generateAndReviewCode wires Ai through to agent actions"() {
    given:
    def crafted = new CodingAssistantAgent.CodeSnippet("code")
    def reviewed = new CodingAssistantAgent.ReviewedCodeSnippet(crafted, "review", null)
    agent.craftCode(_, ai) >> crafted
    agent.reviewCode(_, crafted, ai) >> reviewed

    when:
    def response = mvc.perform(post("/api/code/generateAndReview")
      .contentType(MediaType.TEXT_PLAIN)
      .content("write a search command"))

    then:
    response.andExpect(status().isOk()).andReturn()
    1 * agent.craftCode({ UserInput ui -> ui.getContent() == "write a search command" }, ai)
    1 * agent.reviewCode({ UserInput ui -> ui.getContent() == "write a search command" }, crafted, ai)
  }
}
