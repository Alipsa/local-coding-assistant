package se.alipsa.lca.memory

import groovy.transform.Canonical
import groovy.transform.CompileStatic

import java.time.Instant

@Canonical
@CompileStatic
class MemoryEntry {
  String id
  String content
  Instant createdAt
  Instant lastAccessedAt
  String sourceSessionId
  /** null = global (applies across every project); see ProjectScopeResolver. */
  String projectId
}
