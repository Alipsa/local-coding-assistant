package se.alipsa.lca.shell

import groovy.transform.CompileStatic
import org.jline.reader.Parser
import org.jline.reader.ParsedLine
import org.jline.reader.impl.DefaultParser
import org.springframework.shell.ExitRequest
import org.springframework.shell.Input
import org.springframework.shell.ResultHandlerService
import org.springframework.shell.Shell
import org.springframework.stereotype.Component

import java.lang.reflect.InvocationTargetException

@Component
@CompileStatic
class ShellBatchCommandExecutor implements BatchCommandExecutor {

  private final Shell shell
  private final ResultHandlerService resultHandlerService
  private final Parser parser
  private final java.lang.reflect.Method evaluateMethod

  ShellBatchCommandExecutor(Shell shell, ResultHandlerService resultHandlerService) {
    this.shell = shell
    this.resultHandlerService = resultHandlerService
    this.parser = new DefaultParser()
    try {
      this.evaluateMethod = Shell.class.getDeclaredMethod("evaluate", Input)
      this.evaluateMethod.setAccessible(true)
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Failed to access Spring Shell evaluate method.", e)
    }
  }

  @Override
  BatchCommandOutcome execute(String command) {
    BatchCommandOutcome outcome = new BatchCommandOutcome()
    if (command == null || command.trim().isEmpty()) {
      outcome.error = new IllegalArgumentException("Command must not be empty.")
      handleResult(outcome)
      return outcome
    }
    try {
      ParsedLine parsed = parser.parse(command, command.length() + 1)
      Input input = new BatchInput(command, parsed.words())
      Object result = evaluateMethod.invoke(shell, input)
      if (result == Shell.NO_INPUT) {
        outcome.result = null
        return outcome
      }
      if (result instanceof ExitRequest) {
        ExitRequest exit = (ExitRequest) result
        outcome.exitRequested = true
        outcome.exitCode = exit.status()
        return outcome
      }
      outcome.result = result
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e
      if (cause instanceof ExitRequest) {
        ExitRequest exit = (ExitRequest) cause
        outcome.exitRequested = true
        outcome.exitCode = exit.status()
        return outcome
      }
      outcome.error = cause
    } catch (Exception e) {
      outcome.error = e
    }
    handleResult(outcome)
    outcome
  }

  private void handleResult(BatchCommandOutcome outcome) {
    Object toHandle = outcome.error != null ? outcome.error : outcome.result
    if (toHandle == null || toHandle == Shell.NO_INPUT || toHandle instanceof ExitRequest) {
      return
    }
    resultHandlerService.handle(toHandle)
  }

  @CompileStatic
  private static class BatchInput implements Input {
    private final String raw
    private final List<String> words

    BatchInput(String raw, List<String> words) {
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
