package se.alipsa.lca.shell

import org.springframework.shell.context.InteractionMode
import org.springframework.shell.context.ShellContext
import spock.lang.Specification

class ShellOutputStylerSpec extends Specification {

  def "colourise wraps output in light yellow"() {
    given:
    ShellOutputStyler styler = new ShellOutputStyler()

    when:
    String styled = styler.colourise("Hello")

    then:
    styled == "${ShellOutputStyler.LIGHT_YELLOW}Hello${ShellOutputStyler.RESET}"
  }

  def "colourise restores light yellow after reset"() {
    given:
    ShellOutputStyler styler = new ShellOutputStyler()
    String value = "Top ${ShellOutputStyler.RESET}Inner"

    when:
    String styled = styler.colourise(value)

    then:
    styled == "${ShellOutputStyler.LIGHT_YELLOW}Top ${ShellOutputStyler.RESET}" +
      "${ShellOutputStyler.LIGHT_YELLOW}Inner${ShellOutputStyler.RESET}"
  }

  def "shouldColour requires interactive pty"() {
    given:
    ShellOutputStyler styler = new ShellOutputStyler()
    ShellContext interactive = Stub(ShellContext) {
      getInteractionMode() >> InteractionMode.INTERACTIVE
      hasPty() >> true
    }
    ShellContext nonInteractive = Stub(ShellContext) {
      getInteractionMode() >> InteractionMode.NONINTERACTIVE
      hasPty() >> true
    }
    ShellContext noPty = Stub(ShellContext) {
      getInteractionMode() >> InteractionMode.INTERACTIVE
      hasPty() >> false
    }

    expect:
    styler.shouldColour(interactive)
    !styler.shouldColour(nonInteractive)
    !styler.shouldColour(noPty)
  }
}
