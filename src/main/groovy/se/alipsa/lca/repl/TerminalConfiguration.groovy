package se.alipsa.lca.repl

import groovy.transform.CompileStatic
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

import java.io.IOException

/**
 * Configuration for JLine Terminal.
 * Replaces Spring Shell's JLineShellAutoConfiguration.
 */
@Configuration
@CompileStatic
class TerminalConfiguration {

  @Bean(destroyMethod = "close")
  Terminal terminal() {
    try {
      return TerminalBuilder.builder()
        .system(true)
        .build()
    } catch (Exception e) {
      // Fallback to dumb terminal for piped input or testing
      try {
        return TerminalBuilder.builder()
          .dumb(true)
          .build()
      } catch (Exception ex) {
        throw new IllegalStateException("Failed to create terminal", ex)
      }
    }
  }
}
