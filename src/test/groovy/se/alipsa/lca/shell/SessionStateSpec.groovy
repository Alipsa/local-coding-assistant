package se.alipsa.lca.shell

import com.embabel.common.ai.model.LlmOptions
import se.alipsa.lca.tools.AgentsMdProvider
import se.alipsa.lca.tools.LocalOnlyState
import spock.lang.Specification

import java.time.Instant

class SessionStateSpec extends Specification {

  AgentsMdProvider agentsMdProvider = Stub() {
    appendToSystemPrompt(_) >> { String base -> base }
  }
  SessionState state = new SessionState(
    "default-model",
    0.7d,
    0.35d,
    0,
    "",
    true,
    "htmlunit",
    "jsoup",
    600L,
    "fallback",
    agentsMdProvider,
    new LocalOnlyState(false)
  )

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
    def withDefault = new SessionState(
      "default-model",
      0.7d,
      0.35d,
      0,
      "base",
      false,
      "htmlunit",
      "jsoup",
      600L,
      "fallback",
      agentsMdProvider,
      new LocalOnlyState(false)
    )

    when:
    def prompt = withDefault.systemPrompt(withDefault.getOrCreate("s3"))

    then:
    prompt == "base"
  }

  def "tracks web search enablement with defaults"() {
    when:
    def disabled = new SessionState(
      "m",
      0.5d,
      0.4d,
      0,
      "",
      false,
      "htmlunit",
      "jsoup",
      600L,
      "fallback",
      agentsMdProvider,
      new LocalOnlyState(false)
    )
    def settings = disabled.update("s4", null, null, null, null, null, true)

    then:
    state.isWebSearchEnabled("unknown")
    !disabled.isWebSearchEnabled("unknown")
    disabled.isWebSearchEnabled("s4")
    settings.webSearchEnabled
  }

  def "local-only mode disables web search"() {
    when:
    def localOnly = new SessionState(
      "m",
      0.5d,
      0.4d,
      0,
      "",
      true,
      "htmlunit",
      "jsoup",
      600L,
      "fallback",
      agentsMdProvider,
      new LocalOnlyState(true)
    )

    then:
    !localOnly.isWebSearchEnabled("default")
    localOnly.isLocalOnly()
  }

  def "local-only override allows web search for a session"() {
    given:
    def localOnly = new SessionState(
      "m",
      0.5d,
      0.4d,
      0,
      "",
      true,
      "htmlunit",
      "jsoup",
      600L,
      "fallback",
      agentsMdProvider,
      new LocalOnlyState(true)
    )

    when:
    localOnly.setLocalOnlyOverride("s1", false)

    then:
    !localOnly.isLocalOnly("s1")
    localOnly.isWebSearchEnabled("s1")
  }

  def "web search fetcher overrides apply per session"() {
    when:
    state.setWebSearchFetcherOverride("s1", "jsoup")
    state.setWebSearchFallbackFetcherOverride("s1", "none")

    then:
    state.getWebSearchFetcher("s1") == "jsoup"
    state.getWebSearchFallbackFetcher("s1") == "none"
    state.getWebSearchFetcher("other") == "htmlunit"
    state.getWebSearchFallbackFetcher("other") == "jsoup"
  }

  def "conversation is stored per session"() {
    when:
    def first = state.getOrCreateConversation("s1")
    def second = state.getOrCreateConversation("s1")
    def other = state.getOrCreateConversation("s2")

    then:
    first.is(second)
    !first.is(other)
    first.id == "s1"
  }

  def "recent tool summary honours ttl"() {
    given:
    def ttlState = new SessionState(
      "default-model",
      0.7d,
      0.35d,
      0,
      "",
      true,
      "htmlunit",
      "jsoup",
      2L,
      "fallback",
      agentsMdProvider,
      new LocalOnlyState(false)
    )
    def expired = new SessionState.ToolSummary("web-search", "Old", Instant.now().minusSeconds(5))
    def fresh = new SessionState.ToolSummary("web-search", "Fresh", Instant.now())

    when:
    ttlState.storeToolSummary("s1", expired)

    then:
    ttlState.getRecentToolSummary("s1") == null

    when:
    ttlState.storeToolSummary("s1", fresh)

    then:
    ttlState.getRecentToolSummary("s1").summary == "Fresh"
  }

  def "trackFilePath adds file to recent paths"() {
    when:
    state.trackFilePath("s1", "src/Main.groovy")
    state.trackFilePath("s1", "src/Utils.groovy")

    then:
    state.getRecentFilePaths("s1") == ["src/Utils.groovy", "src/Main.groovy"]
  }

  def "trackFilePath moves existing file to front"() {
    when:
    state.trackFilePath("s1", "src/A.groovy")
    state.trackFilePath("s1", "src/B.groovy")
    state.trackFilePath("s1", "src/C.groovy")
    state.trackFilePath("s1", "src/A.groovy")

    then:
    state.getRecentFilePaths("s1") == ["src/A.groovy", "src/C.groovy", "src/B.groovy"]
  }

  def "trackFilePath limits to 10 most recent files"() {
    when:
    (1..15).each { state.trackFilePath("s1", "file${it}.groovy") }

    then:
    state.getRecentFilePaths("s1").size() == 10
    state.getRecentFilePaths("s1").first() == "file15.groovy"
    state.getRecentFilePaths("s1").last() == "file6.groovy"
  }

  def "trackFilePaths adds multiple files"() {
    when:
    state.trackFilePaths("s1", ["src/A.groovy", "src/B.groovy", "src/C.groovy"])

    then:
    state.getRecentFilePaths("s1") == ["src/C.groovy", "src/B.groovy", "src/A.groovy"]
  }

  def "getRecentFilePaths respects limit parameter"() {
    when:
    state.trackFilePaths("s1", ["a.groovy", "b.groovy", "c.groovy", "d.groovy"])

    then:
    state.getRecentFilePaths("s1", 2) == ["d.groovy", "c.groovy"]
  }

  def "getRecentFilePaths returns empty for unknown session"() {
    when:
    def paths = state.getRecentFilePaths("unknown-session")

    then:
    paths.isEmpty()
  }
}
