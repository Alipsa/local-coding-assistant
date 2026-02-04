package se.alipsa.lca.validation

import groovy.transform.CompileStatic
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.UserInterruptException
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.stereotype.Component

/**
 * Manages interactive clarification dialogs with the user to gather more context
 * for ambiguous implementation requests.
 * Only available when LineReader is present (interactive REPL mode).
 */
@Component
@ConditionalOnBean(LineReader)
@CompileStatic
class ClarificationDialog {

  private final LineReader lineReader
  private final ValidationSettings settings

  ClarificationDialog(LineReader lineReader, ValidationSettings settings) {
    this.lineReader = lineReader
    this.settings = settings
  }

  /**
   * Asks clarifying questions based on the validation result.
   *
   * @param validation The validation result indicating what needs clarification
   * @param originalPrompt The original user prompt
   * @return ClarificationResult with enriched prompt or cancellation
   */
  ClarificationResult ask(ValidationResult validation, String originalPrompt) {
    if (!validation.needsClarification) {
      return ClarificationResult.success(originalPrompt)
    }

    println("\nI need more information to implement this request.")
    println("Reason: ${validation.reason}\n")

    // If we have multiple choice questions, show them
    if (validation.questions.size() >= 2) {
      return askMultiChoice(validation, originalPrompt)
    } else if (validation.questions.size() == 1) {
      return askOpenEnded(validation, originalPrompt)
    } else {
      return askOpenEnded(validation, originalPrompt)
    }
  }

  /**
   * Asks a multi-choice question
   */
  private ClarificationResult askMultiChoice(ValidationResult validation, String originalPrompt) {
    String detectedType = validation.detectedType ?: "item"

    println("What type of ${detectedType} do you want to create?")
    validation.questions.eachWithIndex { String option, int idx ->
      println("  ${idx + 1}) ${option}")
    }
    println()

    try {
      String choice = lineReader.readLine("Your choice (1-${validation.questions.size()}, or 'c' to cancel): ")
      choice = choice?.trim()

      if (choice == null || choice.isEmpty()) {
        return ClarificationResult.cancelled()
      }

      if (choice.equalsIgnoreCase('c')) {
        return ClarificationResult.cancelled()
      }

      // Try to parse as number
      try {
        int choiceNum = Integer.parseInt(choice)
        if (choiceNum < 1 || choiceNum > validation.questions.size()) {
          println("Invalid choice. Please enter a number between 1 and ${validation.questions.size()}.")
          return ClarificationResult.cancelled()
        }

        String selectedOption = validation.questions[choiceNum - 1]

        // If "Other (specify)", ask for details
        if (selectedOption.toLowerCase().contains('other')) {
          return askFollowUpDetails(validation, originalPrompt, selectedOption)
        }

        // Ask follow-up questions for the selected type
        return askFollowUpDetails(validation, originalPrompt, selectedOption)

      } catch (NumberFormatException e) {
        // User entered text instead of number - use as enriched prompt
        String enrichedPrompt = "${originalPrompt} (type: ${choice})"
        return ClarificationResult.success(enrichedPrompt, ['userInput': choice])
      }

    } catch (UserInterruptException | EndOfFileException e) {
      return ClarificationResult.cancelled()
    }
  }

  /**
   * Asks an open-ended question
   */
  private ClarificationResult askOpenEnded(ValidationResult validation, String originalPrompt) {
    String question = validation.questions.isEmpty() ?
      "Please provide more details:" :
      validation.questions[0]

    println(question)
    println()

    try {
      String response = lineReader.readLine("Enter details (or 'c' to cancel): ")
      response = response?.trim()

      if (response == null || response.isEmpty()) {
        return ClarificationResult.cancelled()
      }

      if (response.equalsIgnoreCase('c')) {
        return ClarificationResult.cancelled()
      }

      String enrichedPrompt = "${originalPrompt}\n\nAdditional context: ${response}"
      return ClarificationResult.success(enrichedPrompt, ['details': response])

    } catch (UserInterruptException | EndOfFileException e) {
      return ClarificationResult.cancelled()
    }
  }

  /**
   * Asks follow-up questions after user selects a type
   */
  private ClarificationResult askFollowUpDetails(ValidationResult validation, String originalPrompt, String selectedType) {
    println("\nYou selected: ${selectedType}")
    println("\nPlease provide additional context:")
    println("  - Where should the code be placed?")
    println("  - What specific functionality is needed?")
    println("  - Any other relevant details?")
    println()

    try {
      String details = lineReader.readLine("Enter details (or 'c' to cancel): ")
      details = details?.trim()

      if (details == null || details.isEmpty()) {
        return ClarificationResult.cancelled()
      }

      if (details.equalsIgnoreCase('c')) {
        return ClarificationResult.cancelled()
      }

      String enrichedPrompt = """${originalPrompt}

Type: ${selectedType}
Details: ${details}"""

      return ClarificationResult.success(enrichedPrompt, [
        'type': selectedType,
        'details': details
      ])

    } catch (UserInterruptException | EndOfFileException e) {
      return ClarificationResult.cancelled()
    }
  }

}
