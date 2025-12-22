package se.alipsa.lca.shell

import groovy.transform.CompileStatic
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.stereotype.Component

@Component
@CompileStatic
class DefaultBatchExitHandler implements BatchExitHandler {

  private final ConfigurableApplicationContext context

  DefaultBatchExitHandler(ConfigurableApplicationContext context) {
    this.context = context
  }

  @Override
  void exit(int code) {
    int exitCode = SpringApplication.exit(context, { -> code })
    exitProcess(exitCode)
  }

  protected void exitProcess(int exitCode) {
    System.exit(exitCode)
  }
}
