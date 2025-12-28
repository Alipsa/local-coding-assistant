package se.alipsa.lca.intent

import com.embabel.agent.api.common.Ai
import com.embabel.common.ai.model.LlmOptions
import se.alipsa.lca.tools.ModelRegistry
import spock.lang.Specification

class IntentRouterAgentSpec extends Specification {

  def "route returns parsed commands when confident"() {
    given:
    def agent = new TestIntentRouterAgent(stubAi(), stubRegistry())
    agent.responses = [
      '{"commands":[{"name":"/review","args":{"path":"src"}}],"confidence":0.9,"explanation":"ok"}'
    ]

    when:
    IntentRouterResult result = agent.route("Review src")

    then:
    result.commands.size() == 1
    result.commands[0].name == "/review"
    result.confidence == 0.9d
  }

  def "route retries with stricter prompt on invalid json"() {
    given:
    def agent = new TestIntentRouterAgent(stubAi(), stubRegistry())
    agent.responses = [
      "invalid",
      '{"commands":[{"name":"/review","args":{}}],"confidence":0.9,"explanation":"ok"}'
    ]

    when:
    IntentRouterResult result = agent.route("Review code")

    then:
    result.commands.size() == 1
    agent.optionsSeen.size() == 2
    agent.optionsSeen[1].temperature == 0.0d
  }

  def "route falls back when confidence is low"() {
    given:
    def agent = new TestIntentRouterAgent(stubAi(), stubRegistry())
    agent.responses = [
      '{"commands":[{"name":"/review","args":{}}],"confidence":0.2,"explanation":"low"}'
    ]

    when:
    IntentRouterResult result = agent.route("Review code")

    then:
    result.commands.size() == 1
    result.commands[0].name == "/chat"
    result.confidence == 0.0d
  }

  def "route falls back on unknown command"() {
    given:
    def agent = new TestIntentRouterAgent(stubAi(), stubRegistry())
    agent.responses = [
      '{"commands":[{"name":"/unknown","args":{}}],"confidence":0.9,"explanation":"nope"}'
    ]

    when:
    IntentRouterResult result = agent.route("Do something")

    then:
    result.commands.size() == 1
    result.commands[0].name == "/chat"
  }

  private static class TestIntentRouterAgent extends IntentRouterAgent {

    List<String> responses = []
    List<LlmOptions> optionsSeen = []
    private int index = 0

    TestIntentRouterAgent(Ai ai, ModelRegistry modelRegistry) {
      super(
        ai,
        modelRegistry,
        new IntentRouterParser(),
        "tinyllama",
        "gpt-oss:20b",
        0.2d,
        128,
        0.8d,
        "/chat,/review"
      )
    }

    @Override
    protected String generateResponse(String prompt, LlmOptions options) {
      optionsSeen.add(options)
      if (index >= responses.size()) {
        return null
      }
      responses[index++]
    }
  }

  private Ai stubAi() {
    Stub(Ai)
  }

  private ModelRegistry stubRegistry() {
    Stub(ModelRegistry) {
      listModels() >> ["tinyllama", "gpt-oss:20b"]
    }
  }
}
