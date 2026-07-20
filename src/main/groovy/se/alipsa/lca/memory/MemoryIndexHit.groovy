package se.alipsa.lca.memory

import groovy.transform.Canonical
import groovy.transform.CompileStatic

@Canonical
@CompileStatic
class MemoryIndexHit {
  String id
  double score
}
