package se.alipsa.lca.memory

import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySource
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Binds directly via Binder/MapConfigurationPropertySource rather than a Spring
 * ApplicationContextRunner - a nested @Configuration/@EnableConfigurationProperties test class
 * would live in this package (se.alipsa.lca.memory) and get picked up by the real app's
 * component scan when BatchModeIntegrationSpec launches it on the full test classpath,
 * registering a second, spurious MemorySettings bean.
 */
class MemorySettingsSpec extends Specification {

  private static MemorySettings bind(Map<String, String> properties) {
    ConfigurationPropertySource source = new MapConfigurationPropertySource(properties)
    // Explicit array, not a bare source: MapConfigurationPropertySource is itself Iterable, so
    // passing it directly is ambiguous with Binder's Iterable<ConfigurationPropertySource>
    // overload and Groovy picks the wrong one, causing a ClassCastException at construction.
    Binder binder = new Binder([source] as ConfigurationPropertySource[])
    MemorySettings settings = new MemorySettings()
    binder.bind("lca.memory", Bindable.ofInstance(settings))
    settings
  }

  def "defaults match application.properties"() {
    expect:
    MemorySettings settings = bind([:])
    settings.enabled
    settings.embeddingModel == "nomic-embed-text:latest"
    settings.indexDirectory == "${System.getProperty('user.home')}/.lca/memory-index".toString()
    settings.recallTopK == 5
    settings.recallMinScore == 0.6d
    settings.maxAgeDays == 180
    settings.maxIdleDays == 30
    settings.pruneMinIntervalMinutes == 60
    settings.surprisingLearningDetectionEnabled
    settings.surprisingLearningModel == ""
    settings.recallMaxContextChars == 2000
    settings.maxMemoryContentChars == 500
    settings.recallOverFetchFactor == 4
    settings.supersedeSimilarityThreshold == 0.85d
    settings.supersedeCandidateLimit == 3
  }

  @Unroll
  def "binds lca.memory.#property to MemorySettings.#groovyProperty"() {
    given:
    String key = "lca.memory.${property}"

    expect:
    MemorySettings settings = bind([(key): value])
    settings[groovyProperty] == expected

    where:
    property                                | groovyProperty                       | value   || expected
    "enabled"                               | "enabled"                            | "false" || false
    "recall-top-k"                          | "recallTopK"                         | "9"     || 9
    "recall-min-score"                      | "recallMinScore"                     | "0.42"  || 0.42d
    "max-age-days"                          | "maxAgeDays"                         | "10"    || 10
    "max-idle-days"                         | "maxIdleDays"                        | "3"     || 3
    "prune-min-interval-minutes"            | "pruneMinIntervalMinutes"            | "5"     || 5
    "surprising-learning-detection-enabled" | "surprisingLearningDetectionEnabled" | "false" || false
    "recall-max-context-chars"              | "recallMaxContextChars"              | "1234"  || 1234
    "max-memory-content-chars"              | "maxMemoryContentChars"              | "77"    || 77
    "recall-over-fetch-factor"              | "recallOverFetchFactor"              | "8"     || 8
    "supersede-similarity-threshold"        | "supersedeSimilarityThreshold"       | "0.9"   || 0.9d
    "supersede-candidate-limit"             | "supersedeCandidateLimit"            | "7"     || 7
  }
}
