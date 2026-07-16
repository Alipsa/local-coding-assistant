package se.alipsa.lca.shell

import groovy.transform.CompileStatic
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component

import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import java.util.concurrent.atomic.AtomicInteger

/**
 * Swing-backed {@link ConfirmationService} used by the GUI. Shows a modal dialog on the
 * Event Dispatch Thread and blocks the calling (background) thread until the user answers,
 * so agent work waits for the confirmation just as the console prompt did.
 *
 * <p>Marked {@code @Primary} and gated on {@code lca.gui.enabled=true} so it wins over the
 * console implementation only when the GUI is active.
 */
@Component
@Primary
@CompileStatic
@ConditionalOnProperty(name = "lca.gui.enabled", havingValue = "true")
class SwingConfirmationService implements ConfirmationService {

  @Override
  ConfirmationChoice confirm(String prompt) {
    Object[] options = ["Yes", "No", "Yes to all"] as Object[]
    int result = showOptionDialog(prompt?.trim() ?: "Confirm this action?", "Confirm action", options, 1)
    if (result == 0) {
      return ConfirmationChoice.YES
    }
    if (result == 2) {
      return ConfirmationChoice.ALL
    }
    ConfirmationChoice.NO
  }

  @Override
  boolean confirmYesNo(String prompt) {
    Object[] options = ["Yes", "No"] as Object[]
    showOptionDialog(prompt, "Confirm", options, 1) == 0
  }

  private static int showOptionDialog(String message, String title, Object[] options, int defaultIndex) {
    AtomicInteger holder = new AtomicInteger(JOptionPane.CLOSED_OPTION)
    Runnable task = {
      int choice = JOptionPane.showOptionDialog(
        null, message, title,
        JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
        null, options, options[defaultIndex])
      holder.set(choice)
    } as Runnable
    try {
      if (SwingUtilities.isEventDispatchThread()) {
        task.run()
      } else {
        SwingUtilities.invokeAndWait(task)
      }
    } catch (Exception ignored) {
      return JOptionPane.CLOSED_OPTION
    }
    holder.get()
  }
}
