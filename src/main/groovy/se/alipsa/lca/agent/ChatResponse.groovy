package se.alipsa.lca.agent

import com.embabel.chat.AssistantMessage
import groovy.transform.Canonical
import groovy.transform.CompileStatic

@Canonical
@CompileStatic
class ChatResponse {
  AssistantMessage message
  String reasoning
}
