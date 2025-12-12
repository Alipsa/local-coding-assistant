package se.alipsa.lca.shell

import se.alipsa.lca.review.ReviewFinding
import se.alipsa.lca.review.ReviewSeverity
import se.alipsa.lca.review.ReviewSummary
import spock.lang.Specification

class RenderReviewSpec extends Specification {

  def "renders only findings above threshold"() {
    given:
    ReviewSummary summary = new ReviewSummary(
      [
        new ReviewFinding(ReviewSeverity.LOW, "file", null, "note"),
        new ReviewFinding(ReviewSeverity.HIGH, "file", 3, "critical")
      ],
      List.of("test"),
      ""
    )

    when:
    def text = ShellCommands.renderReview(summary, ReviewSeverity.HIGH, false)

    then:
    !text.contains("Low")
    text.contains("High")
    text.contains("Tests:")
  }
}
