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

@RestController
@RequestMapping("/api/code")
@Validated
@CompileStatic
class CodingAssistantController {

  private final CodingAssistantAgent codingAssistantAgent
  private final Ai ai

  CodingAssistantController(CodingAssistantAgent codingAssistantAgent, Ai ai) {
    this.codingAssistantAgent = codingAssistantAgent
    this.ai = ai
  }

  @PostMapping(path = "/generateAndReview", consumes = MediaType.APPLICATION_JSON_VALUE)
  ReviewedCodeSnippet generateAndReviewCode(@Valid @RequestBody CodeRequest request) {
    UserInput userInput = new UserInput(request.prompt)
    def codeSnippet = codingAssistantAgent.craftCode(userInput, ai)
    codingAssistantAgent.reviewCode(userInput, codeSnippet, ai)
  }

  @CompileStatic
  static class CodeRequest {
    @NotBlank
    String prompt
  }
}
