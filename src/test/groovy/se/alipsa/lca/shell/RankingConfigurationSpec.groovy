package se.alipsa.lca.shell

import com.embabel.agent.spi.support.RankingProperties
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.MutablePropertySources
import org.springframework.core.env.PropertiesPropertySource
import org.springframework.core.env.StandardEnvironment
import spock.lang.Specification
import spock.lang.Unroll

class RankingConfigurationSpec extends Specification {

  @Unroll
  def "#resource binds the configured ranking model"() {
    given:
    Properties properties = new Properties()
    getClass().getResourceAsStream('/' + resource).withCloseable { properties.load(it) }
    StandardEnvironment environment = new StandardEnvironment()
    MutablePropertySources sources = environment.propertySources
    // Drop the real environment so LCA_* variables can't mask the files' own fallback values.
    sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
    sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
    sources.addFirst(new PropertiesPropertySource(resource, properties))
    // Binder.get resolves ${...} placeholders, as Spring does when the application starts.
    Binder binder = Binder.get(environment)

    when:
    RankingProperties ranking = binder.bind(RankingProperties.PREFIX, Bindable.of(RankingProperties)).get()

    then:
    ranking.llm == expectedModel

    where:
    resource                            | expectedModel
    'application.properties'            | 'gpt-oss-64k:latest'
    'application-batch-test.properties' | 'gpt-oss:20b'
  }
}
