package se.alipsa.lca.intent

import spock.lang.Specification

class IntentRouterParserSpec extends Specification {

  def "parse reads commands and metadata"() {
    given:
    IntentRouterParser parser = new IntentRouterParser()
    String json = """
{"commands":[{"name":"/review","args":{"path":"src/main/groovy"}}],
"confidence":0.9,
"explanation":"ok"}
""".trim()

    when:
    IntentRouterResult result = parser.parse(json)

    then:
    result.confidence == 0.9d
    result.explanation == "ok"
    result.commands.size() == 1
    result.commands[0].name == "/review"
    result.commands[0].args["path"] == "src/main/groovy"
  }

  def "parse extracts json from extra text"() {
    given:
    IntentRouterParser parser = new IntentRouterParser()
    String payload = """
Header text
{"commands":[{"name":"/chat","args":{}}],"confidence":0.2,"explanation":"fallback"}
Trailing text
""".trim()

    when:
    IntentRouterResult result = parser.parse(payload)

    then:
    result.commands.size() == 1
    result.commands[0].name == "/chat"
    result.confidence == 0.2d
  }

  def "parse rejects invalid json"() {
    given:
    IntentRouterParser parser = new IntentRouterParser()

    when:
    parser.parse("not-json")

    then:
    thrown(IllegalArgumentException)
  }
}
