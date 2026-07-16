package se.alipsa.lca.gui

import spock.lang.Specification

class SinkEventDispatcherSpec extends Specification {

  ConversationView view = Mock()

  def "routes #kind to the matching ConversationView call"() {
    when:
    SinkEventDispatcher.apply(view, new SinkEvent(kind, text))

    then:
    noteCalls * view.addNote(text)
    beginCalls * view.beginBlock()
    appendCalls * view.appendBlock(text)
    endCalls * view.endBlock()
    msgCalls * view.addAssistantMessage(text)

    where:
    kind                        | text     || noteCalls | beginCalls | appendCalls | endCalls | msgCalls
    SinkEvent.Kind.NOTE         | "n"      || 1         | 0          | 0           | 0        | 0
    SinkEvent.Kind.BLOCK_BEGIN  | null     || 0         | 1          | 0           | 0        | 0
    SinkEvent.Kind.BLOCK_APPEND | "l"      || 0         | 0          | 1           | 0        | 0
    SinkEvent.Kind.BLOCK_END    | null     || 0         | 0          | 0           | 1        | 0
    SinkEvent.Kind.MESSAGE      | "m"      || 0         | 0          | 0           | 0        | 1
  }
}
