package se.alipsa.lca.team

import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Specification
import spock.lang.Unroll

/**
 * StepAction is deserialized by Embabel's structured-output layer (Ai.createObject), which uses
 * a plain Jackson ObjectMapper under the hood and does not enforce the enum via schema - an
 * unrecognised value throws during deserialization unless the enum itself tolerates it. The
 * @JsonCreator factory method restores the leniency the old hand-rolled ArchitectPlan.fromJson
 * used to provide (default to CREATE), but now at the Jackson level so a single bad step doesn't
 * cause the whole ArchitectPlan object to fail deserialization.
 */
class StepActionSpec extends Specification {

  @Unroll
  def "fromValue maps '#input' to #expected"() {
    expect:
    StepAction.fromValue(input) == expected

    where:
    input          | expected
    "CREATE"       | StepAction.CREATE
    "create"       | StepAction.CREATE
    "  MODIFY  "   | StepAction.MODIFY
    "Delete"       | StepAction.DELETE
    "RUN_COMMAND"  | StepAction.RUN_COMMAND
    "ADD_FILE"     | StepAction.CREATE
    "bogus"        | StepAction.CREATE
    ""             | StepAction.CREATE
    null           | StepAction.CREATE
  }

  def "a single unrecognised action value does not fail deserialization of the whole plan"() {
    given:
    ObjectMapper mapper = new ObjectMapper()
    String json = '''
    {
      "summary": "Do things",
      "steps": [
        {"order": 1, "description": "d1", "targetFile": "A.groovy", "action": "CREATE", "contextFiles": [], "dependsOn": [], "acceptanceCriteria": "ok"},
        {"order": 2, "description": "d2", "targetFile": "B.groovy", "action": "ADD_FILE", "contextFiles": [], "dependsOn": [], "acceptanceCriteria": "ok"},
        {"order": 3, "description": "d3", "targetFile": "C.groovy", "action": "DELETE", "contextFiles": [], "dependsOn": [], "acceptanceCriteria": "ok"}
      ],
      "readOnlyContext": [],
      "risks": [],
      "reasoning": "because"
    }
    '''

    when:
    ArchitectPlan plan = mapper.readValue(json, ArchitectPlan)

    then:
    plan.steps.size() == 3
    plan.steps[0].action == StepAction.CREATE
    plan.steps[1].action == StepAction.CREATE
    plan.steps[2].action == StepAction.DELETE
  }
}
