package se.alipsa.lca.repl

import org.jline.reader.EOFError
import org.jline.reader.Parser
import spock.lang.Specification

class FencedPasteParserSpec extends Specification {

  FencedPasteParser parser = new FencedPasteParser()

  def "an unterminated /paste block keeps signalling incomplete input"() {
    given:
    String line = "/paste"

    when:
    parser.parse(line, line.length(), Parser.ParseContext.ACCEPT_LINE)

    then:
    thrown(EOFError)
  }

  def "an unterminated /paste block with content still keeps signalling incomplete input"() {
    given:
    String line = "/paste\nsome content"

    when:
    parser.parse(line, line.length(), Parser.ParseContext.ACCEPT_LINE)

    then:
    thrown(EOFError)
  }

  def "a closed /paste block does not throw"() {
    given:
    String line = "/paste\nsome content\n/end"

    when:
    def result = parser.parse(line, line.length(), Parser.ParseContext.ACCEPT_LINE)

    then:
    noExceptionThrown()
    result.line() == line
  }

  def "a closed ^^^ block does not throw"() {
    given:
    String line = "^^^\nsome content\n^^^"

    when:
    def result = parser.parse(line, line.length(), Parser.ParseContext.ACCEPT_LINE)

    then:
    noExceptionThrown()
    result.line() == line
  }

  def "a bare single-line ^^^ opener (no newline yet) is not treated as already closed"() {
    given:
    String line = "^^^"

    when:
    parser.parse(line, line.length(), Parser.ParseContext.ACCEPT_LINE)

    then:
    thrown(EOFError)
  }

  def "an unterminated block reports the closer, not the opener, as missing"() {
    given:
    String line = "/paste"

    when:
    parser.parse(line, line.length(), Parser.ParseContext.ACCEPT_LINE)

    then:
    EOFError e = thrown(EOFError)
    e.missing == "/end"
  }

  def "a mismatched closer does not close the block"() {
    given:
    String line = "/paste\n^^^"

    when:
    parser.parse(line, line.length(), Parser.ParseContext.ACCEPT_LINE)

    then:
    thrown(EOFError)
  }

  def "an unmatched quote inside pasted content does not reopen a block that just closed"() {
    given:
    parser.setEofOnUnclosedQuote(true)
    String line = "/paste\nit's unmatched\n/end"

    when:
    def result = parser.parse(line, line.length(), Parser.ParseContext.ACCEPT_LINE)

    then:
    noExceptionThrown()
    result.line() == line
  }

  def "input with no fence still gets DefaultParser's own unclosed-quote continuation"() {
    given:
    parser.setEofOnUnclosedQuote(true)
    String line = 'echo "unclosed'

    when:
    parser.parse(line, line.length(), Parser.ParseContext.ACCEPT_LINE)

    then:
    thrown(EOFError)
  }

  def "ordinary single-line input with no fence parses normally"() {
    given:
    String line = "/status"

    when:
    def result = parser.parse(line, line.length(), Parser.ParseContext.ACCEPT_LINE)

    then:
    noExceptionThrown()
    result.line() == line
  }
}
