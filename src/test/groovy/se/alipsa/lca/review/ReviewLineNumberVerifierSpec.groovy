package se.alipsa.lca.review

import spock.lang.Specification

class ReviewLineNumberVerifierSpec extends Specification {

  def "finding citing a real file and in-range line is left untouched"() {
    given:
    ReviewSummary summary = new ReviewSummary(
      [new ReviewFinding(ReviewSeverity.HIGH, "src/App.groovy", 10, "issue")],
      [],
      "raw"
    )

    when:
    ReviewSummary result = ReviewLineNumberVerifier.verify(summary, ["src/App.groovy": 42])

    then:
    result.findings[0].comment == "issue"
  }

  def "finding citing an out-of-range line is annotated UNVERIFIED"() {
    given:
    ReviewSummary summary = new ReviewSummary(
      [new ReviewFinding(ReviewSeverity.HIGH, "src/App.groovy", 100, "issue")],
      [],
      "raw"
    )

    when:
    ReviewSummary result = ReviewLineNumberVerifier.verify(summary, ["src/App.groovy": 42])

    then:
    result.findings[0].comment == "[UNVERIFIED] issue"
  }

  def "AppConfig.groovy does not match a Config.groovy key"() {
    given:
    ReviewSummary summary = new ReviewSummary(
      [new ReviewFinding(ReviewSeverity.HIGH, "AppConfig.groovy", 5, "issue")],
      [],
      "raw"
    )

    when:
    ReviewSummary result = ReviewLineNumberVerifier.verify(summary, ["Config.groovy": 3])

    then: "no fileLineCounts entry matches, so the finding is left alone, not misjudged wrong"
    result.findings[0].comment == "issue"
  }

  def "a bare filename citation matches a full relative path key"() {
    given:
    ReviewSummary summary = new ReviewSummary(
      [new ReviewFinding(ReviewSeverity.HIGH, "ReviewParser.groovy", 100, "issue")],
      [],
      "raw"
    )

    when:
    ReviewSummary result = ReviewLineNumberVerifier.verify(
      summary, ["src/main/groovy/se/alipsa/lca/review/ReviewParser.groovy": 42]
    )

    then:
    result.findings[0].comment == "[UNVERIFIED] issue"
  }

  def "empty fileLineCounts returns the summary unchanged"() {
    given:
    ReviewSummary summary = new ReviewSummary(
      [new ReviewFinding(ReviewSeverity.HIGH, "src/App.groovy", 999, "issue")],
      [],
      "raw"
    )

    when:
    ReviewSummary result = ReviewLineNumberVerifier.verify(summary, [:])

    then: "nothing was sent as file content; annotating everything would be a false-positive flood"
    result.is(summary)
  }

  def "a non-empty fileLineCounts missing the cited file leaves that finding untouched"() {
    given:
    ReviewSummary summary = new ReviewSummary(
      [new ReviewFinding(ReviewSeverity.HIGH, "Other.groovy", 999, "issue")],
      [],
      "raw"
    )

    when:
    ReviewSummary result = ReviewLineNumberVerifier.verify(summary, ["src/App.groovy": 42])

    then: "not sent in full is not evidence the finding is wrong"
    result.findings[0].comment == "issue"
  }

  def "a finding with file general or a null line is left untouched"() {
    given:
    ReviewSummary summary = new ReviewSummary(
      [
        new ReviewFinding(ReviewSeverity.LOW, "general", null, "general note"),
        new ReviewFinding(ReviewSeverity.MEDIUM, "src/App.groovy", null, "no line given")
      ],
      [],
      "raw"
    )

    when:
    ReviewSummary result = ReviewLineNumberVerifier.verify(summary, ["src/App.groovy": 42])

    then:
    result.findings[0].comment == "general note"
    result.findings[1].comment == "no line given"
  }
}
