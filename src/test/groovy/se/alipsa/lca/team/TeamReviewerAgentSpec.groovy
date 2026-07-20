package se.alipsa.lca.team

import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.common.PromptRunner
import spock.lang.Specification

class TeamReviewerAgentSpec extends Specification {

  Ai ai = Mock()
  PromptRunner promptRunner = Mock()
  TeamSettings settings = new TeamSettings(
    true, "model", "model", "model", "model", 0.1d, 0.3d, 0.2d, 0.1d, 30L, 300L, 600L, 300L, true
  )
  TeamReviewerAgent reviewer = new TeamReviewerAgent(ai, settings)

  def "review reports no high-severity finding for a clean review"() {
    given:
    ai.withLlm(_) >> promptRunner
    promptRunner.withPromptContributor(_) >> promptRunner
    promptRunner.generateText(_) >> "Findings:\n- [Low] general - minor style nit\nTests:\n- none needed"

    when:
    TeamReviewResult result = reviewer.review(new TeamReviewRequest("Plan summary", "diff text", null))

    then:
    !result.hasHighSeverityFinding
    result.review.contains("Low")
  }

  def "review detects a High severity finding"() {
    given:
    ai.withLlm(_) >> promptRunner
    promptRunner.withPromptContributor(_) >> promptRunner
    promptRunner.generateText(_) >> "Findings:\n- [High] Foo.groovy:10 - null pointer risk\nTests:\n- add a null test"

    when:
    TeamReviewResult result = reviewer.review(new TeamReviewRequest("Plan summary", "diff text", null))

    then:
    result.hasHighSeverityFinding
  }

  def "review is case-insensitive when detecting High severity"() {
    given:
    ai.withLlm(_) >> promptRunner
    promptRunner.withPromptContributor(_) >> promptRunner
    promptRunner.generateText(_) >> "- [high] foo.groovy:1 - issue"

    when:
    TeamReviewResult result = reviewer.review(new TeamReviewRequest("Plan", "diff", null))

    then:
    result.hasHighSeverityFinding
  }

  def "an LLM failure is treated as no findings rather than propagating"() {
    given:
    ai.withLlm(_) >> { throw new RuntimeException("LLM unavailable") }

    when:
    TeamReviewResult result = reviewer.review(new TeamReviewRequest("Plan", "diff", null))

    then:
    !result.hasHighSeverityFinding
    result.review.contains("LLM unavailable")
  }
}
