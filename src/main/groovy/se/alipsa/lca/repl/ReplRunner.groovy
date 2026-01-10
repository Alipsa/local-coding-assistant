package se.alipsa.lca.repl

import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Starts the JLine REPL after Spring context is loaded.
 * Only runs when lca.repl.enabled=true (default).
 */
@Component
@CompileStatic
@ConditionalOnProperty(name = "lca.repl.enabled", havingValue = "true", matchIfMissing = true)
class ReplRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(ReplRunner)

  private final JLineRepl repl

  ReplRunner(JLineRepl repl) {
    this.repl = repl
  }

  @Override
  void run(ApplicationArguments args) throws Exception {
    log.info("Starting JLine REPL...")

    // Start REPL in the main thread (blocking)
    repl.start()

    // When REPL exits, shutdown the application
    log.info("REPL exited, shutting down application")
    System.exit(0)
  }
}
