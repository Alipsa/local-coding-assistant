package se.alipsa.lca.tools

import spock.lang.Specification

class StandardToolCallSpec extends Specification {

  def "creates StandardToolCall with all properties"() {
    given:
    Map<String, Object> args = [param1: "value1", param2: 42]

    when:
    StandardToolCall call = new StandardToolCall("myServer", "myTool", args)

    then:
    call.serverName == "myServer"
    call.toolName == "myTool"
    call.arguments == args
  }

  def "tests equality and hashCode"() {
    given:
    Map<String, Object> args1 = [param1: "value1", param2: 42]
    Map<String, Object> args2 = [param1: "value1", param2: 42]
    Map<String, Object> args3 = [param1: "different", param2: 99]

    when:
    StandardToolCall call1 = new StandardToolCall("server1", "tool1", args1)
    StandardToolCall call2 = new StandardToolCall("server1", "tool1", args2)
    StandardToolCall call3 = new StandardToolCall("server1", "tool1", args3)
    StandardToolCall call4 = new StandardToolCall("server2", "tool1", args1)

    then:
    call1 == call2
    call1.hashCode() == call2.hashCode()
    call1 != call3
    call1 != call4
  }

  def "allows null arguments map"() {
    when:
    StandardToolCall call = new StandardToolCall("server", "tool", null)

    then:
    call.serverName == "server"
    call.toolName == "tool"
    call.arguments == null
  }

  def "allows empty arguments map"() {
    when:
    StandardToolCall call = new StandardToolCall("server", "tool", [:])

    then:
    call.serverName == "server"
    call.toolName == "tool"
    call.arguments == [:]
  }
}
