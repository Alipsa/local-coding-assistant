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

### 1. `pom.xml`

- Remove `com.fasterxml.jackson.core:jackson-databind` and
  `com.fasterxml.jackson.datatype:jackson-datatype-jsr310`.
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
- Behaviour change: `/mcp call x_y {"a":1} extra` now fails with "Invalid JSON arguments" instead of
  silently ignoring `extra`. Keep the strict behaviour for typed input and mention it in the release
  notes.

### 6. `StepActionSpec`

Switch to `tools.jackson.databind.ObjectMapper`. This has already been checked to pass under
Jackson 3.

### 7. New Spock tests

- A corrupt `metadata.json` or `vectors.json` logs a warning and the store still constructs. Cover
  both stores.
- A Jackson 2-format `metadata.json` with numeric `Instant`s loads correctly.
- `ToolCallParser` accepts an MCP call with trailing text inside the matched braces, e.g.
  `mcp_s_t({"a":1} x })`.

### 8. Documentation

- `release.md` (1.3.0):
  - Memory metadata timestamps are now written as ISO-8601 strings. Existing files still load, and
    older LCA versions can read the new files.
  - `/mcp call` now rejects trailing text after the JSON arguments.
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
