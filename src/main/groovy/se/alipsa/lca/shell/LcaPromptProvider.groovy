package se.alipsa.lca.shell

import groovy.transform.CompileStatic
import org.jline.utils.AttributedString
import org.springframework.context.annotation.Primary
import org.springframework.shell.jline.PromptProvider
import org.springframework.stereotype.Component

@Component
@Primary
@CompileStatic
class LcaPromptProvider implements PromptProvider {

  private static final String PROMPT = "lca> "

  @Override
  AttributedString getPrompt() {
    new AttributedString(PROMPT)
  }
}
