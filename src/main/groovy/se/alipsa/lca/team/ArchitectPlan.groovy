package se.alipsa.lca.team

import groovy.json.JsonSlurper
import groovy.transform.Canonical
import groovy.transform.CompileStatic

@Canonical
@CompileStatic
class ArchitectPlan {
  String summary
  List<PlanStep> steps
  List<String> readOnlyContext
  List<String> risks
  String reasoning

  static ArchitectPlan fromJson(String json) {
    def parsed = new JsonSlurper().parseText(json)
    if (!(parsed instanceof Map)) {
      throw new IllegalArgumentException("Expected a JSON object")
    }
    Map<String, Object> map = (Map<String, Object>) parsed
    List<PlanStep> steps = []
    if (map.steps instanceof List) {
      for (Object stepObj : (List) map.steps) {
        if (stepObj instanceof Map) {
          Map<String, Object> s = (Map<String, Object>) stepObj
          StepAction action = StepAction.CREATE
          if (s.action instanceof String) {
            try {
              action = StepAction.valueOf(((String) s.action).toUpperCase())
            } catch (IllegalArgumentException ignored) {
              // default to CREATE
            }
          }
          steps.add(new PlanStep(
            (s.order ?: 0) as int,
            (s.description ?: "") as String,
            s.targetFile as String,
            action,
            (s.contextFiles ?: []) as List<String>,
            (s.dependsOn ?: []) as List<Integer>,
            (s.acceptanceCriteria ?: "") as String
          ))
        }
      }
    }
    new ArchitectPlan(
      (map.summary ?: "") as String,
      steps,
      (map.readOnlyContext ?: []) as List<String>,
      (map.risks ?: []) as List<String>,
      (map.reasoning ?: "") as String
    )
  }
}
