package se.alipsa.lca.shell

import groovy.transform.CompileStatic
import org.jline.terminal.Terminal
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.shell.ResultHandler
import org.springframework.shell.ResultHandlerService
import org.springframework.shell.context.ShellContext
import org.springframework.shell.result.GenericResultHandlerService

@Configuration
@CompileStatic
class ShellOutputConfiguration {

  @Bean(name = "lcaResultHandlerService")
  @Primary
  ResultHandlerService lcaResultHandlerService(
    Set<ResultHandler<?>> handlers,
    Terminal terminal,
    ShellContext shellContext
  ) {
    GenericResultHandlerService service = new GenericResultHandlerService()
    handlers.each { ResultHandler<?> handler ->
      service.addResultHandler(handler)
    }
    ShellOutputStyler styler = new ShellOutputStyler()
    service.addResultHandler(String, new LcaStringResultHandler(terminal, shellContext, styler))
    service
  }
}
