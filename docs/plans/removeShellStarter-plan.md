# Plan: remove `embabel-agent-starter-shell`

## Summary

LCA does not use anything from `embabel-agent-starter-shell` at runtime. The REPL is LCA's own JLine
code, Spring Shell's auto-configuration is already excluded, and Embabel's `shellCommands` bean is
removed by `ShellCommandExclusionsConfiguration`. Removing the starter has two consequences:

1. `ShellCommands.groovy` stops compiling. It uses the Spring Shell annotations `@ShellMethod` and
   `@ShellOption`, which nothing reads at runtime.
2. The application would start an embedded Tomcat server on port 8080. The starter currently prevents
   this as a side effect, so the plan sets `spring.main.web-application-type=none` explicitly.

The second point also explains an existing problem: the [REST API](../rest.md) cannot be started
today. After this change it can be switched on with a command-line flag (step 6).

## Findings (verified on `main` at `b97009f`)

### What the starter brings in

`embabel-agent-starter-shell` 1.5.2 brings in:

- `embabel-agent-shell-autoconfigure` and `embabel-agent-shell`;
- `spring-shell-starter` 3.4.0, with `spring-shell-autoconfigure`, `-core`, `-standard`,
  `-standard-commands` and `-table`;
- JLine `jline-console`, `jline-builtins` and `jline-style` 3.26.3, plus `ST4`;
- `commons-text` 1.10.0 (LCA does not import it);
- `spring-boot-starter-logging`, which `spring-boot-starter` also provides, so logging is unaffected.

### What LCA uses

| Item | Used by LCA? | Evidence |
|---|---|---|
| Spring Shell runtime | No | `spring.shell.interactive.enabled=false`; every `org.springframework.shell.boot.*` auto-configuration is in `spring.autoconfigure.exclude` |
| Embabel `ShellCommands` bean | No | Removed by `ShellCommandExclusionsConfiguration` |
| Embabel `TerminalServices` (`GoalChoiceApprover`) | No | Not injected anywhere. Embabel's `Autonomy` takes a `GoalChoiceApprover` as a method argument, and LCA does not use `Autonomy` |
| Embabel prompt providers (Star Wars and others) | No | These are Spring Shell `PromptProvider`s. The Star Wars *logging* theme used by `@EnableAgents` lives in `embabel-agent-api` |
| `@ShellMethod` and `@ShellOption` | Compile time only | No code reads them by reflection. `CommandExecutor` and `ShellCommandController` call `ShellCommands` methods directly, and pass every argument |
| `@ShellComponent` | No | Imported but unused (the class is `@Component("lcaShellCommands")`) |

`CommandExecutor` converts kebab-case flags with its own `toCamelCase()`, so option names don't depend
on the annotations. `/help` is a hard-coded list.

### The web server side effect

`embabel-agent-shell-autoconfigure` registers `ShellEnvironmentPostProcessor`, which runs
unconditionally. Spring Boot 4.1.1 still loads it through the deprecated
`org.springframework.boot.env.EnvironmentPostProcessor` key. It adds a property source named
`shellModeProperties` with `addFirst`, which gives it precedence over every other source. That source
sets:

- `spring.main.web-application-type=none`;
- `spring.shell.interactive.enabled=true`, which overrides LCA's `false`. This is harmless only
  because the auto-configuration is excluded.

Tested with a small program that prints the environment once Spring Boot has prepared it:

```
With the starter:    spring.main.web-application-type=none  (first source: shellModeProperties)
                     --spring.main.web-application-type=servlet on the command line → still none
Without the starter: unset → Spring Boot detects SERVLET (Tomcat is on the runtime classpath)
```

Consequences:

- **Without the starter**, every CLI, GUI and batch launch would start Tomcat on port 8080.
- **The REST API cannot run today.** `docs/rest.md` describes it, but nothing can override the forced
  `none`. The controller specs use `MockMvcBuilders.standaloneSetup` and never start a server, so they
  did not catch this.

### Trial removal

The trial ran in a temporary worktree: the starter removed, and `spring-shell-standard` and
`spring-shell-core` 3.4.0 declared directly to keep the annotations compiling. `./mvnw test` passed
with 1247 tests and 0 failures. Two points from the trial:

- `spring-shell-standard` alone is not enough. `@ShellMethod` references
  `org.springframework.shell.context.InteractionMode` from `spring-shell-core`, which
  `spring-shell-standard` declares only at runtime scope, so compilation fails with
  `NoClassDefFoundError`.
- Declaring `spring-shell-core` directly downgrades `commons-io` from 2.22.0 (via `htmlunit`) to
  2.18.0, because Maven picks the version that is nearer, or declared first.

These are the reasons the plan removes the annotations instead of keeping Spring Shell on the classpath.

## Approach

Remove the starter and all Spring Shell usage, and keep today's behaviour of not running a web server.

Alternatives considered and rejected:

- **Keep the annotations** by declaring `spring-shell-standard` and `spring-shell-core`. This keeps
  about 10 Spring Shell and JLine jars only for annotations nobody reads, and needs a `commons-io` pin.
- **Use `provided` scope for the annotations.** Spring Boot's repackaged jar still includes `provided`
  dependencies, so nothing would be gained.
- **Introduce LCA's own `@Command` and `@Option` annotations.** This is a structural change with no
  reader of the annotations today. It can be revisited if `/help` or the REST layer ever generates its
  output from metadata.

## Steps

### 1. `pom.xml`

- Remove the `com.embabel.agent:embabel-agent-starter-shell` dependency.
- Update the JLine comment ("Direct dependencies of the custom JLine REPL; independent of Spring
  Shell.") to drop the Spring Shell reference, since Spring Shell is no longer on the classpath.
- Do not add any Spring Shell dependency.

### 2. `ShellCommands.groovy`: remove the Spring Shell annotations

- Remove the three `org.springframework.shell.standard.*` imports.
- Replace each `@ShellMethod(key = [...], value = "...")` with a short Groovydoc comment that keeps the
  command key and description. There are 28 such methods, and none currently has Groovydoc.
- Remove each `@ShellOption(...)` (139 occurrences). Keep its `help` text, and any non-null
  `defaultValue`, as an `@param` line. These annotations are the only place the option meanings and
  valid values are written down, for example `htmlunit/jsoup/disabled/default`.
  `defaultValue = ShellOption.NULL` carries no information and is dropped.
- Do not change method signatures, parameter names, the bean name `lcaShellCommands`, or any logic.
  `CommandExecutor`, `ShellCommandController` and `BangCommandHandler` call these methods directly with
  explicit arguments.
- Keep lines within 120 characters and use 2-space indentation (AGENTS.md).

Example:

```groovy
/**
 * {@code /config}: view or update shell settings.
 *
 * @param autoPaste enable or disable auto-paste detection (true/false)
 * @param localOnly {@code --local-only}: enable or disable local-only mode for this session (true/false)
 * ...
 */
String config(Boolean autoPaste, Boolean localOnly, ...) {
```

Where an option was renamed through `value = "local-only"` or `value = "--prompt"`, record the CLI
flag name in the `@param` line.

The removal is mechanical and can be scripted. Review the diff to check that each Groovydoc block sits
directly above its method and that no method body changed: `git diff --stat` and a diff that ignores
comment lines should show only removed annotation lines.

### 3. Delete the Embabel bean exclusion

- Delete `src/main/groovy/se/alipsa/lca/shell/ShellCommandExclusionsConfiguration.groovy`.
- Delete `src/test/groovy/se/alipsa/lca/shell/ShellCommandExclusionsConfigurationSpec.groovy`.

Once the starter is gone, `com.embabel.agent.shell.ShellCommands` is no longer on the classpath, so
the post-processor would never find anything to remove.

### 4. `application.properties`

Replace the Spring Shell block (the "Disable Spring Shell completely" comment,
`spring.shell.interactive.enabled=false` and the whole `spring.autoconfigure.exclude` list, which
contains only `org.springframework.shell.boot.*` classes) with:

```properties
# No embedded web server by default. The REST API (docs/rest.md) is opt-in; server-only:
#   lca --spring.main.web-application-type=servlet --lca.repl.enabled=false
spring.main.web-application-type=none
```

Check first that `spring.autoconfigure.exclude` has no non-Spring Shell entries. As of `b97009f` it
has none. No other properties file references `spring.shell.*` (checked with `grep`).

### 5. Tests

Add `src/test/groovy/se/alipsa/lca/WebApplicationTypeSpec.groovy` (Spock 2.4, `@CompileStatic` not
required for specs). It drives Spring Boot's real environment preparation without starting a context:

- Create `new SpringApplication(Object)`.
- Add an `ApplicationListener<ApplicationEnvironmentPreparedEvent>`. It runs after
  `EnvironmentPostProcessorApplicationListener`, because that listener is ordered and an unordered
  one runs last. It captures the environment and then throws a private sentinel exception to stop
  start-up.
- Call `app.run(args)` and catch the sentinel.

Feature methods:

1. **No arguments.** `spring.main.web-application-type` resolves to `none`, and the environment has
   no `shellModeProperties` property source. This guards against a dependency bringing the Embabel
   shell post-processor back.
2. **`--spring.main.web-application-type=servlet`.** It resolves to `servlet`, which proves the REST
   opt-in documented in step 6 works.

Don't remove the system environment property source. The only variable that could interfere is
`SPRING_MAIN_WEB_APPLICATION_TYPE`; note it in the spec's comment instead of manipulating the real
environment.

### 6. Documentation

- **`docs/architecture.md`:**
  - Remove the `embabel-agent-starter-shell` line from "Maven Dependencies" (line 124).
  - The version numbers in that list (0.3.1) are stale. Updating them to 1.5.2 is optional and
    separate from this change.
- **`docs/rest.md`:** add an "Enabling the REST API" section near the top. It should cover:
  - The API is off by default; start a server-only instance with
    `lca --spring.main.web-application-type=servlet --lca.repl.enabled=false`. `lca` passes its
    arguments through to `java -jar`, as on `src/main/bin/lca` lines 311 and 315.
  - Why `--lca.repl.enabled=false` matters: without it the REPL also starts, and `ReplRunner` calls
    `System.exit(0)` when stdin reaches EOF (`ReplRunner.groovy` line 35). A launch in the background
    or under a service manager, with no terminal on stdin, would stop the server straight away.
    Leaving the REPL enabled is fine for an interactive session that also serves REST.
  - `--server.port=<port>` changes the port from the default 8080.
  - The existing local-only, remote-access and HTTPS notes still apply.

  Add this section only if the smoke test in the Verification section passes. Otherwise leave
  `docs/rest.md` unchanged and open an issue (see Risks).
- **`release.md`, 1.3.0 under "Configuration":**
  - Removed the unused `embabel-agent-starter-shell` dependency (Spring Shell is no longer on the
    classpath).
  - LCA continues to disable the embedded web server by default, now through an explicit
    `spring.main.web-application-type=none`. The REST API, which could not previously be started, can
    now be enabled with `--spring.main.web-application-type=servlet` (add `--lca.repl.enabled=false`
    for a server-only instance). Again, include the second bullet only if the REST smoke test passes.

## Verification

1. `./mvnw test` passes. This includes `BatchModeIntegrationSpec`, which starts
   `LocalCodingAssistantApplication` in a subprocess on the test classpath with the `batch-test`
   profile. `BatchTestModelConfiguration` supplies a fake `ChatModel` and `EmbeddingService`. That
   spec starts the full Spring context, so it would catch a bean the removed starter used to
   provide. It does not cover the production model configuration, the GUI, the interactive REPL or
   REST, so the manual checks below are still needed.
2. `./mvnw dependency:tree` shows no `org.springframework.shell`, `embabel-agent-shell*` or
   `commons-text`, and `commons-io` stays at 2.22.0.
3. `grep -rn 'org.springframework.shell\|ShellMethod\|ShellOption' src/main src/test` returns nothing.
4. Build and install the candidate before any manual check. `lca` runs the newest
   `local-coding-assistant-*-exec.jar` in `~/.local/lib` (`find_latest_local_jar`, `src/main/bin/lca`
   line 236), so without this step the checks would test the installed release and its old
   dependencies:
   - `./mvnw -DskipTests package`, then `lca install target/local-coding-assistant-<version>-exec.jar`.
   - Confirm that `/version` in the REPL reports `<version>`.
   - Afterwards, remove the snapshot jar from `~/.local/lib` if the previous release should be the
     default again, since `sort -V` picks the snapshot over 1.2.0.
   - Alternative: run `java -jar target/local-coding-assistant-<version>-exec.jar` directly. This
     skips the launcher's prerequisite checks, model environment variables and JLine flags, so
     prefer the installed route for the REPL and GUI checks.
5. Manual start-up with Ollama running and the production model configuration:
   - `lca`: run `/help`, `/config`, `/config --web-search jsoup`, `/chat hello` and `/exit`.
   - `lca gui`: the window opens and a chat round-trip works.
   - Batch mode with `-c "/help"` exits with status 0.
   - While each run is active, `lsof -nP -iTCP:8080 -sTCP:LISTEN` shows no listener from LCA.
6. REST smoke test, run from inside a git repository:
   - Start a server-only instance with
     `lca --spring.main.web-application-type=servlet --lca.repl.enabled=false`.
   - Run `curl --fail-with-body -s localhost:8080/api/cli/status`. Remote access is blocked by
     default, so call it from localhost.
   - The command must exit with status 0 (HTTP 2xx). The body is plain text, not JSON:
     `ShellCommandController.status` returns `ShellCommands.gitStatus`, which formats the result
     as `Status succeeded` followed by the git output.
   - Stop the server afterwards.

## Risks and notes

- **The REST API has never run on the current stack** (Spring Boot 4.1, Jackson 3), because the
  starter has blocked the web server since it was added. If the smoke test in Verification step 6
  fails, the removal can still go ahead: the default (`none`) matches today's behaviour. In that case
  record the failure as a separate issue and leave out the REST documentation in step 6.
- **Spring Boot 4.1 ignores exclusions of classes not on the classpath.** This is from Spring Boot's
  `AutoConfigurationImportSelector` behaviour and wasn't tested here. It means leaving the old exclude
  list in place would not break start-up, but step 4 removes it anyway.
- **Users who set `embabel.agent.shell.*` properties** will find them ignored silently. No such
  properties appear in the repository.
- **The bean name `lcaShellCommands`** was chosen to avoid clashing with Embabel's `shellCommands`. It
  can stay, since renaming it is out of scope.

## Out of scope

- Making REST endpoints work if the smoke test fails.
- Generating `/help` from method metadata.
- Updating the stale dependency versions in `docs/architecture.md`.
