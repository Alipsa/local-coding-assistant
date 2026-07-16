package se.alipsa.lca.shell

/**
 * Abstraction over how the assistant asks the user to confirm an action.
 *
 * <p>The REPL front-end reads from the console ({@link ConsoleConfirmationService}); the
 * Swing GUI shows a modal dialog ({@code SwingConfirmationService}). Extracting this seam
 * keeps {@link ShellCommands} free of any assumption about {@code System.in}.
 */
interface ConfirmationService {

  /**
   * Ask the user to confirm a destructive action, offering yes / no / all.
   *
   * @param prompt a human-readable description of the action being confirmed
   * @return the user's choice; never {@code null}
   */
  ConfirmationChoice confirm(String prompt)

  /**
   * Ask the user a simple yes/no question.
   *
   * @param prompt the full question to present (including any trailing suffix such as "(y/n): ")
   * @return {@code true} only when the user answers affirmatively
   */
  boolean confirmYesNo(String prompt)
}
