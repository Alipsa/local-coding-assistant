package se.alipsa.lca.api

import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import se.alipsa.lca.shell.ShellCommands
import spock.lang.Specification

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class RestSecurityFilterSpec extends Specification {

  ShellCommands commands = Stub() {
    health() >> "ok"
  }
  ShellCommandController controller = new ShellCommandController(commands)

  def "blocks remote access when disabled"() {
    given:
    RestSecurityFilter filter = new RestSecurityFilter(false, false, "", 0)
    MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).addFilters(filter).build()

    expect:
    mvc.perform(get("/api/cli/health")
      .with(remoteAddr("10.0.0.2"))
      .contentType(MediaType.APPLICATION_JSON))
      .andExpect(status().isForbidden())
  }

  def "requires api key when configured"() {
    given:
    RestSecurityFilter filter = new RestSecurityFilter(true, false, "secret", 0)
    MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).addFilters(filter).build()

    expect:
    mvc.perform(get("/api/cli/health")
      .with(remoteAddr("127.0.0.1")))
      .andExpect(status().isUnauthorized())

    mvc.perform(get("/api/cli/health")
      .with(remoteAddr("127.0.0.1"))
      .header("X-API-Key", "secret"))
      .andExpect(status().isOk())
  }

  def "rate limits when configured"() {
    given:
    RestSecurityFilter filter = new RestSecurityFilter(true, false, "", 2)
    MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).addFilters(filter).build()

    when:
    mvc.perform(get("/api/cli/health").with(remoteAddr("127.0.0.1")))
    mvc.perform(get("/api/cli/health").with(remoteAddr("127.0.0.1")))
    def third = mvc.perform(get("/api/cli/health").with(remoteAddr("127.0.0.1")))

    then:
    third.andExpect(status().isTooManyRequests())
  }

  def "requires https when enabled"() {
    given:
    RestSecurityFilter filter = new RestSecurityFilter(true, true, "", 0)
    MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).addFilters(filter).build()

    expect:
    mvc.perform(get("/api/cli/health")
      .with(remoteAddr("10.0.0.2")))
      .andExpect(status().isForbidden())

    mvc.perform(get("/api/cli/health")
      .with(remoteAddr("10.0.0.2"))
      .with(secure(true)))
      .andExpect(status().isOk())
  }

  private static RequestPostProcessor remoteAddr(String addr) {
    return { request ->
      request.remoteAddr = addr
      request
    } as RequestPostProcessor
  }

  private static RequestPostProcessor secure(boolean value) {
    return { request ->
      request.secure = value
      request
    } as RequestPostProcessor
  }
}
