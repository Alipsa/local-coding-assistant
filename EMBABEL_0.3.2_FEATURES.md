# Embabel 0.3.2 New Features and Opportunities

## Summary of Changes

Embabel 0.3.2 introduces several new capabilities. We've already migrated to the new `RoleGoalBackstorySpec` builder API, but there are additional features we could leverage.

## New Features in 0.3.2

### 1. **Thinking/Reasoning Capabilities** ⭐ HIGH PRIORITY
**New Classes:**
- `com.embabel.common.core.thinking.ThinkingCapability`
- `com.embabel.common.core.thinking.ThinkingResponse<T>`
- `com.embabel.agent.api.common.thinking.ThinkingPromptRunnerOperations`

**What it provides:**
```java
public interface ThinkingPromptRunnerOperations {
  ThinkingResponse<String> generateText(String prompt);
  <T> ThinkingResponse<T> createObject(String prompt, Class<T> type);
  ThinkingResponse<Boolean> evaluateCondition(String condition, String context, double threshold);
  ThinkingResponse<AssistantMessage> respond(List<Message> messages);
}
```

**Opportunity:**
- Could enhance our code review and planning agents to expose reasoning chains
- The `ThinkingResponse` likely includes both the result and the reasoning/thinking process
- Would be valuable for debugging why agents make certain decisions
- Could help with /plan command to show architectural reasoning

**Recommendation:** Investigate if we should migrate our agents to use `ThinkingPromptRunnerOperations` for better transparency into decision-making.

---

### 2. **PersonaSpec Interface**
**New Classes:**
- `com.embabel.agent.prompt.persona.PersonaSpec`

**What it provides:**
```java
public interface PersonaSpec extends PromptContributor {
  String getName();
  String getPersona();
  String getVoice();
  String getObjective();
  String contribution();
}
```

**Current State:**
We're using `RoleGoalBackstorySpec` which has:
- `getRole()` → maps to "name" or "persona"
- `getGoal()` → maps to "objective"
- `getBackstory()` → additional context

**Opportunity:**
- `PersonaSpec` adds a `voice` attribute for tone/style
- Could provide more nuanced persona definitions
- May want to evaluate if `PersonaSpec` is a better fit than `RoleGoalBackstorySpec`

**Recommendation:** LOW priority. `RoleGoalBackstorySpec` seems sufficient for our needs.

---

### 3. **Domain Objects for Web Content**
**New Classes:**
- `com.embabel.agent.domain.library.InternetResource`
- `com.embabel.agent.domain.library.InternetResources`
- `com.embabel.agent.domain.library.Page`

**What it provides:**
```java
public class InternetResource implements Page {
  String getUrl();
  String getSummary();
}
```

**Current State:**
We have our own `WebSearchTool` that:
- Uses HtmlUnit/Jsoup for fetching
- Has custom caching
- Returns custom `SearchResult` objects

**Opportunity:**
- Could standardize on Embabel's domain objects
- May integrate better with other Embabel features
- Unclear if Embabel provides the actual fetching or just the data model

**Recommendation:** MEDIUM priority. Investigate if Embabel 0.3.2 includes web fetching capabilities that could replace our `WebSearchTool` implementation.

---

### 4. **Action Quality of Service (QoS)**
**New Classes:**
- `com.embabel.agent.api.annotation.support.ActionQosProvider`
- `com.embabel.agent.api.annotation.support.DefaultActionQosProvider`

**What it provides:**
```java
public interface ActionQosProvider {
  ActionQos provideActionQos(Method method, Object bean);
}
```

**Opportunity:**
- Could provide performance metrics and monitoring for agent actions
- Might support timeout configuration, retries, or rate limiting
- Could be useful for tracking which actions are slow or expensive

**Recommendation:** LOW priority. Investigate later if we need action-level performance monitoring.

---

### 5. **System-Level I/O**
**New Classes:**
- `com.embabel.agent.domain.io.SystemInput`
- `com.embabel.agent.domain.io.SystemOutput`
- `com.embabel.agent.domain.io.AssistantContent`
- `com.embabel.agent.domain.io.UserContent`
- `com.embabel.agent.domain.io.FileArtifact`

**Current State:**
We use `UserInput` (which still exists in 0.3.2)

**Opportunity:**
- Richer type system for different kinds of inputs/outputs
- `FileArtifact` could be useful for file-based operations
- `SystemInput`/`SystemOutput` might help distinguish system vs user messages

**Recommendation:** LOW priority. Our current use of `UserInput` works fine. Consider if we need file artifact support.

---

### 6. **Nested Object Creation Example**
**New Classes:**
- `com.embabel.agent.api.common.nested.ObjectCreationExample`

**Opportunity:**
- May provide examples or utilities for creating nested/complex objects
- Could help with structured output generation

**Recommendation:** LOW priority. Unclear what this provides without further investigation.

---

## Recommendations Summary

### High Priority
1. **Investigate ThinkingPromptRunnerOperations**
   - Add thinking/reasoning capability to code review and planning agents
   - Would provide transparency into agent decision-making
   - Could significantly improve /plan and /review commands

### Medium Priority
2. **Evaluate InternetResource integration**
   - Check if Embabel provides web fetching beyond just domain objects
   - Could simplify our WebSearchTool if Embabel handles fetching

### Low Priority
3. **ActionQosProvider** - For future performance monitoring
4. **PersonaSpec** - Alternative to RoleGoalBackstorySpec
5. **FileArtifact** - If we need explicit file artifact handling
6. **SystemInput/SystemOutput** - If we need richer I/O types

## Migration Status

✅ **Completed:**
- Migrated from `RoleGoalBackstory` static builders to `RoleGoalBackstorySpec` builders
- All personas now use `RoleGoalBackstorySpec`
- All method signatures updated
- All 297 tests passing

## Next Steps

1. Research `ThinkingPromptRunnerOperations` API
2. Create proof-of-concept for reasoning-enabled code review
3. Check Embabel documentation for web search capabilities
4. Evaluate if any current workarounds can be removed

## Notes

- No breaking changes found beyond the `RoleGoalBackstorySpec` builder migration (already completed)
- All existing functionality works with 0.3.2
- Most new features are additive and optional
