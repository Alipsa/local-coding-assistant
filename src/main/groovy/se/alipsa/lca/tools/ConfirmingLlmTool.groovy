package se.alipsa.lca.tools

import com.embabel.agent.api.tool.DelegatingTool
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.ToolCallContext
import groovy.transform.CompileStatic
import se.alipsa.lca.shell.ConfirmationChoice
import se.alipsa.lca.shell.ConfirmationService

/**
 * Wraps an Embabel {@link Tool} so it blocks on {@link ConfirmationService} before delegating.
 *
 * <p>Embabel's own HITL primitives ({@code ConfirmingTool}, {@code AwaitableResponseException})
 * pause the {@code AgentProcess} and expect a separate "resume" call to continue it, but no such
 * API is discoverable anywhere in this project's resolved Embabel dependencies. Since
 * {@code @LlmTool} methods already execute synchronously, in-process, on whatever thread is
 * running the chat turn, blocking right here — reusing the same {@link ConfirmationService} the
 * REPL and Swing GUI already use for {@code /run}/{@code /git-push} — needs no process pause/resume
 * at all.
 */
@CompileStatic
class ConfirmingLlmTool implements DelegatingTool {

  private final Tool delegate
  private final String message
  private final ConfirmationService confirmationService

  ConfirmingLlmTool(Tool delegate, String message, ConfirmationService confirmationService) {
    this.delegate = delegate
    this.message = message
    this.confirmationService = confirmationService
  }

  @Override
  Tool getDelegate() {
    delegate
  }

  @Override
  Tool.Definition getDefinition() {
    delegate.definition
  }

  @Override
  Tool.Result call(String input) {
    call(input, ToolCallContext.EMPTY)
  }

  @Override
  Tool.Result call(String input, ToolCallContext context) {
    String prompt = "${message}\n${input ?: ''}".trim()
    ConfirmationChoice choice = confirmationService.confirm(prompt)
    if (choice == ConfirmationChoice.NO) {
      return Tool.Result.text("Action declined by the user; do not retry without new instructions.")
    }
    delegate.call(input, context)
  }
}
