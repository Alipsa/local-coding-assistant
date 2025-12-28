package se.alipsa.lca.shell

import org.springframework.shell.command.CommandRegistration
import org.springframework.shell.result.CommandNotFoundMessageProvider
import spock.lang.Specification

class CommandNotFoundSuggestionProviderSpec extends Specification {

  def "suggests closest matching command"() {
    given:
    def provider = new CommandNotFoundSuggestionProvider()
    Map<String, CommandRegistration> registrations = [
      "/version": null,
      "/review": null,
      "/plan": null
    ] as Map<String, CommandRegistration>
    def context = CommandNotFoundMessageProvider.contextOf(
      new RuntimeException("missing"),
      List.of("/verion"),
      registrations,
      "/verion"
    )

    when:
    String message = provider.apply(context)

    then:
    message == "The command /verion does not exist. Did you mean /version?"
  }

  def "returns base message when no close match exists"() {
    given:
    def provider = new CommandNotFoundSuggestionProvider()
    Map<String, CommandRegistration> registrations = [
      "/review": null,
      "/plan": null
    ] as Map<String, CommandRegistration>
    def context = CommandNotFoundMessageProvider.contextOf(
      new RuntimeException("missing"),
      List.of("/xyz"),
      registrations,
      "/xyz"
    )

    when:
    String message = provider.apply(context)

    then:
    message == "The command /xyz does not exist."
  }
}
