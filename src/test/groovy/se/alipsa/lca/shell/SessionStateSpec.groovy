package se.alipsa.lca.shell

import com.embabel.common.ai.model.LlmOptions
import spock.lang.Specification

class SessionStateSpec extends Specification {

  SessionState state = new SessionState("default-model", 0.7d, 0.35d, 0, "", true, "fallback")

  def "uses default model and temperatures when unset"() {
    when:
    def settings = state.getOrCreate("s1")
    def craft = state.craftOptions(settings)
    def review = state.reviewOptions(settings)

    then:
    craft.model == "default-model"
    craft.temperature == 0.7d
    review.model == "default-model"
    review.temperature == 0.35d
  }

  def "applies overrides and max tokens"() {
    when:
    def settings = state.update("s2", "custom", 0.9d, 0.4d, 1024, "sys", null)
    def craft = state.craftOptions(settings)

    then:
    craft.model == "custom"
    craft.temperature == 0.9d
    craft.maxTokens == 1024
    state.systemPrompt(settings) == "sys"
  }

  def "history is appended per session"() {
    when:
    state.appendHistory("hist", "a", "b")
    state.appendHistory("hist", "c")

    then:
    state.history("hist") == ["a", "b", "c"]
  }

  def "falls back to default system prompt when unset"() {
    given:
    def withDefault = new SessionState("default-model", 0.7d, 0.35d, 0, "base", false, "fallback")

    when:
    def prompt = withDefault.systemPrompt(withDefault.getOrCreate("s3"))

    then:
    prompt == "base"
  }

  def "tracks web search enablement with defaults"() {
    when:
    def disabled = new SessionState("m", 0.5d, 0.4d, 0, "", false, "fallback")
    def settings = disabled.update("s4", null, null, null, null, null, true)

    then:
    state.isWebSearchEnabled("unknown")
    !disabled.isWebSearchEnabled("unknown")
    disabled.isWebSearchEnabled("s4")
    settings.webSearchEnabled
  }
}
