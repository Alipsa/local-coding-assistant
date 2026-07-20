package se.alipsa.lca.team

import com.fasterxml.jackson.annotation.JsonCreator
import groovy.transform.CompileStatic

@CompileStatic
enum StepAction {
  CREATE,
  MODIFY,
  DELETE,
  RUN_COMMAND

  @JsonCreator
  static StepAction fromValue(String value) {
    if (value == null) {
      return CREATE
    }
    try {
      return valueOf(value.trim().toUpperCase())
    } catch (IllegalArgumentException ignored) {
      return CREATE
    }
  }
}
