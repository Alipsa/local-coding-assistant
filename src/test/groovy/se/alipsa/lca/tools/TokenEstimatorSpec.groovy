package se.alipsa.lca.tools

import spock.lang.Specification

class TokenEstimatorSpec extends Specification {

  def "estimates tokens by whitespace"() {
    expect:
    new TokenEstimator().estimate("one two three") == 3
  }

  def "returns zero for null or empty"() {
    expect:
    new TokenEstimator().estimate(null) == 0
    new TokenEstimator().estimate("") == 0
    new TokenEstimator().estimate("   ") == 0
  }
}
