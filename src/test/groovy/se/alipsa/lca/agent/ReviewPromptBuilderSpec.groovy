package se.alipsa.lca.agent

import spock.lang.Specification

class ReviewPromptBuilderSpec extends Specification {

  def "buildPrReviewPrompt includes the quote-the-exact-line grounding instruction"() {
    when:
    String prompt = ReviewPromptBuilder.buildPrReviewPrompt("code", "request", null, false)

    then:
    prompt.contains("Quote the exact line you are citing")
  }

  def "the 4-arg overload delegates and produces the same output as the 6-arg overload"() {
    when:
    String fourArg = ReviewPromptBuilder.buildPrReviewPrompt("code", "request", null, false)
    String sixArg = ReviewPromptBuilder.buildPrReviewPrompt("code", "request", null, false, "User request", null)

    then:
    fourArg == sixArg
  }

  def "the 5-arg overload delegates with the given request label"() {
    when:
    String prompt = ReviewPromptBuilder.buildPrReviewPrompt("code", "summary", null, false, "Plan summary")

    then:
    prompt.contains("Plan summary:\nsummary")
    !prompt.contains("User request:")
  }

  def "previousFindings is included when present, directly above the request label"() {
    when:
    String prompt = ReviewPromptBuilder.buildPrReviewPrompt(
      "code", "verify these", null, false, "User request", "- [High] Foo.groovy:1 - issue"
    )

    then:
    prompt.contains("Previous findings to verify:\n- [High] Foo.groovy:1 - issue")
  }

  def "previousFindings is omitted entirely when null"() {
    when:
    String prompt = ReviewPromptBuilder.buildPrReviewPrompt("code", "request", null, false, "User request", null)

    then:
    !prompt.contains("Previous findings to verify")
  }
}
