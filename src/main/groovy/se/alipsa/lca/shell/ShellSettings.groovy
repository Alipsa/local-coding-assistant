package se.alipsa.lca.shell

import groovy.transform.CompileStatic
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

import java.util.concurrent.atomic.AtomicBoolean

@Component
@CompileStatic
class ShellSettings {

  private final AtomicBoolean autoPasteEnabled

  ShellSettings(@Value('${assistant.shell.auto-paste:true}') boolean autoPasteEnabled) {
    this.autoPasteEnabled = new AtomicBoolean(autoPasteEnabled)
  }

  boolean isAutoPasteEnabled() {
    autoPasteEnabled.get()
  }

  void setAutoPasteEnabled(boolean enabled) {
    autoPasteEnabled.set(enabled)
  }
}
