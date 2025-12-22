package se.alipsa.lca.shell

import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.GenericBeanDefinition
import spock.lang.Specification

class ShellCommandExclusionsConfigurationSpec extends Specification {

  def "removes embabel shellCommands bean definition"() {
    given:
    DefaultListableBeanFactory registry = new DefaultListableBeanFactory()
    GenericBeanDefinition definition = new GenericBeanDefinition()
    definition.setBeanClassName("com.embabel.agent.shell.ShellCommands")
    registry.registerBeanDefinition("shellCommands", definition)
    BeanDefinitionRegistryPostProcessor processor =
      new ShellCommandExclusionsConfiguration().embabelShellCommandExcluder()

    when:
    processor.postProcessBeanDefinitionRegistry(registry)

    then:
    !registry.containsBeanDefinition("shellCommands")
  }

  def "retains non-embabel shellCommands bean definition"() {
    given:
    DefaultListableBeanFactory registry = new DefaultListableBeanFactory()
    GenericBeanDefinition definition = new GenericBeanDefinition()
    definition.setBeanClassName("se.alipsa.lca.shell.ShellCommands")
    registry.registerBeanDefinition("shellCommands", definition)
    BeanDefinitionRegistryPostProcessor processor =
      new ShellCommandExclusionsConfiguration().embabelShellCommandExcluder()

    when:
    processor.postProcessBeanDefinitionRegistry(registry)

    then:
    registry.containsBeanDefinition("shellCommands")
  }
}
