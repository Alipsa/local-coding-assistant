package se.alipsa.lca.shell

import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import spock.lang.Specification

class DefaultBatchExitHandlerSpec extends Specification {

  def "exit propagates SpringApplication exit code"() {
    given:
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()
    context.refresh()
    TestExitHandler handler = new TestExitHandler(context)

    when:
    handler.exit(7)

    then:
    handler.exitCode == 7
    !context.isActive()
  }

  private static class TestExitHandler extends DefaultBatchExitHandler {
    Integer exitCode

    TestExitHandler(ConfigurableApplicationContext context) {
      super(context)
    }

    @Override
    protected void exitProcess(int exitCode) {
      this.exitCode = exitCode
    }
  }
}
