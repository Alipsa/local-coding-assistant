package se.alipsa.lca.validation

import se.alipsa.lca.intent.ContextResolver
import se.alipsa.lca.intent.ContextResolver.ResolutionResult
import spock.lang.Specification
import spock.lang.Subject

class RequestValidatorSpec extends Specification {

  ValidationSettings settings
  ContextResolver contextResolver
  @Subject
  RequestValidator validator

  def setup() {
    settings = new ValidationSettings(
      enabled: true,
      skipInBatch: true,
      minPromptLength: 15,
      maxClarificationRounds: 2,
      useContextResolver: true
    )
    contextResolver = Mock(ContextResolver)
    validator = new RequestValidator(settings, contextResolver)
  }

  def "validate flags 'write a query' as ambiguous"() {
    when:
    def result = validator.validate("write a query", "default")

    then:
    result.needsClarification
    result.reason == "Generic request needs specifics"
    result.detectedType == "query"
    result.questions.size() == 4
    result.questions.contains("SQL query for database")
  }

  def "validate flags 'create a test' as ambiguous"() {
    when:
    def result = validator.validate("create a test", "default")

    then:
    result.needsClarification
    result.reason == "Generic request needs specifics"
    result.detectedType == "test"
    result.questions.size() == 4
    result.questions.contains("Unit test (Spock)")
  }

  def "validate accepts 'Create SQL query in UserRepository.groovy'"() {
    when:
    def result = validator.validate("Create SQL query in UserRepository.groovy to fetch users", "default")

    then:
    !result.needsClarification
  }

  def "validate accepts request with explicit file path"() {
    when:
    def result = validator.validate("Add a new method to src/main/groovy/MyClass.groovy", "default")

    then:
    !result.needsClarification
  }

  def "validate accepts request with technology context"() {
    when:
    def result = validator.validate("write a SQL query for the users table", "default")

    then:
    0 * contextResolver.resolve(_, _)
    !result.needsClarification
  }

  def "validate flags very short request as too short"() {
    when:
    def result = validator.validate("write test", "default")

    then:
    result.needsClarification
    result.reason == "Request too short"
  }

  def "validate accepts longer well-formed request"() {
    when:
    def result = validator.validate("Create a Spock test for the UserService class to test login validation", "default")

    then:
    0 * contextResolver.resolve(_, _)
    !result.needsClarification
  }

  def "validate detects ambiguous keyword 'query' without qualifier"() {
    when:
    def result = validator.validate("I need a query to find users by their status", "default")

    then:
    0 * contextResolver.resolve(_, _)
    result.needsClarification
    result.reason == "Ambiguous term needs specification"
    result.detectedType == "query"
  }

  def "validate detects ambiguous keyword 'service' without qualifier"() {
    when:
    def result = validator.validate("Please create a service for user management", "default")

    then:
    0 * contextResolver.resolve(_, _)
    result.needsClarification
    result.reason == "Ambiguous term needs specification"
    result.detectedType == "service"
  }

  def "validate accepts query with SQL qualifier"() {
    when:
    def result = validator.validate("I need a SQL query to find users by their status", "default")

    then:
    0 * contextResolver.resolve(_, _)
    !result.needsClarification
  }

  def "validate accepts service with Spring qualifier"() {
    when:
    def result = validator.validate("Please create a Spring service for user management", "default")

    then:
    0 * contextResolver.resolve(_, _)
    !result.needsClarification
  }

  def "validate detects missing file context"() {
    when:
    def result = validator.validate("Add a method to calculate the total", "default")

    then:
    0 * contextResolver.resolve(_, _)
    result.needsClarification
    result.reason == "Request lacks context"
    result.detectedType == "method"
  }

  def "validate resolves file context using ContextResolver"() {
    when:
    def result = validator.validate("Modify that file to add error handling", "default")

    then:
    1 * contextResolver.resolve(_, _) >> new ResolutionResult("Modify that file to add error handling", ["UserService.groovy"], true)
    !result.needsClarification
  }

  def "validate accepts request with location hint 'in the'"() {
    when:
    def result = validator.validate("Add a method in the UserService class", "default")

    then:
    0 * contextResolver.resolve(_, _)
    println("Result: needsClarification=${result.needsClarification}, reason=${result.reason}")
    !result.needsClarification
  }

  def "validate accepts request with location hint 'in package'"() {
    when:
    def result = validator.validate("Create a new controller in package se.alipsa.lca.web", "default")

    then:
    0 * contextResolver.resolve(_, _)
    !result.needsClarification
  }

  def "validate returns OK when validation is disabled"() {
    given:
    settings.enabled = false

    when:
    def result = validator.validate("x", "default")

    then:
    !result.needsClarification
  }

  def "validate handles null prompt"() {
    when:
    def result = validator.validate(null, "default")

    then:
    result.needsClarification
    result.reason == "Empty request"
  }

  def "validate handles empty prompt"() {
    when:
    def result = validator.validate("   ", "default")

    then:
    result.needsClarification
    result.reason == "Empty request"
  }

  def "validate accepts request with 'make' verb and file context"() {
    when:
    def result = validator.validate("make a controller in UserController.groovy", "default")

    then:
    println("Result: needsClarification=${result.needsClarification}, reason=${result.reason}")
    !result.needsClarification
  }

  def "validate flags 'build a script' without technology"() {
    when:
    def result = validator.validate("build a script for me", "default")

    then:
    0 * contextResolver.resolve(_, _)
    result.needsClarification
    result.detectedType == "script"
  }

  def "validate accepts 'build a bash script'"() {
    when:
    def result = validator.validate("build a bash script to clean logs", "default")

    then:
    0 * contextResolver.resolve(_, _)
    !result.needsClarification
  }

  def "validate accepts request with directory reference"() {
    when:
    def result = validator.validate("Create a new file in directory src/main/resources", "default")

    then:
    0 * contextResolver.resolve(_, _)
    !result.needsClarification
  }

  def "validate flags endpoint without qualifier"() {
    when:
    def result = validator.validate("Please add an endpoint for user login", "default")

    then:
    0 * contextResolver.resolve(_, _)
    result.needsClarification
    result.detectedType == "endpoint"
  }

  def "validate accepts REST endpoint with qualifier"() {
    when:
    def result = validator.validate("Please add a REST endpoint for user login", "default")

    then:
    0 * contextResolver.resolve(_, _)
    !result.needsClarification
  }

}
