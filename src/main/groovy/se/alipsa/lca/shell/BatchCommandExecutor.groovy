package se.alipsa.lca.shell

import groovy.transform.CompileStatic

@CompileStatic
interface BatchCommandExecutor {
  BatchCommandOutcome execute(String command)
}
