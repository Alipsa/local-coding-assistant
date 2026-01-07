package se.alipsa.lca.intent

import se.alipsa.lca.shell.SessionState
import spock.lang.Specification

class ContextResolverSpec extends Specification {

  SessionState sessionState
  ContextResolver resolver

  def setup() {
    sessionState = Mock(SessionState)
    resolver = new ContextResolver(sessionState)
  }

  def "resolve returns no references when input has no pronouns"() {
    given:
    String input = "/review --paths src/Main.groovy --prompt check for bugs"

    when:
    ContextResolver.ResolutionResult result = resolver.resolve(input, "default")

    then:
    !result.hasReferences
    result.resolvedPaths.isEmpty()
    result.originalInput == input
  }

  def "resolve returns no references when no recent files tracked"() {
    given:
    String input = "review that file"
    sessionState.getRecentFilePaths("default", 5) >> []

    when:
    ContextResolver.ResolutionResult result = resolver.resolve(input, "default")

    then:
    !result.hasReferences
    result.resolvedPaths.isEmpty()
  }

  def "resolve returns most recent file for simple 'review it' reference"() {
    given:
    String input = "review it"
    sessionState.getRecentFilePaths("default", 5) >> [
      "src/Main.groovy",
      "src/Utils.groovy"
    ]

    when:
    ContextResolver.ResolutionResult result = resolver.resolve(input, "default")

    then:
    result.hasReferences
    result.resolvedPaths == ["src/Main.groovy"]
  }

  def "resolve returns most recent file for 'check that file'"() {
    given:
    String input = "check that file"
    sessionState.getRecentFilePaths("default", 5) >> [
      "src/Controller.groovy",
      "src/Service.groovy"
    ]

    when:
    ContextResolver.ResolutionResult result = resolver.resolve(input, "default")

    then:
    result.hasReferences
    result.resolvedPaths == ["src/Controller.groovy"]
  }

  def "resolve matches file by type when input mentions 'controller'"() {
    given:
    String input = "review the controller"
    sessionState.getRecentFilePaths("default", 5) >> [
      "src/UserService.groovy",
      "src/UserController.groovy",
      "src/Repository.groovy"
    ]

    when:
    ContextResolver.ResolutionResult result = resolver.resolve(input, "default")

    then:
    result.hasReferences
    result.resolvedPaths == ["src/UserController.groovy"]
  }

  def "resolve matches file by type when input mentions 'test'"() {
    given:
    String input = "fix that test"
    sessionState.getRecentFilePaths("default", 5) >> [
      "src/UserController.groovy",
      "src/UserControllerSpec.groovy"
    ]

    when:
    ContextResolver.ResolutionResult result = resolver.resolve(input, "default")

    then:
    result.hasReferences
    result.resolvedPaths == ["src/UserControllerSpec.groovy"]
  }

  def "resolve handles null input"() {
    when:
    ContextResolver.ResolutionResult result = resolver.resolve(null, "default")

    then:
    !result.hasReferences
    result.resolvedPaths.isEmpty()
  }

  def "resolve handles empty input"() {
    when:
    ContextResolver.ResolutionResult result = resolver.resolve("", "default")

    then:
    !result.hasReferences
    result.resolvedPaths.isEmpty()
  }
}
