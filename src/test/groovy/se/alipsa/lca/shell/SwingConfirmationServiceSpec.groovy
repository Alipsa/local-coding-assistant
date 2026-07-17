package se.alipsa.lca.shell

import spock.lang.Specification
import spock.lang.Unroll

import javax.swing.JOptionPane
import javax.swing.SwingUtilities

class SwingConfirmationServiceSpec extends Specification {

  @Unroll
  def "choiceFor maps JOptionPane result #result to #expected"() {
    expect:
    SwingConfirmationService.choiceFor(result) == expected

    where:
    result                        || expected
    0                              || ConfirmationChoice.YES
    2                              || ConfirmationChoice.ALL
    1                              || ConfirmationChoice.NO
    JOptionPane.CLOSED_OPTION      || ConfirmationChoice.NO
  }

  def "failClosed restores the interrupt flag when the failure was an interruption"() {
    when:
    int result = SwingConfirmationService.failClosed(new InterruptedException("test"), true)

    then:
    result == JOptionPane.CLOSED_OPTION
    Thread.interrupted() // clears the flag as a side effect of reading it; assert it was set
  }

  def "failClosed leaves the interrupt flag untouched for a non-interruption failure"() {
    given:
    assert !Thread.currentThread().isInterrupted()

    when:
    int result = SwingConfirmationService.failClosed(new RuntimeException("boom"), false)

    then:
    result == JOptionPane.CLOSED_OPTION
    !Thread.currentThread().isInterrupted()
  }

  @Unroll
  def "stripYesNoSuffix removes a trailing #description"() {
    expect:
    SwingConfirmationService.stripYesNoSuffix(input) == expected

    where:
    description             | input                                    || expected
    "(y/n): style suffix"   | "Really do this? (y/n): "                || "Really do this?"
    "[y/n] bracket suffix"  | "Really do this? [y/n]"                  || "Really do this?"
    "mixed-case suffix"     | "Proceed? (Y/N):"                        || "Proceed?"
    "no suffix at all"      | "Just a plain question?"                 || "Just a plain question?"
    "null prompt"           | null                                      || null
  }

  def "confirm invoked already on the EDT runs the dialog directly, without invokeAndWait"() {
    given:
    RecordingConfirmationService service = new RecordingConfirmationService(stubResult: 0)

    when:
    ConfirmationChoice choice = null
    SwingUtilities.invokeAndWait {
      choice = service.confirm("Proceed?")
    }

    then:
    choice == ConfirmationChoice.YES
    service.dialogRanOnEdt == [true]
  }

  def "confirm invoked off the EDT hands the dialog to the EDT via invokeAndWait"() {
    given:
    RecordingConfirmationService service = new RecordingConfirmationService(stubResult: 1)

    when: "called directly from this (non-EDT) test thread"
    ConfirmationChoice choice = service.confirm("Proceed?")

    then:
    choice == ConfirmationChoice.NO
    service.dialogRanOnEdt == [true]
  }

  def "confirmYesNo strips the trailing (y/n) suffix before showing the dialog"() {
    given:
    RecordingConfirmationService service = new RecordingConfirmationService(stubResult: 0)

    when:
    boolean result = service.confirmYesNo("Really? (y/n): ")

    then:
    result
    service.lastMessage == "Really?"
  }

  private static class RecordingConfirmationService extends SwingConfirmationService {
    int stubResult
    List<Boolean> dialogRanOnEdt = []
    String lastMessage

    @Override
    protected int showRealDialog(String message, String title, Object[] options, int defaultIndex) {
      dialogRanOnEdt << SwingUtilities.isEventDispatchThread()
      lastMessage = message
      stubResult
    }
  }
}
