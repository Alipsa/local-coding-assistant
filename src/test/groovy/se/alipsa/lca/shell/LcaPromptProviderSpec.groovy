package se.alipsa.lca.shell

import spock.lang.Specification

class LcaPromptProviderSpec extends Specification {

  def "prompt is lca"() {
    given:
    LcaPromptProvider provider = new LcaPromptProvider()

    expect:
    provider.prompt.toString() == "lca> "
  }
}
