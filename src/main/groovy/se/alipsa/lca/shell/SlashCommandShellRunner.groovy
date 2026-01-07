package se.alipsa.lca.shell

import groovy.transform.CompileStatic
import org.jline.reader.LineReader
import org.jline.reader.ParsedLine
import org.jline.reader.Parser
import org.jline.reader.impl.DefaultParser
import org.springframework.core.annotation.Order
import org.springframework.shell.Input
import org.springframework.shell.InputProvider
import org.springframework.shell.Shell
import org.springframework.shell.ShellRunner
import org.springframework.shell.context.InteractionMode
import org.springframework.shell.context.ShellContext
import org.springframework.shell.jline.InteractiveShellRunner
import org.springframework.shell.jline.PromptProvider
import org.springframework.stereotype.Component
import se.alipsa.lca.intent.IntentCommandRouter
import se.alipsa.lca.intent.IntentRoutingDebugFormatter
import se.alipsa.lca.intent.IntentRoutingOutcome
import se.alipsa.lca.intent.IntentRoutingPlan
import se.alipsa.lca.intent.IntentRoutingSettings
import se.alipsa.lca.intent.IntentRoutingState

@Component
@Order(InteractiveShellRunner.PRECEDENCE - 1)
@CompileStatic
class SlashCommandShellRunner implements ShellRunner {

  private final LineReader lineReader
  private final PromptProvider promptProvider
  private final Shell shell
  private final ShellContext shellContext
  private final CommandInputNormaliser normaliser
  private final IntentCommandRouter intentCommandRouter
  private final IntentRoutingState intentRoutingState
  private final IntentRoutingSettings intentRoutingSettings
  private final Parser parser

  SlashCommandShellRunner(
    LineReader lineReader,
    PromptProvider promptProvider,
    Shell shell,
    ShellContext shellContext,
    CommandInputNormaliser normaliser,
    IntentCommandRouter intentCommandRouter,
    IntentRoutingState intentRoutingState,
    IntentRoutingSettings intentRoutingSettings
  ) {
    this.lineReader = lineReader
    this.promptProvider = promptProvider
    this.shell = shell
    this.shellContext = shellContext
    this.normaliser = normaliser != null ? normaliser : new CommandInputNormaliser(new ShellSettings(true))
    this.intentCommandRouter = intentCommandRouter
    this.intentRoutingState = intentRoutingState
    this.intentRoutingSettings = intentRoutingSettings
    this.parser = lineReader != null && lineReader.parser != null ? lineReader.parser : new DefaultParser()
  }

  @Override
  boolean run(String[] args) throws Exception {
    shellContext.setInteractionMode(InteractionMode.INTERACTIVE)
    InputProvider delegate = new InteractiveShellRunner.JLineInputProvider(lineReader, promptProvider)
    InputProvider provider = new NormalisingInputProvider(
      delegate,
      parser,
      normaliser,
      intentCommandRouter,
      intentRoutingState,
      intentRoutingSettings
    )
    shell.run(provider)
    true
  }

  @CompileStatic
  private static class NormalisingInputProvider implements InputProvider {

    private final InputProvider delegate
    private final Parser parser
    private final CommandInputNormaliser normaliser
    private final IntentCommandRouter intentCommandRouter
    private final IntentRoutingState intentRoutingState
    private final IntentRoutingSettings intentRoutingSettings
    private final Deque<Input> queued = new ArrayDeque<>()

    NormalisingInputProvider(
      InputProvider delegate,
      Parser parser,
      CommandInputNormaliser normaliser,
      IntentCommandRouter intentCommandRouter,
      IntentRoutingState intentRoutingState,
      IntentRoutingSettings intentRoutingSettings
    ) {
      this.delegate = delegate
      this.parser = parser
      this.normaliser = normaliser
      this.intentCommandRouter = intentCommandRouter
      this.intentRoutingState = intentRoutingState
      this.intentRoutingSettings = intentRoutingSettings
    }

    @Override
    Input readInput() {
      if (!queued.isEmpty()) {
        return queued.pollFirst()
      }
      Input input = delegate.readInput()
      if (input == null) {
        return null
      }
      String raw = input.rawText()
      List<String> wordsOverride = normaliser.normaliseWords(raw)
      if (wordsOverride != null) {
        return new SimpleInput("/paste --send", wordsOverride)
      }
      Input routed = routeIfNeeded(raw)
      if (routed != null) {
        return routed
      }
      String normalised = normaliser.normalise(raw)
      if (normalised == null || normalised == raw) {
        return input
      }
      ParsedLine parsed = parser.parse(normalised, normalised.length() + 1)
      new SimpleInput(normalised, parsed.words())
    }

    private Input routeIfNeeded(String raw) {
      if (intentCommandRouter == null) {
        return null
      }
      if (!isRoutingEnabled()) {
        return null
      }
      if (!isRoutingCandidate(raw)) {
        return null
      }
      IntentRoutingOutcome outcome = intentCommandRouter.routeDetails(raw, "default")
      IntentRoutingPlan plan = outcome?.plan
      if (isDebugEnabled()) {
        printRoutingDebug(outcome)
        return new SimpleInput("", List.of())
      }
      List<String> commands = plan?.commands
      if (commands == null || commands.isEmpty()) {
        return null
      }
      if (commands.size() > 1) {
        printRoutingPreview(commands, plan)
      }
      if (requiresAdditionalConfirmation(commands) && !confirmRouting(commands)) {
        return new SimpleInput("", List.of())
      }
      List<Input> routedInputs = buildInputs(commands)
      if (routedInputs.isEmpty()) {
        return null
      }
      queued.addAll(routedInputs)
      queued.pollFirst()
    }

    private boolean isRoutingCandidate(String raw) {
      if (raw == null || raw.trim().isEmpty()) {
        return false
      }
      String trimmed = raw.trim()
      if (trimmed.startsWith("/")) {
        return false
      }
      // Allow multiline input for routing (paste mode is handled earlier in the flow)
      true
    }

    private List<Input> buildInputs(List<String> commands) {
      List<Input> inputs = new ArrayList<>()
      for (String command : commands) {
        try {
          ParsedLine parsed = parser.parse(command, command.length() + 1)
          inputs.add(new SimpleInput(command, parsed.words()))
        } catch (Exception ignored) {
          return List.of()
        }
      }
      inputs
    }

    private void printRoutingPreview(List<String> commands, IntentRoutingPlan plan) {
      println("Interpreted as:")
      commands.eachWithIndex { String command, int index ->
        println(" ${index + 1}) ${command}")
      }
      if (plan != null && plan.explanation != null && plan.explanation.trim()) {
        println("Reason: ${plan.explanation.trim()}")
      }
    }

    private void printRoutingDebug(IntentRoutingOutcome outcome) {
      println(IntentRoutingDebugFormatter.format(outcome))
    }

    private boolean isRoutingEnabled() {
      if (intentRoutingState != null) {
        return intentRoutingState.isEnabled(intentRoutingSettings)
      }
      if (intentRoutingSettings == null) {
        return true
      }
      intentRoutingSettings.isEnabled()
    }

    private boolean isDebugEnabled() {
      intentRoutingState != null && intentRoutingState.isDebugEnabled()
    }

    private boolean requiresAdditionalConfirmation(List<String> commands) {
      for (String command : commands) {
        String name = commandName(command)
        if (name && isDestructive(name) && !BUILT_IN_CONFIRM.contains(name)) {
          return true
        }
      }
      false
    }

    private boolean confirmRouting(List<String> commands) {
      println()
      println("⚠ This will execute the following:")
      commands.eachWithIndex { String command, int index ->
        println("  ${index + 1}. ${command}")
      }
      println()
      print("Proceed? (y = yes, N = cancel): ")
      BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))
      String response = reader.readLine()
      String normalised = response != null ? response.trim().toLowerCase() : ""
      "y" == normalised
    }

    private static String commandName(String command) {
      if (command == null) {
        return null
      }
      String trimmed = command.trim()
      if (!trimmed) {
        return null
      }
      int space = trimmed.indexOf(" ")
      String name = space > 0 ? trimmed.substring(0, space) : trimmed
      name
    }

    private boolean isDestructive(String command) {
      if (intentRoutingSettings == null) {
        return DEFAULT_DESTRUCTIVE.contains(command)
      }
      intentRoutingSettings.isDestructiveCommand(command)
    }

    private static final Set<String> BUILT_IN_CONFIRM = Set.of(
      "/apply",
      "/gitapply",
      "/git-push",
      "/run"
    )

    private static final Set<String> DEFAULT_DESTRUCTIVE = Set.of(
      "/edit",
      "/apply",
      "/gitapply",
      "/git-push",
      "/run"
    )
  }

  @CompileStatic
  private static class SimpleInput implements Input {
    private final String raw
    private final List<String> words

    SimpleInput(String raw, List<String> words) {
      this.raw = raw
      this.words = words != null ? words : List.of()
    }

    @Override
    String rawText() {
      raw
    }

    @Override
    List<String> words() {
      words
    }
  }
}
