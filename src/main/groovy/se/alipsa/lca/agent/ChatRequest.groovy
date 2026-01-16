package se.alipsa.lca.agent

import com.embabel.common.ai.model.LlmOptions
import groovy.transform.Canonical
import groovy.transform.CompileStatic

@Canonical
@CompileStatic
class ChatRequest {
  PersonaMode persona
  LlmOptions options
  String systemPrompt
  String responseFormat
  boolean withThinking = false
}
