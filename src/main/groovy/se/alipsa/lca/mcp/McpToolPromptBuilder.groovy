package se.alipsa.lca.mcp

import groovy.transform.CompileStatic
import io.modelcontextprotocol.spec.McpSchema

/**
 * Budget-aware tool description generation for LLM system prompts.
 * Generates descriptions of MCP tools and resources with a character budget to control
 * context window usage for local models.
 */
@CompileStatic
class McpToolPromptBuilder {

  private final int budgetChars

  /**
   * Creates a new prompt builder with the specified character budget.
   *
   * @param budgetChars maximum characters for full tool descriptions (defaults to 3000 if <= 0)
   */
  McpToolPromptBuilder(int budgetChars) {
    this.budgetChars = budgetChars > 0 ? budgetChars : 3000
  }

  /**
   * Builds a prompt describing available MCP tools. Tools are listed with full descriptions
   * and parameter schemas until the budget is exhausted, then remaining tools get compact
   * name-only entries.
   *
   * @param toolsByServer map of server names to their tools (iteration order is preserved)
   * @return formatted tool prompt, or empty string if no tools
   */
  String buildToolPrompt(Map<String, List<McpSchema.Tool>> toolsByServer) {
    if (!toolsByServer || toolsByServer.isEmpty()) {
      return ''
    }

    List<McpSchema.Tool> allTools = []
    toolsByServer.values().each { allTools.addAll(it) }
    if (allTools.isEmpty()) {
      return ''
    }

    StringBuilder sb = new StringBuilder()
    int usedBudget = 0

    toolsByServer.each { String serverName, List<McpSchema.Tool> tools ->
      tools.each { McpSchema.Tool tool ->
        String toolId = "mcp_${serverName}_${tool.name()}"

        if (usedBudget < budgetChars) {
          String fullDesc = formatToolFull(toolId, tool)
          sb.append(fullDesc).append('\n')
          usedBudget += fullDesc.length() + 1
        } else {
          sb.append("- ${toolId}\n")
        }
      }
    }

    sb.append('\nCall MCP tools with JSON arguments: mcp_server_tool({"param": "value"})')
    return sb.toString()
  }

  /**
   * Builds a prompt describing available MCP resources.
   *
   * @param resources list of resources to describe
   * @return formatted resource prompt, or empty string if no resources
   */
  String buildResourcePrompt(List<McpSchema.Resource> resources) {
    if (!resources || resources.isEmpty()) {
      return ''
    }

    StringBuilder sb = new StringBuilder()

    resources.each { McpSchema.Resource resource ->
      sb.append("- ${resource.uri()}")
      if (resource.name()) {
        sb.append(" (${resource.name()})")
      }
      if (resource.description()) {
        sb.append(": ${resource.description()}")
      }
      sb.append('\n')
    }

    sb.append('\nRead a resource: mcp_read_resource("<uri>")')
    return sb.toString()
  }

  /**
   * Formats a full tool description including parameters and their schemas.
   *
   * @param toolId the composite tool identifier (mcp_server_toolname)
   * @param tool the tool schema
   * @return formatted description
   */
  private String formatToolFull(String toolId, McpSchema.Tool tool) {
    StringBuilder sb = new StringBuilder()
    sb.append("- ${toolId}")

    if (tool.description()) {
      sb.append(": ${truncate(tool.description(), 200)}")
    }

    McpSchema.JsonSchema inputSchema = tool.inputSchema()
    if (inputSchema && inputSchema.properties()) {
      sb.append('\n  Parameters:')
      Map<String, Object> properties = inputSchema.properties()
      List<String> required = inputSchema.required() ?: []

      properties.each { String paramName, Object paramDef ->
        sb.append("\n    - ${paramName}")
        if (required.contains(paramName)) {
          sb.append(' (required)')
        }
        if (paramDef instanceof Map) {
          Map<String, Object> paramMap = (Map<String, Object>) paramDef
          if (paramMap.description) {
            sb.append(": ${truncate(paramMap.description.toString(), 100)}")
          }
          if (paramMap.type) {
            sb.append(" [${paramMap.type}]")
          }
        }
      }
    }

    return sb.toString()
  }

  /**
   * Truncates text to maximum length, adding "..." if truncated.
   *
   * @param text text to truncate
   * @param maxLen maximum length (including "...")
   * @return truncated text
   */
  private String truncate(String text, int maxLen) {
    if (!text || text.length() <= maxLen) {
      return text
    }
    return text.substring(0, maxLen - 3) + '...'
  }
}
