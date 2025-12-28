package se.alipsa.lca.intent

import groovy.transform.Canonical
import groovy.transform.CompileStatic

import java.util.Collections

@Canonical
@CompileStatic
class IntentCommand {
  String name
  Map<String, Object> args = Collections.emptyMap()
}
