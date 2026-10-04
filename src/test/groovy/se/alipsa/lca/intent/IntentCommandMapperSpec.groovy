package se.alipsa.lca.intent

import spock.lang.Specification

class IntentCommandMapperSpec extends Specification {

  def "routed run command cannot disable confirmation"() {
    given:
    IntentRouterResult result = new IntentRouterResult(
      [new IntentCommand('/run', [command: 'x', confirm: false])], 0.9d, 'run')

    expect:
    new IntentCommandMapper(null).map('Run x', result) == ['/run --command "x"']
  }

  def "routed commands discard safety overrides and preserve ordinary options"() {
    given:
    Map<String, Object> args = [reason: 'requested']
    args[flag] = false
    IntentRouterResult result = new IntentRouterResult(
      [new IntentCommand('/git-push', args)], 0.9d, 'push')

    expect:
    new IntentCommandMapper(null).map('Push changes', result) == ['/git-push --reason "requested"']

    where:
    flag << ['confirm', 'dry-run', 'dryRun', 'check', 'force', 'allow-secrets',
             'allowSecrets', 'secret-scan', 'secretScan']
  }

  def "routed revert cannot disable preview or confirmation"() {
    given:
    IntentRouterResult result = new IntentRouterResult(
      [new IntentCommand('/revert', ['file-path': 'src/Foo.groovy', 'dry-run': false, confirm: false])],
      0.9d, 'revert')

    expect:
    new IntentCommandMapper(null).map('Revert file', result) == ['/revert --file-path "src/Foo.groovy" --dry-run true']
  }


  def "maps review command with prompt and paths"() {
    given:
    IntentCommandMapper mapper = new IntentCommandMapper(null)
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
    IntentCommandMapper mapper = new IntentCommandMapper(null)
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
    IntentCommandMapper mapper = new IntentCommandMapper(null)
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

  def "detects PR number in review prompt and adds --pr flag"() {
    given:
    IntentCommandMapper mapper = new IntentCommandMapper(null)
    IntentRouterResult result = new IntentRouterResult(
      [new IntentCommand("/review", [:])],
      0.9d,
      "ok"
    )

    when:
    List<String> commands = mapper.map("review PR #16", result)

    then:
    commands.size() == 1
    commands[0].contains('--pr 16')
    commands[0].contains('--prompt "review PR #16"')
  }

  def "detects PR number without hash in review prompt"() {
    given:
    IntentCommandMapper mapper = new IntentCommandMapper(null)
    IntentRouterResult result = new IntentRouterResult(
      [new IntentCommand("/review", [:])],
      0.9d,
      "ok"
    )

    when:
    List<String> commands = mapper.map("review PR 42", result)

    then:
    commands.size() == 1
    commands[0].contains('--pr 42')
  }

  def "detects case-insensitive pr in review prompt"() {
    given:
    IntentCommandMapper mapper = new IntentCommandMapper(null)
    IntentRouterResult result = new IntentRouterResult(
      [new IntentCommand("/review", [:])],
      0.9d,
      "ok"
    )

    when:
    List<String> commands = mapper.map("review pr #7", result)

    then:
    commands.size() == 1
    commands[0].contains('--pr 7')
  }

  def "does not add --pr flag when no PR number in prompt"() {
    given:
    IntentCommandMapper mapper = new IntentCommandMapper(null)
    IntentRouterResult result = new IntentRouterResult(
      [new IntentCommand("/review", [:])],
      0.9d,
      "ok"
    )

    when:
    List<String> commands = mapper.map("review this code", result)

    then:
    commands.size() == 1
    !commands[0].contains('--pr ')
  }
}
