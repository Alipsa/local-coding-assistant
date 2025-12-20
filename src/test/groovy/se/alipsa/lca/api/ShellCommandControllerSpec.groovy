package se.alipsa.lca.api

import groovy.json.JsonOutput
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import se.alipsa.lca.agent.PersonaMode
import se.alipsa.lca.review.ReviewSeverity
import se.alipsa.lca.shell.ShellCommands
import spock.lang.Specification

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class ShellCommandControllerSpec extends Specification {

  ShellCommands commands = Mock(ShellCommands)
  MockMvc mvc = MockMvcBuilders.standaloneSetup(new ShellCommandController(commands)).build()

  def "chat endpoint delegates with defaults"() {
    when:
    def response = mvc.perform(post("/api/cli/chat")
      .contentType(MediaType.APPLICATION_JSON)
      .content(JsonOutput.toJson([prompt: "hello"])))

    then:
    response.andExpect(status().isOk())
    1 * commands.chat("hello", "default", PersonaMode.CODER, null, null, null, null, null) >> "ok"
  }

  def "review endpoint delegates with options"() {
    when:
    def response = mvc.perform(post("/api/cli/review")
      .contentType(MediaType.APPLICATION_JSON)
      .content(JsonOutput.toJson([
        prompt: "check",
        code: "x",
        paths: ["src/App.groovy"],
        minSeverity: "HIGH",
        noColor: true,
        logReview: false
      ])))

    then:
    response.andExpect(status().isOk())
    1 * commands.review(
      "x",
      "check",
      "default",
      null,
      null,
      null,
      null,
      ["src/App.groovy"],
      false,
      ReviewSeverity.HIGH,
      true,
      false,
      false,
      false
    ) >> "review"
  }

  def "git push endpoint uses confirm flag"() {
    when:
    def response = mvc.perform(post("/api/cli/git-push")
      .contentType(MediaType.APPLICATION_JSON)
      .content(JsonOutput.toJson([force: true, confirm: true])))

    then:
    response.andExpect(status().isOk())
    1 * commands.gitPush(true, true) >> "pushed"
  }

  def "tree endpoint maps query params"() {
    when:
    def response = mvc.perform(get("/api/cli/tree")
      .param("depth", "2")
      .param("dirsOnly", "true")
      .param("maxEntries", "50"))

    then:
    response.andExpect(status().isOk())
    1 * commands.tree(2, true, 50) >> "tree"
  }

  def "edit endpoint sends prompt when send is true"() {
    when:
    def response = mvc.perform(post("/api/cli/edit")
      .contentType(MediaType.APPLICATION_JSON)
      .content(JsonOutput.toJson([seed: "draft", send: true, session: "s1", persona: "ARCHITECT"])))

    then:
    response.andExpect(status().isOk())
    1 * commands.chat("draft", "s1", PersonaMode.ARCHITECT, null, null, null, null, null) >> "sent"
  }

  def "run endpoint requires confirmation"() {
    when:
    def response = mvc.perform(post("/api/cli/run")
      .contentType(MediaType.APPLICATION_JSON)
      .content(JsonOutput.toJson([command: "echo hi"])))

    then:
    response.andExpect(status().isBadRequest())
    0 * commands.runCommand(_, _, _, _, _, _)
  }
}
