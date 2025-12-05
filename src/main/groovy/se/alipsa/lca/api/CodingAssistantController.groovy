package se.alipsa.lca.api

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import se.alipsa.lca.agent.CodingAssistantAgent
import se.alipsa.lca.agent.CodingAssistantAgent.ReviewedCodeSnippet
import com.embabel.agent.domain.io.UserInput

@RestController
@RequestMapping("/api/code")
class CodingAssistantController {

    private final CodingAssistantAgent codingAssistantAgent

    CodingAssistantController(CodingAssistantAgent codingAssistantAgent) {
        this.codingAssistantAgent = codingAssistantAgent
    }

    @PostMapping("/generateAndReview")
    ReviewedCodeSnippet generateAndReviewCode(@RequestBody String prompt) {
        def userInput = new UserInput(prompt)
        def codeSnippet = codingAssistantAgent.craftCode(userInput, null) // Ai is passed within the agent's action method
        codingAssistantAgent.reviewCode(userInput, codeSnippet, null) // Ai is passed within the agent's action method
    }
}
