package se.alipsa.lca.intent

import groovy.transform.CompileStatic
import org.springframework.stereotype.Component

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Component
@CompileStatic
class IntentRoutingState {

  private final AtomicReference<Boolean> enabledOverride = new AtomicReference<>()
  private final AtomicBoolean debugEnabled = new AtomicBoolean(false)

  Boolean getEnabledOverride() {
    enabledOverride.get()
  }

  void setEnabledOverride(Boolean enabled) {
    enabledOverride.set(enabled)
  }

  void clearEnabledOverride() {
    enabledOverride.set(null)
  }

  boolean isDebugEnabled() {
    debugEnabled.get()
  }

  void setDebugEnabled(boolean enabled) {
    debugEnabled.set(enabled)
  }

  boolean isEnabled(IntentRoutingSettings settings) {
    Boolean override = enabledOverride.get()
    if (override != null) {
      return override
    }
    if (settings == null) {
      return true
    }
    settings.isEnabled()
  }
}
