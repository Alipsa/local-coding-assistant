package se.alipsa.lca.shell

import com.embabel.agent.spi.support.RankingProperties
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import spock.lang.Specification
import spock.lang.Unroll

class RankingConfigurationSpec extends Specification {

  @Unroll
  def "#resource binds the configured ranking model"() {
    given:
    Properties properties = new Properties()
    getClass().getResourceAsStream('/' + resource).withCloseable { properties.load(it) }
    Map<String, Object> values = properties.collectEntries { key, value -> [(key.toString()): value] }
    Binder binder = new Binder([new MapConfigurationPropertySource(values)])

    when:
    RankingProperties ranking = binder.bind(RankingProperties.PREFIX, Bindable.of(RankingProperties)).get()

    then:
    ranking.llm == expectedModel

    where:
    resource                            | expectedModel
    'application.properties'            | 'gpt-oss-64k:latest'
    'application-batch-test.properties'  | 'gpt-oss:20b'
  }
}
