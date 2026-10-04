# Plan: migrate LCA's own code from Jackson 2 to Jackson 3

## Summary

Jackson 2 cannot be removed from the classpath entirely. Embabel's `embabel-common-textio` depends on
`com.hubspot.jinjava:jinjava` 2.8.3, which declares `jackson-databind`, `jackson-core`,
`jackson-annotations`, `jackson-dataformat-yaml` and `jackson-datatype-jdk8` (2.x) at compile scope.

LCA's own code can still move to Jackson 3 (`tools.jackson.*`). That lets us drop the two direct
Jackson 2 dependencies (`jackson-databind` and `jackson-datatype-jsr310`). Embabel 1.5.2 already uses
Jackson 3 throughout, including for structured output (`JacksonOutputConverter`).

## Current Jackson 2 usage

| File | Jackson 2 API used |
|---|---|
| `src/main/groovy/se/alipsa/lca/tools/ToolCallParser.groovy` | `ObjectMapper`, `JsonParser.Feature` (lenient parsing) |
| `src/main/groovy/se/alipsa/lca/memory/MemoryMetadataStore.groovy` | `ObjectMapper`, `MapType`, `JavaTimeModule` |
| `src/main/groovy/se/alipsa/lca/memory/SimpleCosineMemoryIndex.groovy` | `ObjectMapper`, `TypeReference` |
| `src/main/groovy/se/alipsa/lca/shell/McpCommands.groovy` | `ObjectMapper` |
| `src/main/groovy/se/alipsa/lca/mcp/McpConfigLoader.groovy` | `ObjectMapper` |
| `src/test/groovy/se/alipsa/lca/team/StepActionSpec.groovy` | `ObjectMapper` |

`StepAction` imports `com.fasterxml.jackson.annotation.JsonCreator`. That stays as it is: Jackson 3
still uses `jackson-annotations` 2.x.

## Verified behaviour (Jackson 2.21.5 vs 3.1.5, against LCA's compiled classes)

| Case | Result |
|---|---|
| Jackson 3 reads a `metadata.json` written by Jackson 2 (numeric `Instant`s, e.g. `1791108930.123456789`) | ✅ identical `MemoryEntry` |
| Older LCA (Jackson 2) reads Jackson 3 output (ISO-8601 `Instant` strings) | ✅ downgrade-safe |
| Jackson 2 `vectors.json` (`Map<String, float[]>`) read by Jackson 3 | ✅ |
| Lenient parsing via `JsonReadFeature` (unquoted names, single quotes, trailing comma) | ✅ |
| `StepAction` `@JsonCreator` fallback under Jackson 3 (`ADD_FILE` → `CREATE`) | ✅ |
| Number types when reading into a `Map` | unchanged (`Integer`, `Long`, `Double`) |
| **Corrupt JSON file** | ⚠️ Jackson 3 throws `UnexpectedEndOfInputException`, which is **not** an `IOException` |
| **Write failure** | ⚠️ Jackson 3 throws `JacksonIOException`, which is **not** an `IOException` |
| **Trailing text after the JSON** (`{"a":1} junk`) | ⚠️ Jackson 2 ignores it; Jackson 3 rejects it (`FAIL_ON_TRAILING_TOKENS` is on by default) |

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

- `src/test/resources/memory/jackson2/metadata.json`: at least one `Instant` with full nanosecond
  precision (e.g. `2026-10-04T10:15:30.123456789Z`, written as `1791108930.123456789`), one with
  zero nanoseconds, and one entry with `projectId: null`.
- `src/test/resources/memory/jackson2/vectors.json`: vectors that include values with no exact
  decimal form (e.g. `0.1f`) and negative values.

These fixtures are the only way to keep testing the real legacy format after the migration, because
ordinary round-trip tests only exercise the new mapper.

### 1. `pom.xml`

- Remove `com.fasterxml.jackson.core:jackson-databind`.
- Change `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` to `<scope>test</scope>`. The
  downgrade-compatibility spec (step 7) needs it to read files the way LCA ≤ 1.2.x does, and
  `jinjava` doesn't bring it in.
- Add `tools.jackson.core:jackson-databind` without a version, because Spring Boot's BOM manages it
  (3.1.5). Today it only reaches LCA indirectly through `jackson-module-kotlin`.
- Update the comment above the dependency.

### 2. `ToolCallParser`

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

### 3. `MemoryMetadataStore`

- Remove `JavaTimeModule`, because Jackson 3 handles `java.time` itself. Use `new ObjectMapper()` and
  `tools.jackson.databind.type.MapType`.
- In both `load()` and `persist()`, catch `IOException | JacksonException`.
  `Files.createTempFile` and `Files.move` still throw `IOException`.

### 4. `SimpleCosineMemoryIndex`

- Import `tools.jackson.core.type.TypeReference`.
- Catch `IOException | JacksonException` in `load()` and `persist()`.

### 5. `McpCommands` and `McpConfigLoader`

- Only the imports change, because both already `catch (Exception)`.
- Behaviour changes from `FAIL_ON_TRAILING_TOKENS`, which is on by default in Jackson 3 (see the
  [Jackson 3 migration guide](https://github.com/FasterXML/jackson/blob/main/jackson3/MIGRATING_TO_JACKSON_3.md)):
  - `/mcp call x_y {"a":1} extra` now fails with "Invalid JSON arguments" instead of silently
    ignoring `extra`. Keep the strict behaviour for typed input.
  - `McpConfigLoader.loadServers()`: an MCP config file with trailing content (e.g. a stray `}` after
    the root object) now throws, and the whole file is skipped with a warning. Every server defined
    in that file disappears; servers from the other config files still load. Jackson 2 ignored the
    trailing content. Keep the strict behaviour, since a malformed file should not half-load, but
    document it (step 8).

### 6. `StepActionSpec`

Switch to `tools.jackson.databind.ObjectMapper`. This has already been checked to pass under
Jackson 3.

### 7. New Spock tests

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

**MCP parsing:**

- `McpCommandsSpec`: `/mcp call srv_tool {"a":1} extra` returns a message containing
  "Invalid JSON arguments", and `0 * registry.callTool(_, _, _)`.
- `McpConfigLoaderSpec`: given two config files where the second has a trailing `}`, the servers
  from the first file load, the second file's servers are absent, and no exception escapes.
- `ToolCallParserMcpSpec`: an MCP call with trailing text inside the matched braces, e.g.
  `mcp_s_t({"a":1} x })`, is still parsed, because the lenient mapper disables
  `FAIL_ON_TRAILING_TOKENS`.

### 8. Documentation

- `release.md` (1.3.0):
  - Memory metadata timestamps are now written as ISO-8601 strings. Existing files still load, and
    older LCA versions can read the new files.
  - `/mcp call` now rejects trailing text after the JSON arguments.
  - An MCP config file with trailing content after the root JSON object is now skipped entirely,
    with a warning. Its servers are not loaded until the file is fixed.
- No user documentation describes MCP config files yet (`docs/commands.md` doesn't cover `/mcp`;
  config is set by `assistant.mcp.servers-configuration`). The release note is enough. If an MCP
  section is added to `docs/commands.md` later, it should mention that a malformed file is skipped
  entirely.
- Leave `docs/superpowers/plans/2026-06-09-mcp-support.md` alone. It is a historical plan that
  still shows the Jackson 2 code.

### 9. Optional guard

Jackson 2 stays on the compile classpath through `jinjava`, so a new
`com.fasterxml.jackson.databind` import would still compile. `./mvnw dependency:analyze` reports
such imports as "used undeclared"; it can be run by hand or added to CI.

### 10. Verification

- Run `./mvnw test`.
- Start LCA by hand against an existing memory index (`~/.lca/memory-index`, set by `lca.memory.index-directory`) to confirm the old files load.

## Cosmetic notes (no action needed)

- Jackson 3 sorts properties alphabetically by default, so the field order in saved memory JSON
  changes.
- `McpConfigLoader.writeConsolidatedConfig()` is called only from tests, not from production code.
