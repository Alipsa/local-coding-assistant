package se.alipsa.lca.team

import groovy.json.JsonBuilder
import spock.lang.Specification

class ArchitectPlanSpec extends Specification {

  def "fromJson parses a complete plan"() {
    given:
    String json = new JsonBuilder([
      summary: "Add logging to the service layer",
      steps: [
        [
          order: 1,
          description: "Add logger field to UserService",
          targetFile: "src/main/groovy/se/alipsa/lca/service/UserService.groovy",
          action: "MODIFY",
          contextFiles: ["src/main/groovy/se/alipsa/lca/service/UserService.groovy"],
          dependsOn: [],
          acceptanceCriteria: "Logger field exists"
        ],
        [
          order: 2,
          description: "Add log statements to methods",
          targetFile: "src/main/groovy/se/alipsa/lca/service/UserService.groovy",
          action: "MODIFY",
          contextFiles: [],
          dependsOn: [1],
          acceptanceCriteria: "Methods have log calls"
        ]
      ],
      readOnlyContext: ["pom.xml"],
      risks: ["May increase log volume"],
      reasoning: "Logging is essential for debugging"
    ]).toString()

    when:
    ArchitectPlan plan = ArchitectPlan.fromJson(json)

    then:
    plan.summary == "Add logging to the service layer"
    plan.steps.size() == 2
    plan.steps[0].order == 1
    plan.steps[0].action == StepAction.MODIFY
    plan.steps[0].targetFile == "src/main/groovy/se/alipsa/lca/service/UserService.groovy"
    plan.steps[1].dependsOn == [1]
    plan.readOnlyContext == ["pom.xml"]
    plan.risks == ["May increase log volume"]
    plan.reasoning == "Logging is essential for debugging"
  }

  def "fromJson handles empty steps list"() {
    given:
    String json = '{"summary":"Nothing to do","steps":[],"readOnlyContext":[],"risks":[],"reasoning":""}'

    when:
    ArchitectPlan plan = ArchitectPlan.fromJson(json)

    then:
    plan.summary == "Nothing to do"
    plan.steps.isEmpty()
    plan.risks.isEmpty()
  }

  def "fromJson handles single-step plan"() {
    given:
    String json = new JsonBuilder([
      summary: "Simple change",
      steps: [
        [order: 1, description: "Do the thing", action: "CREATE", acceptanceCriteria: "Done"]
      ],
      risks: []
    ]).toString()

    when:
    ArchitectPlan plan = ArchitectPlan.fromJson(json)

    then:
    plan.steps.size() == 1
    plan.steps[0].order == 1
    plan.steps[0].action == StepAction.CREATE
    plan.steps[0].description == "Do the thing"
  }

  def "fromJson defaults missing fields"() {
    given:
    String json = '{"summary":"Minimal"}'

    when:
    ArchitectPlan plan = ArchitectPlan.fromJson(json)

    then:
    plan.summary == "Minimal"
    plan.steps.isEmpty()
    plan.readOnlyContext.isEmpty()
    plan.risks.isEmpty()
  }

  def "fromJson handles unknown action gracefully"() {
    given:
    String json = new JsonBuilder([
      summary: "Test",
      steps: [[order: 1, description: "Step", action: "UNKNOWN_ACTION"]]
    ]).toString()

    when:
    ArchitectPlan plan = ArchitectPlan.fromJson(json)

    then:
    plan.steps[0].action == StepAction.CREATE // fallback
  }

  def "fromJson throws on non-object input"() {
    when:
    ArchitectPlan.fromJson('"just a string"')

    then:
    thrown(IllegalArgumentException)
  }
}
