package se.alipsa.lca.intent

import spock.lang.Specification

class IntentCommandMapperSpec extends Specification {

  def "maps review command with prompt and paths"() {
    given:
    IntentCommandMapper mapper = new IntentCommandMapper()
    IntentRouterResult result = new IntentRouterResult(
      [new IntentCommand("/review", [path: "src/main/groovy"])],
      0.9d,
      "ok"
    )

    when:
    List<String> commands = mapper.map("Please review", result)

    then:
    commands == ['/review --prompt "Please review" --paths "src/main/groovy"']
  }

  def "maps plan command using original input when prompt missing"() {
    given:
    IntentCommandMapper mapper = new IntentCommandMapper()
    IntentRouterResult result = new IntentRouterResult(
      [new IntentCommand("/plan", [reason: "Follow up"])],
      0.8d,
      "plan"
    )

    when:
    List<String> commands = mapper.map("Plan next steps", result)

    then:
    commands == ['/plan --prompt "Plan next steps" --reason "Follow up"']
  }

  def "falls back to chat when required args are missing"() {
    given:
    IntentCommandMapper mapper = new IntentCommandMapper()
    IntentRouterResult result = new IntentRouterResult(
      [new IntentCommand("/run", [:])],
      0.9d,
      "run it"
    )

    when:
    List<String> commands = mapper.map("Run tests", result)

    then:
    commands.size() == 1
    commands[0].startsWith('/chat --prompt "Run tests"')
  }
}
