package se.alipsa.lca.mcp

import groovy.transform.CompileStatic
import org.springframework.stereotype.Component

import java.util.concurrent.ConcurrentHashMap

@Component
@CompileStatic
class McpSessionState {

  enum McpActivationMode {
    AUTO, MANUAL
  }

  private final Map<String, Set<String>> activeServers = new ConcurrentHashMap<>()
  private final Map<String, McpActivationMode> modes = new ConcurrentHashMap<>()

  void activate(String sessionId, String serverName) {
    String sid = normalise(sessionId)
    modes.put(sid, McpActivationMode.MANUAL)
    activeServers.computeIfAbsent(sid, { k -> Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>()) })
      .add(serverName)
  }

  void deactivate(String sessionId, String serverName) {
    String sid = normalise(sessionId)
    Set<String> servers = activeServers.get(sid)
    if (servers != null) {
      servers.remove(serverName)
    }
  }

  void resetToAuto(String sessionId) {
    String sid = normalise(sessionId)
    activeServers.remove(sid)
    modes.put(sid, McpActivationMode.AUTO)
  }

  boolean isActive(String sessionId, String serverName) {
    String sid = normalise(sessionId)
    Set<String> servers = activeServers.get(sid)
    return servers != null && servers.contains(serverName)
  }

  McpActivationMode getMode(String sessionId) {
    String sid = normalise(sessionId)
    return modes.getOrDefault(sid, McpActivationMode.AUTO)
  }

  Set<String> getActiveServers(String sessionId) {
    String sid = normalise(sessionId)
    Set<String> servers = activeServers.get(sid)
    return servers != null ? Collections.unmodifiableSet(servers) : Collections.emptySet()
  }

  private static String normalise(String sessionId) {
    return (sessionId == null || sessionId.isEmpty()) ? "default" : sessionId
  }
}
