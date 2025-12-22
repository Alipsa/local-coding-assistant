package se.alipsa.lca.shell

import groovy.transform.CompileStatic

@CompileStatic
interface BatchExitHandler {
  void exit(int code)
}
