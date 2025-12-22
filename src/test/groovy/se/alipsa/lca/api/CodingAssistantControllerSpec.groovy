package se.alipsa.lca.api

import com.embabel.agent.api.common.Ai
import com.embabel.agent.domain.io.UserInput
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean
import se.alipsa.lca.agent.CodingAssistantAgent
import se.alipsa.lca.agent.PersonaMode
import se.alipsa.lca.agent.Personas
import se.alipsa.lca.shell.SessionState
import se.alipsa.lca.tools.AgentsMdProvider
import spock.lang.Specification

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class CodingAssistantControllerSpec extends Specification {

  CodingAssistantAgent agent = Mock(CodingAssistantAgent)
  Ai ai = Mock(Ai)
  AgentsMdProvider agentsMdProvider = Stub() {
    appendToSystemPrompt(_) >> { String base -> base }
  }
  SessionState sessionState =
    new SessionState("default-model", 0.7d, 0.35d, 0, "", true, false, "fallback", agentsMdProvider)
  LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean()
  MockMvc mvc

  def setup() {
    validator.afterPropertiesSet()
    mvc = MockMvcBuilders.standaloneSetup(new CodingAssistantController(agent, ai, sessionState))
      .setValidator(validator)
      .build()
  }

  def "generateAndReviewCode wires Ai through to agent actions"() {
    given:
    def crafted = new CodingAssistantAgent.CodeSnippet("code")
    def reviewed = new CodingAssistantAgent.ReviewedCodeSnippet(crafted, "review", Personas.REVIEWER)

    when:
    def response = mvc.perform(post("/api/code/generateAndReview")
      .contentType(MediaType.APPLICATION_JSON)
      .content('{"prompt":"write a search command"}'))

    then:
    response.andExpect(status().isOk()).andReturn()
    1 * agent.craftCode(
      { UserInput ui -> ui.getContent() == "write a search command" },
      ai,
      PersonaMode.CODER,
      null,
      ""
    ) >> crafted
    1 * agent.reviewCode(
      { UserInput ui -> ui.getContent() == "write a search command" },
      crafted,
      ai,
      null,
      ""
    ) >> reviewed
  }

  def "generateAndReviewCode rejects blank prompts"() {
    when:
    def response = mvc.perform(post("/api/code/generateAndReview")
      .contentType(MediaType.APPLICATION_JSON)
      .content('{"prompt":" "}'))

    then:
    response.andExpect(status().isBadRequest())
    0 * agent.craftCode(_, _, _, _, _)
  }
}
