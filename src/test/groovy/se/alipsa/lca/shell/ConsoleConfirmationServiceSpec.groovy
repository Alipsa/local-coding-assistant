package se.alipsa.lca.shell

import spock.lang.Specification

import java.nio.charset.StandardCharsets

class ConsoleConfirmationServiceSpec extends Specification {

  private static ConsoleConfirmationService serviceFor(String input) {
    InputStream inStream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))
    PrintStream outStream = new PrintStream(new ByteArrayOutputStream())
    new ConsoleConfirmationService(inStream, outStream)
  }

  def "confirm maps answers to choices"() {
    expect:
    serviceFor(input).confirm("do it?") == choice

    where:
    input   || choice
    "y\n"   || ConfirmationChoice.YES
    "a\n"   || ConfirmationChoice.ALL
    "n\n"   || ConfirmationChoice.NO
    "\n"    || ConfirmationChoice.NO
    ""      || ConfirmationChoice.NO
    "Y\n"   || ConfirmationChoice.YES
  }

  def "confirmYesNo is true only for affirmative answers"() {
    expect:
    serviceFor(input).confirmYesNo("web search? (y/n): ") == expected

    where:
    input     || expected
    "y\n"     || true
    "yes\n"   || true
    "YES\n"   || true
    "n\n"     || false
    "\n"      || false
    "maybe\n" || false
  }
}
