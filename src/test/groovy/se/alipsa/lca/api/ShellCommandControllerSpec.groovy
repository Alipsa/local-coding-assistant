package se.alipsa.lca.api

import groovy.json.JsonOutput
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean
import se.alipsa.lca.agent.PersonaMode
import se.alipsa.lca.intent.IntentCommandRouter
import se.alipsa.lca.intent.IntentRoutingPlan
import se.alipsa.lca.review.ReviewSeverity
import se.alipsa.lca.shell.ShellCommands
import spock.lang.Specification

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class ShellCommandControllerSpec extends Specification {

  ShellCommands commands = Mock(ShellCommands)
  IntentCommandRouter router = Mock(IntentCommandRouter)
  LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean()
  MockMvc mvc

  def setup() {
    validator.afterPropertiesSet()
    mvc = MockMvcBuilders.standaloneSetup(new ShellCommandController(commands, router))
      .setValidator(validator)
      .build()
  }

  def "chat endpoint delegates with defaults"() {
    when:
    def response = mvc.perform(post("/api/cli/chat")
      .contentType(MediaType.APPLICATION_JSON)
      .content(JsonOutput.toJson([prompt: "hello"])))

    then:
    response.andExpect(status().isOk())
    1 * commands.chat("hello", "default", PersonaMode.CODER, null, null, null, null, null, false) >> "ok"
  }

  def "plan endpoint delegates with defaults"() {
    when:
    def response = mvc.perform(post("/api/cli/plan")
      .contentType(MediaType.APPLICATION_JSON)
      .content(JsonOutput.toJson([prompt: "plan it"])))

    then:
    response.andExpect(status().isOk())
    1 * commands.plan("plan it", "default", PersonaMode.ARCHITECT, null, null, null, null, null) >> "ok"
  }

  def "route endpoint delegates to intent router"() {
    given:
    router.route("route it") >> new IntentRoutingPlan(["/chat --prompt \"route it\""], 0.4d, "fallback")

    when:
    def response = mvc.perform(post("/api/cli/route")
      .contentType(MediaType.APPLICATION_JSON)
      .content(JsonOutput.toJson([prompt: "route it"])))

    then:
    response.andExpect(status().isOk())
    1 * router.route("route it")
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
    1 * commands.chat("draft", "s1", PersonaMode.ARCHITECT, null, null, null, null, null, false) >> "sent"
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

  def "revert endpoint requires confirmation when not a dry run"() {
    when:
    def response = mvc.perform(post("/api/cli/revert")
      .contentType(MediaType.APPLICATION_JSON)
      .content(JsonOutput.toJson([filePath: "file.txt", dryRun: false, confirm: false])) )

    then:
    response.andExpect(status().isBadRequest())
    0 * commands.revert(_, _, _)
  }

  def "chat requires non-blank prompt"() {
    when:
    def response = mvc.perform(post("/api/cli/chat")
      .contentType(MediaType.APPLICATION_JSON)
      .content(JsonOutput.toJson([prompt: " "])))

    then:
    response.andExpect(status().isBadRequest())
    0 * commands.chat(_, _, _, _, _, _, _, _)
  }

  def "plan requires non-blank prompt"() {
    when:
    def response = mvc.perform(post("/api/cli/plan")
      .contentType(MediaType.APPLICATION_JSON)
      .content(JsonOutput.toJson([prompt: " "])))

    then:
    response.andExpect(status().isBadRequest())
    0 * commands.plan(_, _, _, _, _, _, _, _)
  }

  def "route requires non-blank prompt"() {
    when:
    def response = mvc.perform(post("/api/cli/route")
      .contentType(MediaType.APPLICATION_JSON)
      .content(JsonOutput.toJson([prompt: " "])))

    then:
    response.andExpect(status().isBadRequest())
    0 * router.route(_)
  }
}
