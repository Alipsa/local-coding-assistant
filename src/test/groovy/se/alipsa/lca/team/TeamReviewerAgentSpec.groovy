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

  def "review detects an indented [High] bullet"() {
    given:
    ai.withLlm(_) >> promptRunner
    promptRunner.withPromptContributor(_) >> promptRunner
    promptRunner.generateText(_) >> "Findings:\n   - [High] Foo.groovy:10 - indented bullet\nTests:\n- none"

    when:
    TeamReviewResult result = reviewer.review(new TeamReviewRequest("Plan", "diff", null))

    then:
    result.hasHighSeverityFinding
  }

  def "review detects a numbered-list [High] finding"() {
    given:
    ai.withLlm(_) >> promptRunner
    promptRunner.withPromptContributor(_) >> promptRunner
    promptRunner.generateText(_) >> "Findings:\n1. [High] Foo.groovy:10 - numbered list\nTests:\n- none"

    when:
    TeamReviewResult result = reviewer.review(new TeamReviewRequest("Plan", "diff", null))

    then:
    result.hasHighSeverityFinding
  }

  def "review does not detect a High finding that isn't at the start of a line"() {
    given:
    ai.withLlm(_) >> promptRunner
    promptRunner.withPromptContributor(_) >> promptRunner
    promptRunner.generateText(_) >> "This mentions [High] mid-sentence but isn't a real finding line"

    when:
    TeamReviewResult result = reviewer.review(new TeamReviewRequest("Plan", "diff", null))

    then:
    !result.hasHighSeverityFinding
  }

  def "review sends the plan summary under a Plan summary label, not User request"() {
    given:
    String capturedPrompt = null
    ai.withLlm(_) >> promptRunner
    promptRunner.withPromptContributor(_) >> promptRunner
    promptRunner.generateText(_) >> { String prompt -> capturedPrompt = prompt; "Findings:\n- [Low] general - nit" }

    when:
    reviewer.review(new TeamReviewRequest("Refactor plan summary text", "diff", null))

    then:
    capturedPrompt.contains("Plan summary:\nRefactor plan summary text")
    !capturedPrompt.contains("User request:")
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
