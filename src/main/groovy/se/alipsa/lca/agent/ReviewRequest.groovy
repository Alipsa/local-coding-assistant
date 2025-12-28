package se.alipsa.lca.agent

import com.embabel.common.ai.model.LlmOptions
import groovy.transform.Canonical
import groovy.transform.CompileStatic

@Canonical
@CompileStatic
class ReviewRequest {
  String prompt
  String payload
  LlmOptions options
  String systemPrompt
  boolean security
}
