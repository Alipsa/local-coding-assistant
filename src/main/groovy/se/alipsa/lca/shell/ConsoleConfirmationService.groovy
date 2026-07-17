package se.alipsa.lca.shell

import groovy.transform.CompileStatic
import org.springframework.stereotype.Component

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PrintStream
import java.util.Locale

/**
 * Console-backed {@link ConfirmationService} used by the JLine REPL. Prompts on stdout and
 * reads the answer from stdin, preserving the behaviour the shell had before the seam was
 * extracted. The streams are injectable to keep the class unit-testable.
 */
@Component
@CompileStatic
class ConsoleConfirmationService implements ConfirmationService {

  private final InputStream inputStream
  private final PrintStream outputStream

  ConsoleConfirmationService() {
    this(System.in, System.out)
  }

  ConsoleConfirmationService(InputStream inputStream, PrintStream outputStream) {
    this.inputStream = inputStream
    this.outputStream = outputStream
  }

  @Override
  ConfirmationChoice confirm(String prompt) {
    outputStream.print("${prompt?.trim()} [y/N/a]: ")
    outputStream.flush()
    String normalised = readLine()
    if ("a" == normalised) {
      return ConfirmationChoice.ALL
    }
    if ("y" == normalised) {
      return ConfirmationChoice.YES
    }
    ConfirmationChoice.NO
  }

  @Override
  boolean confirmYesNo(String prompt) {
    outputStream.print(prompt)
    outputStream.flush()
    String normalised = readLine()
    normalised == "y" || normalised == "yes"
  }

  private String readLine() {
    BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))
    String response = reader.readLine()
    response != null ? response.trim().toLowerCase(Locale.UK) : ""
  }
}
