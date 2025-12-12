package se.alipsa.lca.tools

import spock.lang.Specification

class TokenEstimatorSpec extends Specification {

  def "estimates tokens by whitespace"() {
    expect:
    new TokenEstimator().estimate("one two three") == 3
  }
}
