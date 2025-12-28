package se.alipsa.lca.tools

import groovy.transform.CompileStatic
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

@Component
@CompileStatic
class LocalOnlyState {

  private final boolean defaultLocalOnly
  private final ConcurrentMap<String, Boolean> overrides = new ConcurrentHashMap<>()

  LocalOnlyState(@Value('${assistant.local-only:true}') boolean defaultLocalOnly) {
    this.defaultLocalOnly = defaultLocalOnly
  }

  boolean isLocalOnly(String sessionId) {
    String key = normaliseSession(sessionId)
    Boolean override = overrides.get(key)
    override != null ? override : defaultLocalOnly
  }

  Boolean getLocalOnlyOverride(String sessionId) {
    overrides.get(normaliseSession(sessionId))
  }

  void setLocalOnlyOverride(String sessionId, Boolean enabled) {
    String key = normaliseSession(sessionId)
    if (enabled == null) {
      overrides.remove(key)
      return
    }
    overrides.put(key, enabled)
  }

  boolean getDefaultLocalOnly() {
    defaultLocalOnly
  }

  private static String normaliseSession(String sessionId) {
    sessionId != null && sessionId.trim() ? sessionId.trim() : "default"
  }
}
