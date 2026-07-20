package se.alipsa.lca.team

import spock.lang.Specification

/**
 * ArchitectAgent now uses Ai's structured-output support (createObject) to populate this
 * class directly, so there is no hand-rolled JSON parsing left to test here (that used to be
 * ArchitectPlan.fromJson) - this spec just verifies the plain data-class shape.
 */
class ArchitectPlanSpec extends Specification {

  def "constructs with all fields set"() {
    given:
    PlanStep step = new PlanStep(
      1, "Add logger field", "src/main/groovy/Foo.groovy", StepAction.MODIFY, ["Foo.groovy"], [], "Logger exists"
    )

    when:
    ArchitectPlan plan = new ArchitectPlan(
      "Add logging", [step], ["pom.xml"], ["May increase log volume"], "Logging is essential for debugging"
    )

    then:
    plan.summary == "Add logging"
    plan.steps == [step]
    plan.readOnlyContext == ["pom.xml"]
    plan.risks == ["May increase log volume"]
    plan.reasoning == "Logging is essential for debugging"
  }

  def "constructs with empty collections"() {
    when:
    ArchitectPlan plan = new ArchitectPlan("Nothing to do", [], [], [], "")

    then:
    plan.steps.isEmpty()
    plan.readOnlyContext.isEmpty()
    plan.risks.isEmpty()
  }
}
