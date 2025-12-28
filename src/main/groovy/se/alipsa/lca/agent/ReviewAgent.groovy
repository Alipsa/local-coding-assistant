package se.alipsa.lca.agent

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.common.Ai
import com.embabel.agent.domain.io.UserInput
import groovy.transform.CompileStatic
import org.springframework.context.annotation.Profile

import java.util.Objects

import se.alipsa.lca.agent.CodingAssistantAgent.CodeSnippet

@Agent(name = "lca-review", description = "Review code snippets from trigger-based requests")
@Profile("!test")
@CompileStatic
class ReviewAgent {

  private final CodingAssistantAgent codingAssistantAgent

  ReviewAgent(CodingAssistantAgent codingAssistantAgent) {
    this.codingAssistantAgent = Objects.requireNonNull(codingAssistantAgent, "codingAssistantAgent must not be null")
  }

  @AchievesGoal(description = "Review code based on a structured review request")
  @Action(canRerun = true, trigger = ReviewRequest)
  ReviewResponse review(ReviewRequest request, Ai ai) {
    Objects.requireNonNull(request, "request must not be null")
    Objects.requireNonNull(ai, "ai must not be null")
    def persona = request.security ? Personas.SECURITY_REVIEWER : Personas.REVIEWER
    def reviewed = codingAssistantAgent.reviewCode(
      new UserInput(request.prompt),
      new CodeSnippet(request.payload),
      ai,
      request.options,
      request.systemPrompt,
      persona
    )
    new ReviewResponse(reviewed.review)
  }
}
