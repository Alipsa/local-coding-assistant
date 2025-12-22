package se.alipsa.lca.tools

import spock.lang.Specification

class ContextBudgetManagerSpec extends Specification {

  def "trims context beyond budget"() {
    given:
    ContextBudgetManager manager = new ContextBudgetManager(20, 0, new TokenEstimator(), 2, -1)
    String text = "1234567890123456789012345"
    def hits = [new CodeSearchTool.SearchHit("f", 1, 1, "snippet")]

    when:
    def result = manager.applyBudget(text, hits)

    then:
    result.truncated
    result.text.length() <= 20 + 3
  }

  def "keeps most relevant hits within budget"() {
    given:
    ContextBudgetManager manager = new ContextBudgetManager(50, 5, new TokenEstimator(), 2, -1)
    def hits = [
      new CodeSearchTool.SearchHit("src/main/App.groovy", 10, 1, "a" * 30),
      new CodeSearchTool.SearchHit("src/test/AppSpec.groovy", 5, 1, "b" * 30)
    ]
    String text = (hits.collect { "${it.path}:${it.line}\n${it.snippet}" }.join("\n\n"))

    when:
    def result = manager.applyBudget(text, hits)

    then:
    result.included.size() == 1
    result.included.first().path.contains("src/main")
  }
}
