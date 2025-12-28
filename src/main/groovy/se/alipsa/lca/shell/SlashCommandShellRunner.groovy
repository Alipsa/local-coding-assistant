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

@Component
@Order(InteractiveShellRunner.PRECEDENCE - 1)
@CompileStatic
class SlashCommandShellRunner implements ShellRunner {

  private final LineReader lineReader
  private final PromptProvider promptProvider
  private final Shell shell
  private final ShellContext shellContext
  private final CommandInputNormaliser normaliser
  private final Parser parser

  SlashCommandShellRunner(
    LineReader lineReader,
    PromptProvider promptProvider,
    Shell shell,
    ShellContext shellContext,
    CommandInputNormaliser normaliser
  ) {
    this.lineReader = lineReader
    this.promptProvider = promptProvider
    this.shell = shell
    this.shellContext = shellContext
    this.normaliser = normaliser != null ? normaliser : new CommandInputNormaliser(new ShellSettings(true))
    this.parser = lineReader != null && lineReader.parser != null ? lineReader.parser : new DefaultParser()
  }

  @Override
  boolean run(String[] args) throws Exception {
    shellContext.setInteractionMode(InteractionMode.INTERACTIVE)
    InputProvider delegate = new InteractiveShellRunner.JLineInputProvider(lineReader, promptProvider)
    InputProvider provider = new NormalisingInputProvider(delegate, parser, normaliser)
    shell.run(provider)
    true
  }

  @CompileStatic
  private static class NormalisingInputProvider implements InputProvider {

    private final InputProvider delegate
    private final Parser parser
    private final CommandInputNormaliser normaliser

    NormalisingInputProvider(InputProvider delegate, Parser parser, CommandInputNormaliser normaliser) {
      this.delegate = delegate
      this.parser = parser
      this.normaliser = normaliser
    }

    @Override
    Input readInput() {
      Input input = delegate.readInput()
      if (input == null) {
        return null
      }
      String raw = input.rawText()
      List<String> wordsOverride = normaliser.normaliseWords(raw)
      if (wordsOverride != null) {
        return new SimpleInput("/paste --send", wordsOverride)
      }
      String normalised = normaliser.normalise(raw)
      if (normalised == null || normalised == raw) {
        return input
      }
      ParsedLine parsed = parser.parse(normalised, normalised.length() + 1)
      new SimpleInput(normalised, parsed.words())
    }
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
