package se.alipsa.lca.tools

import com.embabel.agent.api.tool.DelegatingTool
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.ToolCallContext
import groovy.transform.CompileStatic
import se.alipsa.lca.shell.ConfirmationChoice
import se.alipsa.lca.shell.ConfirmationService
import se.alipsa.lca.shell.SessionState

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
 *
 * <p>Choosing "yes to all" records that in {@link SessionState}, scoped to the given
 * {@code sessionId} (the chat conversation's id), so later confirmation-gated tool calls in the
 * same session skip prompting — mirroring {@code ShellCommands.applyAllConfirmed}, but per
 * session rather than process-wide, and never persisted across restarts.
 */
@CompileStatic
class ConfirmingLlmTool implements DelegatingTool {

  private final Tool delegate
  private final String message
  private final ConfirmationService confirmationService
  private final SessionState sessionState
  private final String sessionId

  ConfirmingLlmTool(
    Tool delegate,
    String message,
    ConfirmationService confirmationService,
    SessionState sessionState,
    String sessionId
  ) {
    this.delegate = delegate
    this.message = message
    this.confirmationService = confirmationService
    this.sessionState = sessionState
    this.sessionId = sessionId
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
    if (sessionState.isToolConfirmationAllowedForAll(sessionId)) {
      return delegate.call(input, context)
    }
    String prompt = "${message}\n${input ?: ''}".trim()
    ConfirmationChoice choice = confirmationService.confirm(prompt)
    if (choice == ConfirmationChoice.NO) {
      return Tool.Result.text("Action declined by the user; do not retry without new instructions.")
    }
    if (choice == ConfirmationChoice.ALL) {
      sessionState.allowAllToolConfirmations(sessionId)
    }
    delegate.call(input, context)
  }
}
