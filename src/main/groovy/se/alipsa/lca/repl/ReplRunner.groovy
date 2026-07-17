package se.alipsa.lca.repl

import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner

/**
 * Starts the JLine REPL after Spring context is loaded. Registered as a {@code @Bean} (see
 * {@code RunnerConfiguration}) when {@code lca.repl.enabled=true} (default) and no
 * {@code GuiRunner} bean is present.
 */
@CompileStatic
class ReplRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(ReplRunner)

  private final JLineRepl repl

  ReplRunner(JLineRepl repl) {
    this.repl = repl
    log.debug("ReplRunner constructed with repl: {}", repl)
  }

  @Override
  void run(ApplicationArguments args) throws Exception {
    log.debug("ReplRunner.run() called - about to start JLine REPL...")

    // Start REPL in the main thread (blocking)
    repl.start()

    // When REPL exits, shutdown the application
    log.debug("REPL exited, shutting down application")
    System.exit(0)
  }
}
