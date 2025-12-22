package se.alipsa.lca.shell

import groovy.transform.CompileStatic
import org.jline.reader.Parser
import org.jline.reader.ParsedLine
import org.jline.reader.impl.DefaultParser
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.shell.ExitRequest
import org.springframework.shell.Input
import org.springframework.shell.ResultHandlerService
import org.springframework.shell.Shell
import org.springframework.stereotype.Component
import se.alipsa.lca.tools.LogSanitizer

import java.lang.reflect.InvocationTargetException

@Component
@CompileStatic
class ShellBatchCommandExecutor implements BatchCommandExecutor {

  private static final Logger log = LoggerFactory.getLogger(ShellBatchCommandExecutor)

  private final Shell shell
  private final ResultHandlerService resultHandlerService
  private final Parser parser
  private final java.lang.reflect.Method evaluateMethod

  ShellBatchCommandExecutor(Shell shell, ResultHandlerService resultHandlerService) {
    this.shell = shell
    this.resultHandlerService = resultHandlerService
    this.parser = new DefaultParser()
    try {
      // Spring Shell does not expose evaluate(Input) publicly; keep in sync with Spring Shell upgrades.
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
      try {
        ParsedLine parsed
        try {
          parsed = parser.parse(command, command.length() + 1)
        } catch (Exception e) {
          String safe = LogSanitizer.sanitize(command)
          log.warn("Failed to parse batch command: {}", safe, e)
          outcome.error = e
          handleResult(outcome)
          return outcome
        }
        if (parsed == null) {
          String safe = LogSanitizer.sanitize(command)
          IllegalArgumentException error = new IllegalArgumentException("Failed to parse batch command.")
          log.warn("Parsed line was null for batch command: {}", safe)
          outcome.error = error
          handleResult(outcome)
          return outcome
        }
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
        if (cause instanceof Exception) {
          throw (Exception) cause
        }
        throw new RuntimeException(cause)
      }
    } catch (ExitRequest e) {
      outcome.exitRequested = true
      outcome.exitCode = e.status()
      return outcome
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
