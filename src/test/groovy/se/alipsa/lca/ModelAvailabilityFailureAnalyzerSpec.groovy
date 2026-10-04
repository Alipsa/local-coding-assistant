package se.alipsa.lca

import org.springframework.beans.factory.BeanCreationException
import spock.lang.Specification
import spock.lang.Unroll

class ModelAvailabilityFailureAnalyzerSpec extends Specification {

  @Unroll
  def "explains a missing embedding model through a bean creation failure: #model"() {
    given:
    Throwable cause = new IllegalStateException(
      "Embedding model '${model}' for role best is not available: Choices are []")
    Throwable failure = new BeanCreationException('modelProvider', 'Factory method failed', cause)

    when:
    def analysis = new ModelAvailabilityFailureAnalyzer().analyze(failure)

    then:
    analysis.cause.is(cause)
    analysis.description.contains('even when memory is disabled')
    analysis.action.contains('Ensure Ollama is running')
    analysis.action.contains("ollama pull ${model}")
    analysis.action.contains('restart lca or lca gui')

    where:
    model << ['nomic-embed-text:latest', 'custom-embedding:latest']
  }

  def "leaves unrelated startup failures to other analysers"() {
    expect:
    new ModelAvailabilityFailureAnalyzer().analyze(new IllegalStateException('Unrelated failure')) == null
  }

  def "explains no models detected without recommending a cloud starter"() {
    given:
    Throwable cause = new IllegalArgumentException(
      'No models detected. Ensure that at least one Embabel Agent\n' +
        'Starter (e.g. embabel-agent-starter-openai) is on the classpath and models are loaded into it.')
    Throwable failure = new BeanCreationException('modelProvider', 'Factory method failed', cause)

    when:
    def analysis = new ModelAvailabilityFailureAnalyzer().analyze(failure)

    then:
    analysis.cause.is(cause)
    analysis.description.contains('No local models were detected')
    analysis.action.contains('ollama serve')
    analysis.action.contains('spring.ai.ollama.base-url')
    analysis.action.contains('Re-run the updated lca launcher')
    analysis.action.contains('./models.sh when running from source')
    !analysis.description.contains('openai')
    !analysis.action.contains('openai')
  }

  def "explains a missing chat role without memory-specific advice"() {
    given:
    Throwable cause = new IllegalStateException(
      "LLM 'qwen3.6-128k:latest' for role best is not available: Choices are [gpt-oss:20b]")
    Throwable failure = new BeanCreationException('modelProvider', 'Factory method failed', cause)

    when:
    def analysis = new ModelAvailabilityFailureAnalyzer().analyze(failure)

    then:
    analysis.cause.is(cause)
    analysis.description.contains("chat model 'qwen3.6-128k:latest'")
    !analysis.description.contains('memory')
    analysis.action.contains('Ensure Ollama is running')
    analysis.action.contains('install or create')
    analysis.action.contains('./models.sh when running from source')
    analysis.action.contains('qwen3.6-128k:latest')
  }
}
