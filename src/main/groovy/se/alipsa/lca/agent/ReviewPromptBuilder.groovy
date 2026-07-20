package se.alipsa.lca.agent

import groovy.transform.CompileStatic

/**
 * Shared PR-review prompt text, used by both the {@code /review} command
 * ({@link CodingAssistantAgent#buildPrReviewPrompt}) and the agent team's QA pass
 * ({@code se.alipsa.lca.team.TeamReviewerAgent}), so both paths benefit from the same
 * cross-file-analysis depth instead of drifting into two independently-tuned prompts.
 */
@CompileStatic
class ReviewPromptBuilder {

  static String buildPrReviewPrompt(
    String codeText,
    String userRequest,
    String systemPromptOverride,
    boolean securityFocus
  ) {
    String extraSystem = systemPromptOverride?.trim()
    """
You are a repository code reviewer.
Assess the proposal for correctness, repository fit, error handling, and testing strategy.
Ensure code follows project conventions (check AGENTS.md if present) and avoid deprecated APIs.
Reference likely target files or layers and call out missing test coverage.
Prioritize security flaws, unsafe file handling, missing validation, and unclear error paths.
${securityFocus ? "Focus on secrets, injection risks, auth bypasses, insecure defaults, and data exposure." : ""}
Format findings as bullet lines using: [Severity] file:line - comment (severity: High/Medium/Low; file may be 'general').
${extraSystem ? "Additional system guidance: ${extraSystem}\n" : ""}
This is a pull request review. Analyse the full diff and all changed files provided below.

Cross-file analysis:
- Trace data flow across changed files. If a method signature or column changes in one file, verify all callers/consumers are updated.
- Check constraint consistency: if a unique constraint changes, verify all queries that depend on uniqueness.
- Look for missing filters: if a table now requires a compound key, verify all lookups include all key columns.
- Identify silent failures: null returns that callers don't check, catch blocks that swallow errors, fallbacks that hide problems.

Report only issues found and suggested actions. Do not list strengths or summarise what the PR does well.
Do not limit your response length. Be thorough.

Code to review:
${codeText}

User request:
${userRequest}

Respond with sections:
Findings: bullet points starting with High/Medium/Low
Tests: list the specific tests or scenarios to validate
""".stripIndent().trim()
  }
}
