package se.alipsa.lca.shell

import org.jline.reader.Parser
import org.jline.reader.impl.DefaultParser
import org.springframework.shell.Input
import org.springframework.shell.InputProvider
import se.alipsa.lca.intent.IntentCommandRouter
import se.alipsa.lca.intent.IntentRoutingOutcome
import se.alipsa.lca.intent.IntentRoutingPlan
import se.alipsa.lca.intent.IntentRoutingSettings
import se.alipsa.lca.intent.IntentRoutingState
import spock.lang.Specification

import java.io.ByteArrayInputStream
import java.io.InputStream

class SlashCommandShellRunnerRoutingSpec extends Specification {

  def "routing bypasses slash-prefixed input"() {
    given:
    IntentCommandRouter router = Mock()
    InputProvider delegate = new SingleInputProvider(new TestInput("/review src/main/groovy"))
    Object provider = buildProvider(delegate, router, new IntentRoutingState(), new IntentRoutingSettings(true, "/edit"))

    when:
    Input output = provider.readInput() as Input

    then:
    output.rawText() == "/review src/main/groovy"
    0 * router._
  }

  def "routing cancels destructive command when not confirmed"() {
    given:
    InputProvider delegate = new SingleInputProvider(new TestInput("Please edit src/App.groovy"))
    IntentCommandRouter router = Mock()
    IntentRoutingOutcome outcome = new IntentRoutingOutcome(
      new IntentRoutingPlan(List.of("/edit --file-path src/App.groovy"), 0.9d, null),
      null
    )
    router.routeDetails("Please edit src/App.groovy") >> outcome
    Object provider = buildProvider(delegate, router, new IntentRoutingState(), new IntentRoutingSettings(true, "/edit"))
    InputStream originalIn = System.in
    System.in = new ByteArrayInputStream("n\n".bytes)

    when:
    Input output = provider.readInput() as Input

    then:
    output.rawText() == ""

    cleanup:
    System.in = originalIn
  }

  def "routing executes destructive command when confirmed"() {
    given:
    InputProvider delegate = new SingleInputProvider(new TestInput("Please edit src/App.groovy"))
    IntentCommandRouter router = Mock()
    IntentRoutingOutcome outcome = new IntentRoutingOutcome(
      new IntentRoutingPlan(List.of("/edit --file-path src/App.groovy"), 0.9d, null),
      null
    )
    router.routeDetails("Please edit src/App.groovy") >> outcome
    Object provider = buildProvider(delegate, router, new IntentRoutingState(), new IntentRoutingSettings(true, "/edit"))
    InputStream originalIn = System.in
    System.in = new ByteArrayInputStream("y\n".bytes)

    when:
    Input output = provider.readInput() as Input

    then:
    output.rawText() == "/edit --file-path src/App.groovy"

    cleanup:
    System.in = originalIn
  }

  private static Object buildProvider(
    InputProvider delegate,
    IntentCommandRouter router,
    IntentRoutingState state,
    IntentRoutingSettings settings
  ) {
    Parser parser = new DefaultParser()
    CommandInputNormaliser normaliser = new CommandInputNormaliser(new ShellSettings(true))
    Class<?> providerClass = SlashCommandShellRunner.declaredClasses.find { it.simpleName == "NormalisingInputProvider" }
    def ctor = providerClass.getDeclaredConstructor(
      InputProvider,
      Parser,
      CommandInputNormaliser,
      IntentCommandRouter,
      IntentRoutingState,
      IntentRoutingSettings
    )
    ctor.setAccessible(true)
    ctor.newInstance(delegate, parser, normaliser, router, state, settings)
  }

  private static class SingleInputProvider implements InputProvider {
    private final Input input
    private boolean used = false

    SingleInputProvider(Input input) {
      this.input = input
    }

    @Override
    Input readInput() {
      if (used) {
        return null
      }
      used = true
      input
    }
  }

  private static class TestInput implements Input {
    private final String raw

    TestInput(String raw) {
      this.raw = raw
    }

    @Override
    String rawText() {
      raw
    }

    @Override
    List<String> words() {
      List.of()
    }
  }
}
