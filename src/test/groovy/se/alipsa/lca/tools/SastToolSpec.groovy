package se.alipsa.lca.tools

import spock.lang.Specification

class SastToolSpec extends Specification {

  def "parses semgrep style json output"() {
    given:
    CommandRunner runner = Stub() {
      run(_ as List<String>, _, _) >> new CommandRunner.CommandResult(true, false, 0, """
{
  "results": [
    {
      "check_id": "RULE1",
      "path": "src/App.groovy",
      "start": {"line": 12},
      "extra": {"severity": "HIGH"}
    }
  ]
}
""", false, null)
    }
    CommandPolicy policy = new CommandPolicy("", "")
    SastTool tool = new SastTool(runner, policy, "semgrep --json {paths}", 1000L, 2000)

    when:
    def result = tool.run(["src/App.groovy"])

    then:
    result.success
    result.findings.size() == 1
    result.findings.first().severity == "HIGH"
    result.findings.first().rule == "RULE1"
  }

  def "reports when command is not configured"() {
    given:
    CommandRunner runner = Stub()
    CommandPolicy policy = new CommandPolicy("", "")
    SastTool tool = new SastTool(runner, policy, "", 1000L, 2000)

    when:
    def result = tool.run(["src/App.groovy"])

    then:
    !result.ran
    result.message.contains("not configured")
  }

  def "falls back to line parsing when JSON is malformed"() {
    given:
    CommandRunner runner = Stub() {
      run(_ as List<String>, _, _) >> new CommandRunner.CommandResult(true, false, 0, """
{ "malformed": "json without closing brace
warning: possible issue at line 5
error: critical issue found
""", false, null)
    }
    CommandPolicy policy = new CommandPolicy("", "")
    SastTool tool = new SastTool(runner, policy, "sast-tool {paths}", 1000L, 2000)

    when:
    def result = tool.run(["src/App.groovy"])

    then:
    result.success
    result.findings.size() == 3
    result.findings.every { it.severity == "INFO" }
    result.findings.any { it.rule.contains("malformed") }
  }

  def "rejects control characters in arguments"() {
    given:
    CommandRunner runner = Mock()
    CommandPolicy policy = new CommandPolicy("", "")
    SastTool tool = new SastTool(runner, policy, "sast-tool {paths}", 1000L, 2000)

    when:
    def result = tool.run(["src/\nmain"])

    then:
    !result.ran
    !result.success
    result.message.contains("control characters")
    0 * runner.run(_ as List<String>, _, _)
  }

  def "expands paths into command arguments"() {
    given:
    CommandRunner runner = Mock()
    CommandPolicy policy = new CommandPolicy("", "")
    SastTool tool = new SastTool(runner, policy, "semgrep --json {paths}", 1000L, 2000)

    when:
    def result = tool.run(["src/App.groovy", "src/Other.groovy"])

    then:
    1 * runner.run(
      ["semgrep", "--json", "src/App.groovy", "src/Other.groovy"],
      1000L,
      2000
    ) >> new CommandRunner.CommandResult(true, false, 0, "{}", false, null)
    result.success
  }
}
