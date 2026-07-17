package se.alipsa.lca.shell

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component

import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

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

  private static final Logger log = LoggerFactory.getLogger(SwingConfirmationService)
  // Matches a trailing console-style "(y/n): " (or "[y/n]", extra spacing, no colon, etc.) so it
  // isn't echoed verbatim next to the dialog's own Yes/No buttons.
  private static final Pattern YES_NO_SUFFIX = Pattern.compile(/(?i)\s*[\(\[]\s*y\s*\/\s*n\s*[\)\]]\s*:?\s*$/)

  @Override
  ConfirmationChoice confirm(String prompt) {
    Object[] options = ["Yes", "No", "Yes to all"] as Object[]
    int result = showOptionDialog(prompt?.trim() ?: "Confirm this action?", "Confirm action", options, 1)
    choiceFor(result)
  }

  @Override
  boolean confirmYesNo(String prompt) {
    Object[] options = ["Yes", "No"] as Object[]
    showOptionDialog(stripYesNoSuffix(prompt), "Confirm", options, 1) == 0
  }

  /** Strips a trailing console-style "(y/n): " suffix, cosmetically confusing next to real buttons. */
  @PackageScope
  static String stripYesNoSuffix(String prompt) {
    prompt == null ? prompt : YES_NO_SUFFIX.matcher(prompt).replaceFirst("")
  }

  /** Maps a {@code JOptionPane} option index (or {@code CLOSED_OPTION}) to a choice. */
  @PackageScope
  static ConfirmationChoice choiceFor(int result) {
    if (result == 0) {
      return ConfirmationChoice.YES
    }
    if (result == 2) {
      return ConfirmationChoice.ALL
    }
    ConfirmationChoice.NO
  }

  private int showOptionDialog(String message, String title, Object[] options, int defaultIndex) {
    AtomicInteger holder = new AtomicInteger(JOptionPane.CLOSED_OPTION)
    Runnable task = {
      holder.set(showRealDialog(message, title, options, defaultIndex))
    } as Runnable
    try {
      if (SwingUtilities.isEventDispatchThread()) {
        task.run()
      } else {
        SwingUtilities.invokeAndWait(task)
      }
    } catch (InterruptedException e) {
      // The calling thread was interrupted (e.g. shutdown/cancellation) while waiting for the
      // EDT to answer. Restore the interrupt flag so callers upstream see it and can unwind,
      // rather than silently continuing as if the user had answered "No".
      return failClosed(e, true)
    } catch (Exception e) {
      return failClosed(e, false)
    }
    holder.get()
  }

  /**
   * Isolates the actual modal dialog call as a seam: tests override this to return a canned
   * option index instead of a real {@code JOptionPane} popping up, letting them exercise the
   * EDT-detection/{@code invokeAndWait} branching in {@link #showOptionDialog} deterministically.
   */
  protected int showRealDialog(String message, String title, Object[] options, int defaultIndex) {
    JOptionPane.showOptionDialog(
      null, message, title,
      JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
      null, options, options[defaultIndex])
  }

  /**
   * Fail closed (CLOSED_OPTION → NO), recording why so a real dialog/headless failure is
   * distinguishable from a genuine user "No". Restores the interrupt flag when {@code interrupted}.
   */
  @PackageScope
  static int failClosed(Exception e, boolean interrupted) {
    if (interrupted) {
      Thread.currentThread().interrupt()
    }
    log.warn("Confirmation dialog failed to display; treating as declined", e)
    JOptionPane.CLOSED_OPTION
  }
}
