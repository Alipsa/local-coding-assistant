package se.alipsa.lca.api

import spock.lang.Specification

class OidcTokenValidatorSpec extends Specification {

  def "rejects non-https jwks uri"() {
    when:
    new OidcTokenValidator("issuer", "audience", null, "http://example.com/jwks", 1000L)

    then:
    IllegalStateException ex = thrown()
    ex.message.contains("HTTPS")
  }
}
