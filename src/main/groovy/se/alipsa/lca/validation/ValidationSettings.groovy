package se.alipsa.lca.validation

import groovy.transform.CompileStatic
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "assistant.validation")
@CompileStatic
class ValidationSettings {

  /**
   * Whether request validation is enabled globally
   */
  boolean enabled = true

  /**
   * Whether to skip validation in batch mode
   */
  boolean skipInBatch = true

  /**
   * Minimum prompt length before flagging as too short
   */
  int minPromptLength = 15

  /**
   * Maximum number of clarification rounds before giving up
   */
  int maxClarificationRounds = 2

  /**
   * Whether to use ContextResolver to resolve file references
   */
  boolean useContextResolver = true

}
