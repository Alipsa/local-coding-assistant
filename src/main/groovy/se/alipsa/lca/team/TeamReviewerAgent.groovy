package se.alipsa.lca.team

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.common.Ai
import com.embabel.common.ai.model.LlmOptions
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import se.alipsa.lca.agent.Personas
import se.alipsa.lca.agent.ReviewPromptBuilder

import java.time.Duration
import java.util.regex.Pattern

/**
 * QA pass for the agent team pipeline. Reuses the same cross-file-analysis prompt depth as
 * the {@code /review} command's PR review path ({@link ReviewPromptBuilder}), since that path
 * is the one role that already produced consistently useful output.
 */
@Agent(name = "lca-team-reviewer", description = "Review the team's implementation output for correctness and quality")
@Profile("!test")
@CompileStatic
class TeamReviewerAgent {

  private static final Logger log = LoggerFactory.getLogger(TeamReviewerAgent)
  private static final Pattern HIGH_SEVERITY_PATTERN = Pattern.compile(
    /(?im)^[-*]?\s*\[High]/
  )

  private final Ai ai
  private final TeamSettings settings

  TeamReviewerAgent(Ai ai, TeamSettings settings) {
    this.ai = ai
    this.settings = settings
  }

  @AchievesGoal(description = "Review the team's implementation output for correctness and quality")
  @Action(canRerun = true, trigger = TeamReviewRequest)
  TeamReviewResult review(TeamReviewRequest request) {
    String prompt = ReviewPromptBuilder.buildPrReviewPrompt(
      request?.diffText ?: "", request?.planSummary ?: "", request?.sessionSystemPrompt, false
    )

    LlmOptions options = LlmOptions.withModel(settings.reviewerModel)
      .withTemperature(settings.reviewerTemperature)
      .withTimeout(Duration.ofSeconds(settings.reviewerTimeoutSeconds))

    try {
      String review = ai.withLlm(options)
        .withPromptContributor(Personas.REVIEWER)
        .generateText(prompt)
      boolean highSeverity = review != null && HIGH_SEVERITY_PATTERN.matcher(review).find()
      new TeamReviewResult(review ?: "", highSeverity)
    } catch (Exception e) {
      log.warn("Team QA review failed; treating as no findings", e)
      new TeamReviewResult("QA review could not be completed: ${e.message}".toString(), false)
    }
  }
}
