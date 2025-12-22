package se.alipsa.lca.shell

import groovy.transform.CompileStatic
import org.springframework.beans.BeansException
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@CompileStatic
class ShellCommandExclusionsConfiguration {

  private static final String EMBABEL_SHELL_COMMANDS = "com.embabel.agent.shell.ShellCommands"
  private static final String EMBABEL_SHELL_BEAN = "shellCommands"

  @Bean
  BeanDefinitionRegistryPostProcessor embabelShellCommandExcluder() {
    return new BeanDefinitionRegistryPostProcessor() {
      @Override
      void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        if (!registry.containsBeanDefinition(EMBABEL_SHELL_BEAN)) {
          return
        }
        BeanDefinition definition = registry.getBeanDefinition(EMBABEL_SHELL_BEAN)
        if (EMBABEL_SHELL_COMMANDS == definition.getBeanClassName()) {
          registry.removeBeanDefinition(EMBABEL_SHELL_BEAN)
        }
      }

      @Override
      void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        // No-op
      }
    }
  }
}
