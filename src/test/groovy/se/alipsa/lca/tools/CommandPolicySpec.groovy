package se.alipsa.lca.tools

import spock.lang.Specification

class CommandPolicySpec extends Specification {

  def "allowlist blocks commands not listed"() {
    given:
    CommandPolicy policy = new CommandPolicy("git*,mvn*", "")

    when:
    def decision = policy.evaluate("npm test")

    then:
    !decision.allowed
    decision.message.contains("allowlist")
  }

  def "denylist blocks matching command"() {
    given:
    CommandPolicy policy = new CommandPolicy("", "rm*")

    when:
    def decision = policy.evaluate("rm -rf ./build")

    then:
    !decision.allowed
    decision.message.contains("denylist")
  }

  def "allowlist permits matching prefix"() {
    given:
    CommandPolicy policy = new CommandPolicy("mvn*", "")

    when:
    def decision = policy.evaluate("./mvnw test")

    then:
    !decision.allowed
    decision.message.contains("allowlist")

    when:
    def allowed = policy.evaluate("mvn test")

    then:
    allowed.allowed
  }

  def "wildcard patterns respect command boundaries"() {
    given:
    CommandPolicy policy = new CommandPolicy("git*", "")

    when:
    def allowed = policy.evaluate("git push")

    then:
    allowed.allowed

    when:
    def blocked = policy.evaluate("gitty status")

    then:
    !blocked.allowed
    blocked.message.contains("allowlist")

    when:
    def denied = policy.evaluate("legitimate")

    then:
    !denied.allowed
  }
}
