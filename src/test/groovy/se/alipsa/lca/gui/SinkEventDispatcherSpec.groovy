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

  def "applies a streamed shell turn to the view in publication order"() {
    given:
    // The exact event sequence StreamingWorker publishes for a `! ...` turn: open a block, stream
    // lines, close it. process() applies each in order; assert the view sees them that way.
    List<SinkEvent> stream = [
      SinkEvent.blockBegin(),
      SinkEvent.blockAppend("\$ git status"),
      SinkEvent.blockAppend("On branch main"),
      SinkEvent.blockEnd()
    ]

    when:
    stream.each { SinkEventDispatcher.apply(view, it) }

    then:
    1 * view.beginBlock()

    then:
    1 * view.appendBlock("\$ git status")

    then:
    1 * view.appendBlock("On branch main")

    then:
    1 * view.endBlock()
  }

  def "applies a note then a completed message for a routed LLM turn"() {
    when:
    SinkEventDispatcher.apply(view, SinkEvent.note("Routed to chat"))
    SinkEventDispatcher.apply(view, SinkEvent.message("Here is the answer."))

    then:
    1 * view.addNote("Routed to chat")

    then:
    1 * view.addAssistantMessage("Here is the answer.")
  }
}
