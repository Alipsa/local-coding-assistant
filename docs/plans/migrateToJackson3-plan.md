# Plan: move LCA's own code off Jackson 2

## Summary

Jackson 2 cannot be removed from the classpath entirely. Embabel's `embabel-common-textio` depends on
`com.hubspot.jinjava:jinjava` 2.8.3, which declares `jackson-databind`, `jackson-core`,
`jackson-annotations`, `jackson-dataformat-yaml` and `jackson-datatype-jdk8` (2.x) at compile scope.

LCA's own code can still stop using Jackson 2, which lets us drop the direct Jackson 2 dependencies.
The work splits two ways:

- **`groovy-json`** (already a direct dependency, used by `IntentRouterParser`, `ModelRegistry`,
  `SastTool`, `GitTool` and `IntentRoutingDebugFormatter`) for simple `Map` read/write in
  `McpCommands` and `McpConfigLoader`.
- **Jackson 3** (`tools.jackson.*`) for `ToolCallParser` and the two memory stores. Jackson 3 can't
  be avoided anyway:
  - Embabel 1.5.2 uses it throughout, including for structured output (`JacksonOutputConverter`).
  - `CodingAssistantAgent` already imports `tools.jackson.databind.ObjectMapper` and
    `@JsonDeserialize`.

`groovy-xml` is not on the classpath and is not relevant.

## Current Jackson 2 usage and target

| File | Jackson 2 API used | Target |
|---|---|---|
| `src/main/groovy/se/alipsa/lca/shell/McpCommands.groovy` | `ObjectMapper` | `groovy-json` |
| `src/main/groovy/se/alipsa/lca/mcp/McpConfigLoader.groovy` | `ObjectMapper` | `groovy-json` |
| `src/main/groovy/se/alipsa/lca/tools/ToolCallParser.groovy` | `ObjectMapper`, `JsonParser.Feature` (lenient parsing) | Jackson 3 |
| `src/main/groovy/se/alipsa/lca/memory/MemoryMetadataStore.groovy` | `ObjectMapper`, `MapType`, `JavaTimeModule` | Jackson 3 |
| `src/main/groovy/se/alipsa/lca/memory/SimpleCosineMemoryIndex.groovy` | `ObjectMapper`, `TypeReference` | Jackson 3 |
| `src/test/groovy/se/alipsa/lca/team/StepActionSpec.groovy` | `ObjectMapper` | Jackson 3 (what Embabel uses) |

`StepAction` imports `com.fasterxml.jackson.annotation.JsonCreator`. That stays as it is: Jackson 3
still uses `jackson-annotations` 2.x.

## Why the split

### `groovy-json` results (Groovy 5.1.3)

| Case | Result |
|---|---|
| `/mcp call` arguments and MCP config files → `Map` | ✅ works |
| Trailing text after the JSON (`{"a":1} extra`, a stray trailing `}`) | ✅ ignored by the default parser, as Jackson 2 did, so there is no behaviour change |
| `LAX` parser on `{this is not json at all!!!}` | ❌ returns `[:]` instead of throwing (details below) |
| `LAX` parser on unquoted values (`{e: yes}`) | ❌ accepted as the string `"yes"` |
| Decimal numbers | ⚠️ parsed as `BigDecimal`; Jackson parses them as `Double` |
| `Instant` | ⚠️ default `JsonOutput` writes `{"epochSecond":…,"nano":…}`. It needs a `JsonGenerator` converter, and legacy numeric timestamps come back as `BigDecimal`, so each needs manual mapping. |
| `float[]` vectors | ✅ values match exactly (including the Jackson 2 format), but ⚠️ about 4× slower (below) |

**Why `LAX` is unsafe for `ToolCallParser`:** an empty map would run the MCP tool with no arguments.
Today the parser records an error instead (`ToolCallParserMcpSpec` "skips completely invalid JSON and
adds error").

**Vector index speed:** with 2,000 memories × 768 dimensions (a 16 MB file; the test size is an
assumption), `groovy-json` takes about 285 ms to write and 200–300 ms to read. Jackson 3 takes about
60 ms and 45 ms. `persist()` rewrites the whole file on every `remember`/`delete`, so this cost
applies to every write, not just startup.

### Jackson 3 results (2.21.5 vs 3.1.5, against LCA's compiled classes)

| Case | Result |
|---|---|
| Jackson 3 reads a `metadata.json` written by Jackson 2 (numeric `Instant`s, e.g. `1791108930.123456789`) | ✅ identical `MemoryEntry` |
| Older LCA (Jackson 2) reads Jackson 3 output (ISO-8601 `Instant` strings) | ✅ downgrade-safe |
| Jackson 2 `vectors.json` (`Map<String, float[]>`) read by Jackson 3 | ✅ |
| Lenient parsing via `JsonReadFeature` (unquoted names, single quotes, trailing comma) | ✅ |
| `StepAction` `@JsonCreator` fallback under Jackson 3 (`ADD_FILE` → `CREATE`) | ✅ |
| **Corrupt JSON file** | ⚠️ throws `UnexpectedEndOfInputException`, which is **not** an `IOException` |
| **Write failure** | ⚠️ throws `JacksonIOException`, or `DatabindException` when a getter throws; neither is an `IOException` |
| **Trailing text after the JSON** | ⚠️ rejected (`FAIL_ON_TRAILING_TOKENS` is on by default, see the [Jackson 3 migration guide](https://github.com/FasterXML/jackson/blob/main/jackson3/MIGRATING_TO_JACKSON_3.md)); only `ToolCallParser` needs Jackson 2's tolerance back |

### Main risk

`MemoryMetadataStore` and `SimpleCosineMemoryIndex` are `@Component`s that load their files in the
constructor and only `catch (IOException)`. If we only swap the imports, one corrupt memory file
would make bean creation fail and stop LCA from starting. No existing test covers corrupt files, so
`./mvnw test` would not catch this.

### Existing issue

`StepActionSpec` tests `StepAction` deserialisation with a Jackson 2 `ObjectMapper`. Since PR #55,
Embabel deserialises structured output with Jackson 3, so the spec is testing the wrong library.

## Steps

### 0. Capture legacy fixtures first (before changing any code)

On the current Jackson 2 code, write a small memory index with the real `MemoryMetadataStore` and
`SimpleCosineMemoryIndex`, then commit the files as test resources:

- `src/test/resources/memory/jackson2/metadata.json`:
  - at least one `Instant` with full nanosecond precision (e.g. `2026-10-04T10:15:30.123456789Z`,
    written as `1791108930.123456789`)
  - one `Instant` with zero nanoseconds
  - one entry with `projectId: null`
- `src/test/resources/memory/jackson2/vectors.json`: vectors that include values with no exact
  decimal form (e.g. `0.1f`) and negative values.

These fixtures are the only way to keep testing the real legacy format after the migration, because
ordinary round-trip tests only exercise the new mapper.

### 1. `pom.xml`

- Remove `com.fasterxml.jackson.core:jackson-databind`.
- Change `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` to `<scope>test</scope>`. The
  downgrade-compatibility spec (step 8) needs it to read files the way LCA ≤ 1.2.x does, and
  `jinjava` doesn't bring it in.
- Add `tools.jackson.core:jackson-databind` without a version, because Spring Boot's BOM manages it
  (3.1.5). Today it only reaches LCA indirectly through `jackson-module-kotlin`.
- Update the comment above the dependency.

### 2. `McpCommands` → `groovy-json`

- Replace the `ObjectMapper` field with `new JsonSlurper().parseText(jsonPart)`, using the default
  parser (not `LAX`).
- The class is `@CompileStatic`, so cast the result explicitly. A JSON array or scalar root then
  fails the cast inside the existing `catch (Exception)` and still returns "Invalid JSON arguments".
- Trailing text stays tolerated, as with Jackson 2.
- Decimal arguments become `BigDecimal` instead of `Double`. They are passed to
  `McpToolRegistry.callTool` and serialised by the MCP SDK, so they should still reach the server as
  JSON numbers. This is unverified; step 8 adds a test.

### 3. `McpConfigLoader` → `groovy-json`

- Reading: `new JsonSlurper().parse(path.toFile(), 'UTF-8')`, cast to `Map<String, Object>`. Name the
  charset explicitly; Jackson detected the encoding automatically. The existing `catch (Exception)`
  still skips unreadable files, including empty files and a non-object root.
- Writing (`writeConsolidatedConfig`): `JsonOutput.prettyPrint(JsonOutput.toJson(output))` written
  with `Files.writeString`.
  - This method is called only from tests, not from production code.
  - Its existing assertions only use `contains`, so the different indentation does not matter.

### 4. `ToolCallParser` → Jackson 3

The feature names have moved; `ALLOW_UNQUOTED_FIELD_NAMES` is now `ALLOW_UNQUOTED_PROPERTY_NAMES`.

```groovy
import tools.jackson.core.json.JsonReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper

private static final ObjectMapper lenientMapper = JsonMapper.builder()
  .enable(JsonReadFeature.ALLOW_UNQUOTED_PROPERTY_NAMES, JsonReadFeature.ALLOW_SINGLE_QUOTES,
    JsonReadFeature.ALLOW_TRAILING_COMMA)
  .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS) // keep Jackson 2 leniency for LLM output
  .build()
```

### 5. `MemoryMetadataStore` → Jackson 3

- Remove `JavaTimeModule`, because Jackson 3 handles `java.time` itself. Use `new ObjectMapper()` and
  `tools.jackson.databind.type.MapType`.
- In both `load()` and `persist()`, catch `IOException | JacksonException`.
  `Files.createTempFile` and `Files.move` still throw `IOException`.

### 6. `SimpleCosineMemoryIndex` → Jackson 3

- Import `tools.jackson.core.type.TypeReference`.
- Catch `IOException | JacksonException` in `load()` and `persist()`.

### 7. `StepActionSpec`

Switch to `tools.jackson.databind.ObjectMapper`. This has already been checked to pass under
Jackson 3.

### 8. New Spock tests

**Load failures** (both stores):

- A corrupt `metadata.json` or `vectors.json` logs a warning and the store still constructs.

**Persistence failures** (both stores):

- When the Jackson write fails, the mutation returns `false` instead of throwing. That keeps
  `MemoryStore.remember()`'s rollback working (`MemoryStore.groovy:55-70` checks the `indexed`/`stored`
  flags).
- To trigger a write failure, use a value whose getter throws. Jackson 3 wraps it in
  `DatabindException`, a `JacksonException` (verified):
  - `MemoryMetadataStore`: `put(new ExplodingEntry(...))`, where `ExplodingEntry extends MemoryEntry`
    and overrides `getContent()` to throw. Also cover `putAll` and `remove`.
  - `SimpleCosineMemoryIndex`: put an object with a throwing getter into `index.@vectors` (Groovy
    ignores generics), then call `upsert`/`delete`, and assert that `upsert` returns `false` and
    `delete` doesn't throw.
- Optional, existing issue: `persist()` leaves the `*.json.tmp` file behind when the write fails.
  Deleting it in a `finally` block (or on failure) would let the test also assert that no temporary
  file remains.

**Legacy and downgrade compatibility** (uses the step 0 fixtures):

- New store reads the `jackson2/metadata.json` fixture. Assert exact `Instant` equality, including
  `getNano() == 123456789` and the zero-nanosecond entry, and that `projectId` is `null`.
- New index reads the `jackson2/vectors.json` fixture. Assert `Arrays.equals` against the expected
  `float[]` values.
- Downgrade: write entries with the new store, then read the file with a Jackson 2 `ObjectMapper` plus
  `JavaTimeModule`, configured exactly as `MemoryMetadataStore` is on `main` today. Assert the entries
  are equal, nanoseconds included. Do the same for vectors with a plain Jackson 2 `ObjectMapper`. This
  keeps the check inside `./mvnw test` rather than relying on a one-off script.

**MCP parsing (`groovy-json`):**

- `McpCommandsSpec`:
  - `/mcp call srv_tool {"a":1} extra` still calls `registry.callTool('srv', 'tool', [a: 1])`
    (unchanged behaviour).
  - `/mcp call srv_tool [1,2]` and `/mcp call srv_tool {bad` return "Invalid JSON arguments" with
    `0 * registry.callTool(_, _, _)`.
- `McpConfigLoaderSpec`:
  - A config file with a trailing `}` still loads its servers (unchanged behaviour).
  - An empty file or a file whose root is an array is skipped with no exception, and servers from the
    other config files still load.
  - Non-ASCII content in `env` values round-trips.
- MCP arguments: a decimal argument (`{"t":0.5}`) reaches the MCP server as a JSON number. Test this
  at the `McpToolRegistry` or SDK serialisation level, or confirm by hand against a test server.

**`ToolCallParser` (Jackson 3):**

- `ToolCallParserMcpSpec`: an MCP call with trailing text inside the matched braces, e.g.
  `mcp_s_t({"a":1} x })`, is still parsed, because the lenient mapper disables
  `FAIL_ON_TRAILING_TOKENS`. The existing "skips completely invalid JSON and adds error" test must
  keep passing.

### 9. Documentation

- `release.md` (1.3.0): memory metadata timestamps are now written as ISO-8601 strings. Existing
  files still load, and older LCA versions can read the new files.
- No MCP behaviour changes, so there are no MCP release-note bullets.
- Leave `docs/superpowers/plans/2026-06-09-mcp-support.md` alone. It is a historical plan that still
  shows the Jackson 2 code.

### 10. Optional guard

Jackson 2 stays on the compile classpath through `jinjava`, so a new
`com.fasterxml.jackson.databind` import would still compile. `./mvnw dependency:analyze` reports
such imports as "used undeclared"; it can be run by hand or added to CI.

### 11. Verification

- Run `./mvnw test`.
- Start LCA by hand against an existing memory index (`~/.lca/memory-index`, set by
  `lca.memory.index-directory`) to confirm the old files load.
- Run `/mcp status` and one `/mcp call` against a configured server.

## Cosmetic notes (no action needed)

- Jackson 3 sorts properties alphabetically by default, so the field order in saved memory JSON
  changes.
- `writeConsolidatedConfig()` output indentation changes from Jackson's pretty printer to
  `JsonOutput.prettyPrint`.
- `JsonOutput` escapes non-ASCII characters in the consolidated config (e.g. `Å` becomes `Å`).
  It is still valid JSON and round-trips exactly (verified).
