package se.alipsa.lca.api

import com.embabel.agent.api.common.Ai
import com.embabel.agent.domain.io.UserInput
import groovy.transform.CompileStatic
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import se.alipsa.lca.agent.CodingAssistantAgent
import se.alipsa.lca.agent.CodingAssistantAgent.ReviewedCodeSnippet

@RestController
@RequestMapping("/api/code")
@CompileStatic
class CodingAssistantController {

  private final CodingAssistantAgent codingAssistantAgent
  private final Ai ai

  CodingAssistantController(CodingAssistantAgent codingAssistantAgent, Ai ai) {
    this.codingAssistantAgent = codingAssistantAgent
    this.ai = ai
  }

  @PostMapping("/generateAndReview")
  ReviewedCodeSnippet generateAndReviewCode(@RequestBody String prompt) {
    UserInput userInput = new UserInput(prompt)
    def codeSnippet = codingAssistantAgent.craftCode(userInput, ai)
    codingAssistantAgent.reviewCode(userInput, codeSnippet, ai)
  }
}
