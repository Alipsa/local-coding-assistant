package se.alipsa.lca.review

import spock.lang.Specification

class ReviewParserSpec extends Specification {

  def "parses findings with file and line"() {
    given:
    String review = """\
Findings:
- [High] src/App.groovy:42 - Null check missing
- [Low] general - Add documentation
Tests:
- cover error path
"""

    when:
    ReviewSummary summary = ReviewParser.parse(review)

    then:
    summary.findings.size() == 2
    summary.findings[0].severity == ReviewSeverity.HIGH
    summary.findings[0].file == "src/App.groovy"
    summary.findings[0].line == 42
    summary.findings[1].file == "general"
    summary.tests == ["cover error path"]
  }
}
