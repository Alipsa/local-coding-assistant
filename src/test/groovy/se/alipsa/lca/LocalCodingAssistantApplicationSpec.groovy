package se.alipsa.lca

import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import spock.lang.Specification

class LocalCodingAssistantApplicationSpec extends Specification {

  private static final String HEADLESS_PROPERTY = "java.awt.headless"

  String originalHeadless

  def setup() {
    originalHeadless = System.getProperty(HEADLESS_PROPERTY)
    System.clearProperty(HEADLESS_PROPERTY)
  }

  def cleanup() {
    if (originalHeadless == null) {
      System.clearProperty(HEADLESS_PROPERTY)
    } else {
      System.setProperty(HEADLESS_PROPERTY, originalHeadless)
    }
  }

  private static StandardEnvironment environmentWith(Map<String, Object> properties) {
    StandardEnvironment environment = new StandardEnvironment()
    environment.propertySources.addFirst(new MapPropertySource("test", properties))
    environment
  }

  def "flips headless off when lca.gui.enabled is true via a source other than -D, e.g. application.properties"() {
    given:
    // No -D flag involved at all: the property arrives purely through the resolved Environment,
    // mirroring an application.properties entry, a profile, or the LCA_GUI_ENABLED env var.
    def environment = environmentWith([ "lca.gui.enabled": "true" ])

    when:
    LocalCodingAssistantApplication.configureHeadless(environment)

    then:
    System.getProperty(HEADLESS_PROPERTY) == "false"
  }

  def "leaves headless untouched when lca.gui.enabled is false or absent"() {
    given:
    def environment = environmentWith(properties)

    when:
    LocalCodingAssistantApplication.configureHeadless(environment)

    then:
    System.getProperty(HEADLESS_PROPERTY) == null

    where:
    properties << [[:], [ "lca.gui.enabled": "false" ]]
  }
}
