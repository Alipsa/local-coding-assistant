package se.alipsa.lca.shell

import spock.lang.Specification
import spock.lang.Unroll

import javax.swing.JOptionPane

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
}
