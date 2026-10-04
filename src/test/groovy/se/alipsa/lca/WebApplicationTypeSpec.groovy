package se.alipsa.lca

import org.springframework.boot.SpringApplication
import org.springframework.boot.context.logging.LoggingApplicationListener
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent
import org.springframework.context.ApplicationListener
import org.springframework.core.env.ConfigurableEnvironment
import spock.lang.Specification

class WebApplicationTypeSpec extends Specification {

  def "web server is disabled by default without an Embabel shell property source"() {
    when:
    ConfigurableEnvironment environment = prepareEnvironment()

    then:
    environment.getProperty('spring.main.web-application-type') == 'none'
    !environment.propertySources.contains('shellModeProperties')
  }

  def "command line can enable the REST web server"() {
    when:
    ConfigurableEnvironment environment = prepareEnvironment('--spring.main.web-application-type=servlet')

    then:
    environment.getProperty('spring.main.web-application-type') == 'servlet'
    !environment.propertySources.contains('shellModeProperties')
  }

  private static ConfigurableEnvironment prepareEnvironment(String... args) {
    // SPRING_MAIN_WEB_APPLICATION_TYPE can intentionally override the application's default.
    ConfigurableEnvironment environment = null
    SpringApplication application = new SpringApplication(Object)
    application.logStartupInfo = false
    // Environment preparation must not reset or stop the shared test JVM's logging context.
    application.setListeners(application.listeners.findAll { !(it instanceof LoggingApplicationListener) })
    application.addListeners(new ApplicationListener<ApplicationEnvironmentPreparedEvent>() {
      @Override
      void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        environment = event.environment
        throw new EnvironmentPrepared()
      }
    })
    try {
      application.run(args)
      throw new AssertionError('Expected startup to stop after environment preparation')
    } catch (EnvironmentPrepared ignored) {
      return environment
    }
  }

  private static class EnvironmentPrepared extends RuntimeException {
  }
}
