package se.alipsa.lca.agent

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

/**
 * Marks an {@code @LlmTool}-annotated method as requiring explicit user confirmation before it
 * runs. Embabel's {@code MethodToolFactory} only recognises the literal {@code @LlmTool}
 * annotation via direct reflection, so this cannot wrap or replace it — it is read separately
 * by {@link CodingAssistantAgent#buildLlmTools()}, which wraps the matching {@code Tool} in a
 * {@link se.alipsa.lca.tools.ConfirmingLlmTool} before registering it with the chat LLM.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@interface RequiresConfirmation {

  /** Human-readable description of the action, shown to the user alongside the raw tool input. */
  String message()
}
