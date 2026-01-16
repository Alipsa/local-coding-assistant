# Code Search Improvements

## Issues Identified from Session Output

### Problem 1: Literal String Matching Only
**Current behavior:**
- `CodeSearchTool.search()` uses `indexOf(query)` for literal substring matching (line 96)
- Query "welcome message" won't find `printWelcome()` method
- No support for:
  - Case-insensitive search
  - Regex patterns
  - Multiple keywords (OR logic)
  - Fuzzy matching

**Impact:** Users need to guess exact text rather than searching by concept

### Problem 2: No Search Transparency
**Current behavior:**
- When codesearch returns no results, user doesn't know what was searched for
- When asked "how did you search?", LLM gave generic unhelpful response
- No logging or display of actual query used

**Impact:** Users can't debug why search failed

### Problem 3: Poor Query Extraction from Natural Language
**Current behavior:**
- IntentCommandMapper extracts query from user's natural language request
- For "locate the class responsible for printing the welcome message":
  - Likely extracted "welcome message" or "printing the welcome message"
  - Should have extracted "printWelcome" or "welcome"

**Impact:** Natural language queries often fail

## Recommendations

### High Priority
1. **Add case-insensitive search option**
   ```groovy
   int idx = caseInsensitive
     ? current.text.toLowerCase().indexOf(query.toLowerCase())
     : current.text.indexOf(query)
   ```

2. **Show the actual query used in output**
   ```groovy
   if (hits.isEmpty()) {
     return formatSection("Code Search", "No matches found for query: '${query}'")
   }
   ```

3. **Add better query extraction guidance in IntentRouterAgent**
   - Extract key technical terms (class names, method names, keywords)
   - Prefer shorter, more specific queries
   - Try multiple queries if first fails

### Medium Priority
4. **Support regex patterns**
   - Add `--regex` flag to `/codesearch`
   - Use Pattern.compile() when regex enabled

5. **Add multi-keyword OR search**
   - Accept multiple `--query` parameters
   - Match if any query matches

6. **Suggest alternative queries on no results**
   ```groovy
   if (hits.isEmpty()) {
     return """No matches for '${query}'.
     Try:
     - Different keywords (e.g. 'printWelcome' instead of 'welcome message')
     - Case-insensitive search with --case-insensitive
     - Broader terms"""
   }
   ```

### Low Priority
7. **Add semantic search for method/class finding**
   - Parse code structure (AST)
   - Search by method name, class name, comments
   - Match by purpose/description

## Quick Wins

### 1. Add -i flag for case-insensitive (5 min)
```groovy
@ShellOption(defaultValue = "false", help = "Case-insensitive search") boolean caseInsensitive
```

### 2. Show query in no-results message (2 min)
```groovy
formatSection("Code Search", "No matches found for: '${query}'")
```

### 3. Add search tips to help (1 min)
Update `/help` or `/codesearch --help` with:
- Search is case-sensitive and literal
- Use exact text from code
- Try class/method names rather than descriptions

## Testing the Welcome Message Search

To find the welcome message currently, users would need to search for:
- "Local Coding Assistant" (exact text)
- "printWelcome" (method name)
- "Type naturally to interact" (part of text)

NOT:
- "welcome message" (concept, not in code)
- "greeting" (concept, not in code)
