# Implementation Plan: Embabel 0.3.2 Features

## Investigation Summary

### 1. Thinking/Reasoning Capabilities ✅

**What we found:**
- `PromptRunner.supportsThinking()` - check if model supports thinking
- `PromptRunner.withThinking()` - returns `ThinkingPromptRunnerOperations`
- `ThinkingResponse<T>` - wraps result + thinking process:
  - `T getResult()` - the actual answer
  - `List<ThinkingBlock> getThinkingBlocks()` - structured thinking steps
  - `String getThinkingContent()` - all thinking as text
  - `boolean hasThinking()` - check if thinking was captured
  - Methods to filter by type/tag

**How it works:**
```java
// Instead of:
String result = ai.withLlm(options).generateText(prompt);

// Use:
ThinkingResponse<String> response = ai.withLlm(options)
    .withThinking()
    .generateText(prompt);

String answer = response.getResult();
String reasoning = response.getThinkingContent();
```

### 2. InternetResource/Web Search ✅

**What we found:**
- `InternetResource`, `Page`, `InternetResources` are **domain objects only**
- They implement `PromptContributor` for easy prompt inclusion
- Embabel does **NOT** provide web fetching/searching implementation
- These are standardization interfaces, not replacement tools

**Conclusion:**
- We keep our `WebSearchTool` implementation (HtmlUnit/Jsoup)
- Opportunity: Make our results compatible with Embabel's domain model
- Minor benefit: Better integration if we use Embabel's collections elsewhere

---

## Implementation Plan

### Phase 1: Thinking Support for Code Review (HIGH PRIORITY)

**Goal:** Add reasoning transparency to `/review` command

**Steps:**

#### 1.1 Update ReviewAgent to support thinking
- **File:** `src/main/groovy/se/alipsa/lca/agent/ReviewAgent.groovy`
- **Changes:**
  - Add `boolean withThinking` parameter to `ReviewRequest`
  - Modify `review()` method to use `withThinking()` when enabled
  - Return thinking content in `ReviewResponse`

```groovy
@Canonical
@CompileStatic
class ReviewRequest {
  // ... existing fields ...
  boolean withThinking = false  // NEW
}

@Canonical
@CompileStatic
class ReviewResponse {
  String review
  String reasoning  // NEW - thinking content
}
```

#### 1.2 Update CodingAssistantAgent.reviewCode()
- **File:** `src/main/groovy/se/alipsa/lca/agent/CodingAssistantAgent.groovy`
- **Changes:**
  - Add optional `withThinking` parameter
  - Use `ThinkingPromptRunnerOperations` when enabled
  - Return both review + reasoning

```groovy
ReviewedCodeSnippet reviewCode(
  UserInput userInput,
  CodeSnippet codeSnippet,
  Ai ai,
  LlmOptions llmOverride,
  String systemPromptOverride,
  RoleGoalBackstorySpec reviewerPersona,
  boolean withThinking = false  // NEW
) {
  LlmOptions options = llmOverride ?: reviewLlmOptions
  RoleGoalBackstorySpec reviewer = reviewerPersona ?: Personas.REVIEWER
  String reviewPrompt = buildReviewPrompt(userInput, codeSnippet, systemPromptOverride, reviewer)

  if (withThinking && ai.withLlm(options).supportsThinking()) {
    ThinkingResponse<String> response = ai
      .withLlm(options)
      .withPromptContributor(reviewer)
      .withThinking()
      .generateText(reviewPrompt)

    return new ReviewedCodeSnippet(
      codeSnippet,
      response.getResult(),
      reviewer,
      response.getThinkingContent()  // NEW
    )
  } else {
    // Fallback to non-thinking mode
    String review = ai.withLlm(options)
      .withPromptContributor(reviewer)
      .generateText(reviewPrompt)
    return new ReviewedCodeSnippet(codeSnippet, review, reviewer, null)
  }
}
```

#### 1.3 Update ReviewedCodeSnippet class
- **File:** `src/main/groovy/se/alipsa/lca/agent/CodingAssistantAgent.groovy`
- **Changes:**
  - Add `String reasoning` field

```groovy
@Canonical
@CompileStatic
static class ReviewedCodeSnippet implements HasContent, Timestamped {
  CodeSnippet codeSnippet
  String review
  RoleGoalBackstorySpec reviewer
  String reasoning  // NEW - thinking/reasoning content

  // ... existing methods ...
}
```

#### 1.4 Update ShellCommands.review()
- **File:** `src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy`
- **Changes:**
  - Add `--with-thinking` or `--reasoning` flag
  - Display thinking content separately when available

```groovy
@ShellMethod(key = ["/review"], value = "Review code for issues")
String review(
  @ShellOption(help = "Review prompt") String prompt,
  // ... existing params ...
  @ShellOption(value = ["--with-thinking", "--reasoning"],
               defaultValue = "false",
               help = "Show reasoning process") boolean withThinking
) {
  // ... existing code ...

  ReviewedCodeSnippet reviewed = agent.reviewCode(
    userInput, codeSnippet, ai, options, systemPrompt, persona, withThinking
  )

  String output = formatReview(reviewed)

  if (withThinking && reviewed.reasoning) {
    output += "\n\n=== Reasoning Process ===\n"
    output += reviewed.reasoning
  }

  return formatSection("Review", output)
}
```

#### 1.5 Add configuration
- **File:** `src/main/resources/application.properties`
- **Changes:**
  - Add thinking-related properties

```properties
# Thinking/Reasoning configuration
assistant.thinking.enabled=true
assistant.thinking.default=false
assistant.thinking.token-budget=2000
```

#### 1.6 Testing
- **File:** `src/test/groovy/se/alipsa/lca/agent/ReviewAgentSpec.groovy`
- **Tests:**
  - Test review with thinking enabled
  - Test review with thinking disabled
  - Test fallback when model doesn't support thinking
  - Verify reasoning content is captured

---

### Phase 2: Thinking Support for Planning (HIGH PRIORITY)

**Goal:** Show architectural reasoning in `/plan` command

**Steps:**

#### 2.1 Update ChatAgent for planning persona
- **File:** `src/main/groovy/se/alipsa/lca/agent/ChatAgent.groovy`
- **Changes:**
  - Add thinking support when persona is ARCHITECT
  - Return structured response with reasoning

```groovy
AssistantMessage respond(Conversation conversation, UserMessage userMessage, ChatRequest request, Ai ai) {
  // ... existing code ...

  boolean useThinking = (request.persona == PersonaMode.ARCHITECT &&
                         ai.withLlm(options).supportsThinking())

  if (useThinking) {
    ThinkingResponse<AssistantMessage> response = ai
      .withLlm(options)
      .withPromptContributor(template.persona)
      .withThinking()
      .respond(conversation.messages)

    // Append reasoning to assistant message
    String content = response.getResult().textContent
    if (response.hasThinking()) {
      content += "\n\n### Architectural Reasoning\n"
      content += response.getThinkingContent()
    }

    return new AssistantMessage(content)
  } else {
    // Existing non-thinking path
  }
}
```

#### 2.2 Update /plan command
- **File:** `src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy`
- **Changes:**
  - Add `--show-reasoning` flag
  - Use ARCHITECT persona with thinking enabled

```groovy
@ShellMethod(key = ["/plan"], value = "Create implementation plan")
String plan(
  @ShellOption(help = "Planning prompt") String prompt,
  @ShellOption(defaultValue = "false", help = "Show reasoning") boolean showReasoning,
  // ... other params ...
) {
  ChatRequest request = new ChatRequest(
    PersonaMode.ARCHITECT,
    options,
    systemPrompt,
    showReasoning  // Use thinking when showing reasoning
  )

  // ... rest of implementation ...
}
```

#### 2.3 Testing
- **File:** `src/test/groovy/se/alipsa/lca/agent/ChatAgentSpec.groovy`
- **Tests:**
  - Test planning with reasoning enabled
  - Test planning without reasoning
  - Verify reasoning is helpful and actionable

---

### Phase 3: Web Search Standardization (MEDIUM PRIORITY)

**Goal:** Align with Embabel's domain model for future compatibility

**Steps:**

#### 3.1 Create adapter for InternetResource
- **File:** `src/main/groovy/se/alipsa/lca/tools/WebSearchTool.groovy`
- **Changes:**
  - Add method to convert `SearchResult` to `InternetResource`
  - Keep existing API, add new Embabel-compatible method

```groovy
import com.embabel.agent.domain.library.InternetResource
import com.embabel.agent.domain.library.InternetResources

// Add new method
List<InternetResource> searchAsInternetResources(String query, int limit) {
  List<SearchResult> results = search(query, limit)
  return results.collect { result ->
    new InternetResource(result.link, result.snippet)
  }
}

// Wrapper class for prompt contribution
class WebSearchResults implements InternetResources {
  private final List<InternetResource> links

  WebSearchResults(List<InternetResource> links) {
    this.links = List.copyOf(links)
  }

  @Override
  List<InternetResource> getLinks() {
    return links
  }
}
```

#### 3.2 Update ShellCommands to offer both formats
- **File:** `src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy`
- **Changes:**
  - Keep existing `/search` behavior
  - Internal use can leverage `InternetResources` for prompt contribution

```groovy
// When passing search results to agents:
WebSearchResults searchResults = new WebSearchResults(
  webSearchTool.searchAsInternetResources(query, limit)
)

// This can now be used with .withPromptContributor(searchResults)
```

#### 3.3 Testing
- Test conversion to InternetResource format
- Verify backward compatibility with existing code
- Test prompt contribution works correctly

---

## Rollout Strategy

### Sprint 1 (Week 1): Review with Thinking
- [ ] Implement Phase 1.1-1.3 (ReviewAgent + CodingAssistantAgent)
- [ ] Implement Phase 1.4 (ShellCommands)
- [ ] Implement Phase 1.5 (Configuration)
- [ ] Write and run tests (Phase 1.6)
- [ ] Update documentation in `docs/commands.md`

### Sprint 2 (Week 2): Planning with Thinking
- [ ] Implement Phase 2.1 (ChatAgent)
- [ ] Implement Phase 2.2 (/plan command)
- [ ] Write and run tests (Phase 2.3)
- [ ] Create examples showing reasoning output
- [ ] Update documentation

### Sprint 3 (Optional): Web Search Standardization
- [ ] Implement Phase 3.1 (InternetResource adapter)
- [ ] Implement Phase 3.2 (ShellCommands integration)
- [ ] Write and run tests (Phase 3.3)
- [ ] Update documentation if needed

---

## Success Criteria

### Phase 1: Code Review
✅ `/review --with-thinking` shows both review + reasoning
✅ Reasoning explains why issues were flagged
✅ Fallback works when thinking unsupported
✅ All tests passing
✅ Performance acceptable (< 2x slowdown)

### Phase 2: Planning
✅ `/plan --show-reasoning` exposes architectural thinking
✅ Reasoning helps users understand tradeoffs
✅ Works with ARCHITECT persona
✅ All tests passing
✅ Documentation includes examples

### Phase 3: Web Search
✅ Search results compatible with Embabel domain model
✅ Backward compatibility maintained
✅ Ready for future Embabel integrations
✅ All tests passing

---

## Risk Mitigation

### Risk 1: Model doesn't support thinking
**Mitigation:** Always check `supportsThinking()` and gracefully fallback

### Risk 2: Thinking adds too much latency
**Mitigation:**
- Make thinking opt-in (default=false)
- Add token budget limits
- Show progress indicator for long operations

### Risk 3: Thinking content is not useful
**Mitigation:**
- Start with /review where reasoning is most valuable
- Collect user feedback
- Iterate on prompts to improve reasoning quality

### Risk 4: Breaking changes in future Embabel versions
**Mitigation:**
- Keep adapters thin
- Maintain our own abstractions
- Version-lock Embabel until tested

---

## Open Questions

1. **Which models support thinking?**
   - Need to test with qwen3-coder:30b, tinyllama, gpt-oss:20b
   - Document which models work best

2. **What's the token budget impact?**
   - Benchmark thinking vs non-thinking modes
   - Tune `thinking.token-budget` based on results

3. **Should thinking be default for any commands?**
   - Probably not initially - make it opt-in
   - Consider making it default for /plan later

4. **How should reasoning be formatted?**
   - Plain text? Markdown? Structured sections?
   - Experiment and gather user feedback

---

## Documentation Updates Required

1. **docs/commands.md**
   - Document `--with-thinking` flag for /review
   - Document `--show-reasoning` flag for /plan
   - Add examples of reasoning output

2. **docs/quickstart.md**
   - Add section on reasoning capabilities
   - Explain when to use thinking mode

3. **README.md**
   - Mention thinking/reasoning as a feature
   - Link to detailed docs

4. **AGENTS.md** (project-specific template)
   - Suggest using --with-thinking for complex reviews
   - Provide guidelines for reasoning output

---

## Next Actions

1. **Immediate:** Review and approve this plan
2. **Week 1:** Begin Sprint 1 (Review with Thinking)
3. **Week 2:** Begin Sprint 2 (Planning with Thinking)
4. **Week 3:** Evaluate Phase 3 necessity based on Phases 1-2 results
