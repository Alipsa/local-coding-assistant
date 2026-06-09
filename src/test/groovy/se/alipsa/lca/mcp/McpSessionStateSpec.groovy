package se.alipsa.lca.mcp

import spock.lang.Specification

class McpSessionStateSpec extends Specification {

  McpSessionState state

  def setup() {
    state = new McpSessionState()
  }

  def "defaults to AUTO mode"() {
    expect:
    state.getMode("session1") == McpSessionState.McpActivationMode.AUTO
    state.getMode("session2") == McpSessionState.McpActivationMode.AUTO
    state.getMode(null) == McpSessionState.McpActivationMode.AUTO
    state.getMode("") == McpSessionState.McpActivationMode.AUTO
  }

  def "activating a server switches to MANUAL mode"() {
    when:
    state.activate("session1", "server-a")

    then:
    state.getMode("session1") == McpSessionState.McpActivationMode.MANUAL
    state.isActive("session1", "server-a")
  }

  def "deactivating a server keeps MANUAL mode"() {
    given:
    state.activate("session1", "server-a")
    state.activate("session1", "server-b")

    when:
    state.deactivate("session1", "server-a")

    then:
    state.getMode("session1") == McpSessionState.McpActivationMode.MANUAL
    !state.isActive("session1", "server-a")
    state.isActive("session1", "server-b")
  }

  def "resetToAuto clears manual overrides"() {
    given:
    state.activate("session1", "server-a")
    state.activate("session1", "server-b")

    when:
    state.resetToAuto("session1")

    then:
    state.getMode("session1") == McpSessionState.McpActivationMode.AUTO
    !state.isActive("session1", "server-a")
    !state.isActive("session1", "server-b")
    state.getActiveServers("session1").isEmpty()
  }

  def "sessions are isolated"() {
    when:
    state.activate("s1", "server-a")
    state.activate("s2", "server-b")

    then:
    state.isActive("s1", "server-a")
    !state.isActive("s1", "server-b")
    state.isActive("s2", "server-b")
    !state.isActive("s2", "server-a")
    state.getMode("s1") == McpSessionState.McpActivationMode.MANUAL
    state.getMode("s2") == McpSessionState.McpActivationMode.MANUAL
  }

  def "getActiveServers returns set of active servers"() {
    when:
    state.activate("session1", "server-a")
    state.activate("session1", "server-b")
    state.activate("session1", "server-c")

    then:
    state.getActiveServers("session1").size() == 3
    state.getActiveServers("session1").contains("server-a")
    state.getActiveServers("session1").contains("server-b")
    state.getActiveServers("session1").contains("server-c")
  }

  def "getActiveServers returns empty set in AUTO mode"() {
    expect:
    state.getActiveServers("session1").isEmpty()
    state.getMode("session1") == McpSessionState.McpActivationMode.AUTO
  }

  def "getActiveServers returns unmodifiable set"() {
    given:
    state.activate("session1", "server-a")

    when:
    Set<String> servers = state.getActiveServers("session1")
    servers.add("server-b")

    then:
    thrown(UnsupportedOperationException)
  }

  def "null and empty session IDs are normalised to default"() {
    when:
    state.activate(null, "server-a")
    state.activate("", "server-b")

    then:
    state.isActive(null, "server-a")
    state.isActive("", "server-a")
    state.isActive(null, "server-b")
    state.isActive("", "server-b")
    state.getMode(null) == McpSessionState.McpActivationMode.MANUAL
    state.getMode("") == McpSessionState.McpActivationMode.MANUAL
  }

  def "deactivating non-existent server is safe"() {
    when:
    state.deactivate("session1", "server-a")

    then:
    notThrown(Exception)
  }

  def "isActive returns false for non-existent session"() {
    expect:
    !state.isActive("non-existent", "server-a")
  }
}
