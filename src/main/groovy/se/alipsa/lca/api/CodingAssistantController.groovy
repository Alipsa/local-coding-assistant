package se.alipsa.lca.api

import com.embabel.agent.api.common.Ai
import com.embabel.agent.domain.io.UserInput
import groovy.transform.CompileStatic
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.validation.annotation.Validated
import se.alipsa.lca.agent.CodingAssistantAgent
import se.alipsa.lca.agent.CodingAssistantAgent.ReviewedCodeSnippet
import se.alipsa.lca.agent.PersonaMode
import se.alipsa.lca.shell.SessionState

@RestController
@RequestMapping("/api/code")
@Validated
@CompileStatic
class CodingAssistantController {

  private final CodingAssistantAgent codingAssistantAgent
  private final Ai ai
  private final SessionState sessionState

  CodingAssistantController(CodingAssistantAgent codingAssistantAgent, Ai ai, SessionState sessionState) {
    this.codingAssistantAgent = codingAssistantAgent
    this.ai = ai
    this.sessionState = sessionState
  }

  @PostMapping(path = "/generateAndReview", consumes = MediaType.APPLICATION_JSON_VALUE)
  ReviewedCodeSnippet generateAndReviewCode(@Valid @RequestBody CodeRequest request) {
    UserInput userInput = new UserInput(request.prompt)
    def settings = sessionState.getOrCreate("default")
    String systemPrompt = sessionState.systemPrompt(settings)
    def codeSnippet = codingAssistantAgent.craftCode(userInput, ai, PersonaMode.CODER, null, systemPrompt)
    codingAssistantAgent.reviewCode(userInput, codeSnippet, ai, null, systemPrompt)
  }

  @CompileStatic
  static class CodeRequest {
    @NotBlank
    String prompt
  }
}
