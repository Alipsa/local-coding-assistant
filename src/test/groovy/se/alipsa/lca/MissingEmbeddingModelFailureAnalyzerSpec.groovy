package se.alipsa.lca

import org.springframework.beans.factory.BeanCreationException
import spock.lang.Specification
import spock.lang.Unroll

class MissingEmbeddingModelFailureAnalyzerSpec extends Specification {

  @Unroll
  def "explains a missing embedding model through a bean creation failure: #model"() {
    given:
    Throwable cause = new IllegalStateException(
      "Embedding model '${model}' for role best is not available: Choices are []")
    Throwable failure = new BeanCreationException('modelProvider', 'Factory method failed', cause)

    when:
    def analysis = new MissingEmbeddingModelFailureAnalyzer().analyze(failure)

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
    new MissingEmbeddingModelFailureAnalyzer().analyze(new IllegalStateException('Unrelated failure')) == null
  }
}
