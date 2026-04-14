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

  def "parses findings with markdown bold severity"() {
    given:
    String review = """\
Findings:
- **[High]** src/App.groovy:42 - Null check missing
Tests:
- check nulls
"""

    when:
    ReviewSummary summary = ReviewParser.parse(review)

    then:
    summary.findings.size() == 1
    summary.findings[0].severity == ReviewSeverity.HIGH
    summary.findings[0].file == "src/App.groovy"
    summary.findings[0].line == 42
    summary.findings[0].comment == "Null check missing"
  }

  def "parses findings with lowercase severity"() {
    given:
    String review = """\
Findings:
- [high] src/App.groovy:10 - Missing validation
Tests:
- validate inputs
"""

    when:
    ReviewSummary summary = ReviewParser.parse(review)

    then:
    summary.findings.size() == 1
    summary.findings[0].severity == ReviewSeverity.HIGH
  }

  def "parses findings without line number"() {
    given:
    String review = """\
Findings:
- [Medium] src/App.groovy - Consider refactoring
Tests:
- refactor test
"""

    when:
    ReviewSummary summary = ReviewParser.parse(review)

    then:
    summary.findings.size() == 1
    summary.findings[0].severity == ReviewSeverity.MEDIUM
    summary.findings[0].file == "src/App.groovy"
    summary.findings[0].line == null
    summary.findings[0].comment == "Consider refactoring"
  }

  def "parses unstructured finding as Low severity"() {
    given:
    String review = """\
Findings:
- The error handling in Service.groovy could be improved
Tests:
- test errors
"""

    when:
    ReviewSummary summary = ReviewParser.parse(review)

    then:
    summary.findings.size() == 1
    summary.findings[0].severity == ReviewSeverity.LOW
    summary.findings[0].file == "general"
    summary.findings[0].comment == "The error handling in Service.groovy could be improved"
  }
}
