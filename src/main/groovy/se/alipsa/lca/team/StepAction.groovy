package se.alipsa.lca.team

import groovy.transform.CompileStatic

@CompileStatic
enum StepAction {
  CREATE,
  MODIFY,
  DELETE,
  RUN_COMMAND
}
