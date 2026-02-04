package se.alipsa.lca.validation

import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.UserInterruptException
import spock.lang.Specification
import spock.lang.Subject

class ClarificationDialogSpec extends Specification {

  LineReader lineReader
  ValidationSettings settings
  @Subject
  ClarificationDialog dialog

  def setup() {
    lineReader = Mock(LineReader)
    settings = new ValidationSettings(
      enabled: true,
      skipInBatch: true,
      minPromptLength: 15,
      maxClarificationRounds: 2,
      useContextResolver: true
    )
    dialog = new ClarificationDialog(lineReader, settings)
  }

  def "ask returns success when validation does not need clarification"() {
    given:
    def validation = ValidationResult.ok()

    when:
    def result = dialog.ask(validation, "original prompt")

    then:
    !result.cancelled
    result.enrichedPrompt == "original prompt"
  }

  def "ask returns cancelled when user enters 'c'"() {
    given:
    def validation = ValidationResult.needsClarification(
      "Test reason",
      ["Option 1", "Option 2"]
    )

    when:
    def result = dialog.ask(validation, "write a query")

    then:
    1 * lineReader.readLine(_) >> "c"
    result.cancelled
  }

  def "ask returns cancelled when user presses Ctrl+D (EOF)"() {
    given:
    def validation = ValidationResult.needsClarification(
      "Test reason",
      ["Option 1", "Option 2"]
    )

    when:
    def result = dialog.ask(validation, "write a query")

    then:
    1 * lineReader.readLine(_) >> { throw new EndOfFileException() }
    result.cancelled
  }

  def "ask returns cancelled when user presses Ctrl+C (interrupt)"() {
    given:
    def validation = ValidationResult.needsClarification(
      "Test reason",
      ["Option 1", "Option 2"]
    )

    when:
    def result = dialog.ask(validation, "write a query")

    then:
    1 * lineReader.readLine(_) >> { throw new UserInterruptException("") }
    result.cancelled
  }

  def "ask handles multi-choice selection and follow-up"() {
    given:
    def validation = ValidationResult.needsClarification(
      "Generic request needs specifics",
      ["SQL query for database", "GraphQL query for API", "Search query", "Other (specify)"],
      "query"
    )

    when:
    def result = dialog.ask(validation, "write a query")

    then:
    1 * lineReader.readLine(_) >> "1"
    1 * lineReader.readLine(_) >> "in UserRepository.groovy to fetch users by email"
    !result.cancelled
    result.enrichedPrompt.contains("write a query")
    result.enrichedPrompt.contains("SQL query for database")
    result.enrichedPrompt.contains("UserRepository.groovy")
    result.answers['type'] == "SQL query for database"
    result.answers['details'] == "in UserRepository.groovy to fetch users by email"
  }

  def "ask handles user selecting 'Other' option"() {
    given:
    def validation = ValidationResult.needsClarification(
      "Generic request needs specifics",
      ["SQL query for database", "GraphQL query for API", "Other (specify)"],
      "query"
    )

    when:
    def result = dialog.ask(validation, "write a query")

    then:
    1 * lineReader.readLine(_) >> "3"
    1 * lineReader.readLine(_) >> "JPA query for finding users"
    !result.cancelled
    result.enrichedPrompt.contains("Other (specify)")
    result.enrichedPrompt.contains("JPA query")
  }

  def "ask handles user entering text instead of number"() {
    given:
    def validation = ValidationResult.needsClarification(
      "Generic request needs specifics",
      ["Option 1", "Option 2"],
      "query"
    )

    when:
    def result = dialog.ask(validation, "write a query")

    then:
    1 * lineReader.readLine(_) >> "I want a custom MongoDB query"
    !result.cancelled
    result.enrichedPrompt.contains("write a query")
    result.enrichedPrompt.contains("custom MongoDB query")
    result.answers['userInput'] == "I want a custom MongoDB query"
  }

  def "ask returns cancelled when user enters invalid choice number"() {
    given:
    def validation = ValidationResult.needsClarification(
      "Generic request needs specifics",
      ["Option 1", "Option 2"],
      "query"
    )

    when:
    def result = dialog.ask(validation, "write a query")

    then:
    1 * lineReader.readLine(_) >> "99"
    result.cancelled
  }

  def "ask handles open-ended question with single question"() {
    given:
    def validation = ValidationResult.needsClarification(
      "No target location specified",
      ["Where should the code be placed?"]
    )

    when:
    def result = dialog.ask(validation, "add a method")

    then:
    1 * lineReader.readLine(_) >> "in UserService.groovy class"
    !result.cancelled
    result.enrichedPrompt.contains("add a method")
    result.enrichedPrompt.contains("UserService.groovy")
    result.answers['details'] == "in UserService.groovy class"
  }

  def "ask returns cancelled for open-ended when user enters 'c'"() {
    given:
    def validation = ValidationResult.needsClarification(
      "No target location specified",
      ["Where should the code be placed?"]
    )

    when:
    def result = dialog.ask(validation, "add a method")

    then:
    1 * lineReader.readLine(_) >> "c"
    result.cancelled
  }

  def "ask returns cancelled for open-ended when user enters empty string"() {
    given:
    def validation = ValidationResult.needsClarification(
      "No target location specified",
      ["Where should the code be placed?"]
    )

    when:
    def result = dialog.ask(validation, "add a method")

    then:
    1 * lineReader.readLine(_) >> ""
    result.cancelled
  }

  def "ask handles follow-up cancellation"() {
    given:
    def validation = ValidationResult.needsClarification(
      "Generic request needs specifics",
      ["SQL query for database", "GraphQL query for API"],
      "query"
    )

    when:
    def result = dialog.ask(validation, "write a query")

    then:
    1 * lineReader.readLine(_) >> "1"
    1 * lineReader.readLine(_) >> "c"
    result.cancelled
  }

  def "ask handles null response from lineReader"() {
    given:
    def validation = ValidationResult.needsClarification(
      "Test reason",
      ["Option 1", "Option 2"]
    )

    when:
    def result = dialog.ask(validation, "write a query")

    then:
    1 * lineReader.readLine(_) >> null
    result.cancelled
  }

  def "ask enriches prompt correctly for choice selection"() {
    given:
    def validation = ValidationResult.needsClarification(
      "Generic request needs specifics",
      ["Unit test (Spock)", "Integration test"],
      "test"
    )

    when:
    def result = dialog.ask(validation, "create a test")

    then:
    1 * lineReader.readLine(_) >> "1"
    1 * lineReader.readLine(_) >> "for UserService in src/test/groovy/UserServiceSpec.groovy"
    !result.cancelled
    result.enrichedPrompt.contains("create a test")
    result.enrichedPrompt.contains("Unit test (Spock)")
    result.enrichedPrompt.contains("UserService")
    result.answers.size() == 2
  }

}
