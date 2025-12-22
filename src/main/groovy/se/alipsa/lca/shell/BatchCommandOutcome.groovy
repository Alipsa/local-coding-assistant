package se.alipsa.lca.shell

import groovy.transform.Canonical
import groovy.transform.CompileStatic

@Canonical
@CompileStatic
class BatchCommandOutcome {
  Object result
  Throwable error
  boolean exitRequested
  Integer exitCode
}
