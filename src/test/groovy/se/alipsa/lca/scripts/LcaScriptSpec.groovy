package se.alipsa.lca.scripts

import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Unroll

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.Pattern

import static org.junit.jupiter.api.Assumptions.assumeTrue

class LcaScriptSpec extends Specification {

  @TempDir
  Path tempDir

  @Unroll
  def "upgrade downloads the latest release and removes the old jar (#scriptName)"() {
    given:
    assumeScriptAvailable(scriptName)
    Path scriptPath = projectRoot().resolve("src/main/bin/${scriptName}")
    Path homeDir = tempDir.resolve("home")
    Path binDir = tempDir.resolve("bin")
    Files.createDirectories(binDir)
    writeStubCurl(binDir, releaseJsonFor("1.2.0"))
    Path libDir = homeDir.resolve(".local").resolve("lib")
    Files.createDirectories(libDir)
    Files.writeString(libDir.resolve("local-coding-assistant-1.0.0-exec.jar"), "old")

    when:
    def result = runScript(
      scriptPath,
      ["upgrade"],
      [
        HOME: homeDir.toString(),
        PATH: binDir.toString() + File.pathSeparator + System.getenv("PATH"),
        LCA_FAKE_RELEASE_FILE: binDir.resolve("release.json").toString()
      ]
    )

    then:
    result.exitCode == 0
    Files.exists(libDir.resolve("local-coding-assistant-1.2.0-exec.jar"))
    !Files.exists(libDir.resolve("local-coding-assistant-1.0.0-exec.jar"))
    // Script now outputs informational messages about removing old jars
    result.output.contains("Removing old jar") || result.output.trim().isEmpty()

    where:
    scriptName << scriptNames()
  }

  @Unroll
  def "upgrade reports when already on the latest release (#scriptName)"() {
    given:
    assumeScriptAvailable(scriptName)
    Path scriptPath = projectRoot().resolve("src/main/bin/${scriptName}")
    Path homeDir = tempDir.resolve("home-latest")
    Path binDir = tempDir.resolve("bin-latest")
    Files.createDirectories(binDir)
    writeStubCurl(binDir, releaseJsonFor("2.0.0"))
    Path libDir = homeDir.resolve(".local").resolve("lib")
    Files.createDirectories(libDir)
    Files.writeString(libDir.resolve("local-coding-assistant-2.0.0-exec.jar"), "latest")

    when:
    def result = runScript(
      scriptPath,
      ["upgrade"],
      [
        HOME: homeDir.toString(),
        PATH: binDir.toString() + File.pathSeparator + System.getenv("PATH"),
        LCA_FAKE_RELEASE_FILE: binDir.resolve("release.json").toString()
      ]
    )

    then:
    result.exitCode == 0
    result.output.trim() == "You are already using the latest version of the local coding assistant (2.0.0)"
    Files.exists(libDir.resolve("local-coding-assistant-2.0.0-exec.jar"))

    where:
    scriptName << scriptNames()
  }

  @Unroll
  def "run ensures required models before starting the programme (#scriptName)"() {
    given:
    assumeScriptAvailable(scriptName)
    Path scriptPath = projectRoot().resolve("src/main/bin/${scriptName}")
    Path homeDir = tempDir.resolve("home-run")
    Path binDir = tempDir.resolve("bin-run")
    Files.createDirectories(binDir)
    Path libDir = homeDir.resolve(".local").resolve("lib")
    Files.createDirectories(libDir)
    Files.writeString(libDir.resolve("local-coding-assistant-1.0.0-exec.jar"), "jar")
    Path ollamaLog = tempDir.resolve("ollama.log")
    Path javaLog = tempDir.resolve("java.log")
    Path ollamaState = tempDir.resolve("ollama-state.txt")
    Files.writeString(ollamaState, "other-model:1.0\tid-seed\n")
    writeStubOllama(binDir, ollamaState)
    writeStubJava(binDir)

    when:
    def result = runScript(
      scriptPath,
      [],
      [
        HOME: homeDir.toString(),
        PATH: binDir.toString() + File.pathSeparator + System.getenv("PATH"),
        LCA_OLLAMA_LOG: ollamaLog.toString(),
        LCA_JAVA_LOG: javaLog.toString()
      ]
    )

    then:
    result.exitCode == 0
    def log = Files.readString(ollamaLog)
    log.contains("pull qwen3.8:27b")
    log.contains("pull gpt-oss:20b")
    log.contains("pull nomic-embed-text:latest")
    log.contains("create qwen3.8-192k")
    log.contains("create gpt-oss-64k")
    log.contains("create qwen3.8-review")
    Files.exists(javaLog)
    def javaEnv = Files.readString(javaLog)
    javaEnv.contains("LCA_CHAT_MODEL=qwen3.8-192k:latest")
    javaEnv.contains("LCA_FALLBACK_MODEL=gpt-oss-64k:latest")
    javaEnv.contains("LCA_EMBEDDING_MODEL=nomic-embed-text:latest")
    javaEnv.contains("LCA_REVIEW_MODEL=qwen3.8-review:latest")
    javaEnv.contains("LCA_DEFAULT_CONTEXT_WINDOW=131072")
    // num_batch/num_gpu are performance tuning for the qwen3.8-based models (chat +
    // review), not the gpt-oss fallback.
    modelfileSectionFor(log, "qwen3.8-192k").contains("PARAMETER num_batch 2048")
    modelfileSectionFor(log, "qwen3.8-192k").contains("PARAMETER num_gpu 99")
    modelfileSectionFor(log, "qwen3.8-review").contains("PARAMETER num_batch 2048")
    modelfileSectionFor(log, "qwen3.8-review").contains("PARAMETER num_gpu 99")
    !modelfileSectionFor(log, "gpt-oss-64k").contains("PARAMETER num_batch")
    !modelfileSectionFor(log, "gpt-oss-64k").contains("PARAMETER num_gpu")

    where:
    scriptName << scriptNames()
  }

  def "run rebuilds a custom model whose base model changed, even though it already exists"() {
    given:
    // Simulates switching a base model's backend (e.g. mlx -> llama.cpp): the custom model
    // already exists under the same name, but its recorded base-model id is stale, so a naive
    // "does a model with this name exist?" check must not be enough to skip rebuilding it.
    Path scriptPath = projectRoot().resolve("src/main/bin/lca")
    Path homeDir = tempDir.resolve("home-rebuild")
    Path binDir = tempDir.resolve("bin-rebuild")
    Files.createDirectories(binDir)
    Path libDir = homeDir.resolve(".local").resolve("lib")
    Files.createDirectories(libDir)
    Files.writeString(libDir.resolve("local-coding-assistant-1.0.0-exec.jar"), "jar")
    Path ollamaLog = tempDir.resolve("rebuild-ollama.log")
    Path javaLog = tempDir.resolve("rebuild-java.log")
    Path ollamaState = tempDir.resolve("rebuild-ollama-state.txt")
    Files.writeString(ollamaState, "qwen3.8:27b\tnew-llamacpp-id\nqwen3.8-192k:latest\tstale-custom-id\n")
    Path modelStateDir = homeDir.resolve(".lca").resolve("model_state")
    Files.createDirectories(modelStateDir)
    Files.writeString(modelStateDir.resolve("qwen3.8-192k.id"), "old-mlx-id")
    writeStubOllama(binDir, ollamaState)
    writeStubJava(binDir)

    when:
    def result = runScript(
      scriptPath,
      [],
      [
        HOME: homeDir.toString(),
        PATH: binDir.toString() + File.pathSeparator + System.getenv("PATH"),
        LCA_OLLAMA_LOG: ollamaLog.toString(),
        LCA_JAVA_LOG: javaLog.toString()
      ]
    )

    then:
    result.exitCode == 0
    result.output.contains("Rebuilding qwen3.8-192k")
    !result.output.contains("qwen3.8-192k custom model already exists")
    Files.readString(ollamaLog).contains("create qwen3.8-192k")
  }

  def "run rebuilds a custom model when only its context or extra params changed, base model id unchanged"() {
    given:
    // Reproduces a real regression: bumping REVIEW_CONTEXT/QWEN_EXTRA_PARAMS in lca without
    // the underlying base model itself changing must still trigger a rebuild - a signature
    // that only tracks the base model's id would say "up to date" and silently keep serving
    // the stale Modelfile (missing the new num_batch/num_gpu tuning).
    Path scriptPath = projectRoot().resolve("src/main/bin/lca")
    Path homeDir = tempDir.resolve("home-params-changed")
    Path binDir = tempDir.resolve("bin-params-changed")
    Files.createDirectories(binDir)
    Path libDir = homeDir.resolve(".local").resolve("lib")
    Files.createDirectories(libDir)
    Files.writeString(libDir.resolve("local-coding-assistant-1.0.0-exec.jar"), "jar")
    Path ollamaLog = tempDir.resolve("params-ollama.log")
    Path javaLog = tempDir.resolve("params-java.log")
    Path ollamaState = tempDir.resolve("params-ollama-state.txt")
    Files.writeString(ollamaState, "qwen3.8:27b\tsame-base-id\nqwen3.8-review:latest\texisting-custom-id\n")
    Path modelStateDir = homeDir.resolve(".lca").resolve("model_state")
    Files.createDirectories(modelStateDir)
    // Same base id as lca will report now, but recorded against the pre-tuning signature
    // (empty extra params) - i.e. the base model itself never changed.
    Files.writeString(modelStateDir.resolve("qwen3.8-review.id"), "same-base-id|131072|")
    writeStubOllama(binDir, ollamaState)
    writeStubJava(binDir)

    when:
    def result = runScript(
      scriptPath,
      [],
      [
        HOME: homeDir.toString(),
        PATH: binDir.toString() + File.pathSeparator + System.getenv("PATH"),
        LCA_OLLAMA_LOG: ollamaLog.toString(),
        LCA_JAVA_LOG: javaLog.toString()
      ]
    )

    then:
    result.exitCode == 0
    result.output.contains("Rebuilding qwen3.8-review")
    !result.output.contains("qwen3.8-review is up to date")
    modelfileSectionFor(Files.readString(ollamaLog), "qwen3.8-review").contains("PARAMETER num_gpu 99")
  }

  def "models.sh derives its model list from lca and installs the same models"() {
    given:
    Path scriptPath = projectRoot().resolve("models.sh")
    Path homeDir = tempDir.resolve("home-models-sh")
    Path binDir = tempDir.resolve("bin-models-sh")
    Files.createDirectories(binDir)
    Path ollamaLog = tempDir.resolve("models-sh-ollama.log")
    Path ollamaState = tempDir.resolve("models-sh-ollama-state.txt")
    Files.writeString(ollamaState, "other-model:1.0\tid-seed\n")
    writeStubOllama(binDir, ollamaState)

    when:
    def result = runScript(
      scriptPath,
      [],
      [
        HOME: homeDir.toString(),
        PATH: binDir.toString() + File.pathSeparator + System.getenv("PATH"),
        LCA_OLLAMA_LOG: ollamaLog.toString()
      ]
    )

    then:
    result.exitCode == 0
    def log = Files.readString(ollamaLog)
    log.contains("pull qwen3.8:27b")
    log.contains("pull gpt-oss:20b")
    log.contains("pull nomic-embed-text:latest")
    log.contains("create qwen3.8-192k")
    log.contains("create gpt-oss-64k")
    log.contains("create qwen3.8-review")
    modelfileSectionFor(log, "qwen3.8-192k").contains("PARAMETER num_batch 2048")
    modelfileSectionFor(log, "qwen3.8-192k").contains("PARAMETER num_gpu 99")
    modelfileSectionFor(log, "qwen3.8-review").contains("PARAMETER num_batch 2048")
    modelfileSectionFor(log, "qwen3.8-review").contains("PARAMETER num_gpu 99")
    !modelfileSectionFor(log, "gpt-oss-64k").contains("PARAMETER num_batch")
    !modelfileSectionFor(log, "gpt-oss-64k").contains("PARAMETER num_gpu")
  }

  def "models.sh rebuilds a custom model when only its context or extra params changed, base model id unchanged"() {
    given:
    // Mirrors lca's own regression test: models.sh's createCustomModel must fingerprint the
    // full desired Modelfile recipe (base id + context + extra params), not just whether a
    // model with this name already exists - the exact gap that let a stale custom model keep
    // serving silently after a context/parameter-only edit.
    Path scriptPath = projectRoot().resolve("models.sh")
    Path homeDir = tempDir.resolve("home-models-sh-rebuild")
    Path binDir = tempDir.resolve("bin-models-sh-rebuild")
    Files.createDirectories(binDir)
    Path ollamaLog = tempDir.resolve("models-sh-rebuild-ollama.log")
    Path ollamaState = tempDir.resolve("models-sh-rebuild-ollama-state.txt")
    Files.writeString(ollamaState, "qwen3.8:27b\tsame-base-id\nqwen3.8-review:latest\texisting-custom-id\n")
    Path modelStateDir = homeDir.resolve(".lca").resolve("model_state")
    Files.createDirectories(modelStateDir)
    // Same base id the stub will report now, but recorded against the pre-tuning signature
    // (empty extra params) - i.e. the base model itself never changed.
    Files.writeString(modelStateDir.resolve("qwen3.8-review.id"), "same-base-id|131072|")
    writeStubOllama(binDir, ollamaState)

    when:
    def result = runScript(
      scriptPath,
      [],
      [
        HOME: homeDir.toString(),
        PATH: binDir.toString() + File.pathSeparator + System.getenv("PATH"),
        LCA_OLLAMA_LOG: ollamaLog.toString()
      ]
    )

    then:
    result.exitCode == 0
    result.output.contains("Rebuilding qwen3.8-review")
    !result.output.contains("qwen3.8-review is up to date")
    modelfileSectionFor(Files.readString(ollamaLog), "qwen3.8-review").contains("PARAMETER num_gpu 99")
  }

  private static String modelfileSectionFor(String log, String modelName) {
    String startMarker = "--- modelfile:${modelName} ---"
    String endMarker = "--- end modelfile:${modelName} ---"
    int start = log.indexOf(startMarker)
    if (start < 0) {
      return ""
    }
    int end = log.indexOf(endMarker, start)
    end < 0 ? log.substring(start) : log.substring(start, end)
  }

  private static Path projectRoot() {
    Paths.get("").toAbsolutePath().normalize()
  }

  private static List<String> scriptNames() {
    ["lca"]
  }

  private static void assumeScriptAvailable(String scriptName) {
    // POSIX sh script works with any shell
  }

  private static boolean hasExecutable(String name) {
    String path = System.getenv("PATH")
    if (path == null || path.trim().isEmpty()) {
      return false
    }
    for (String entry : path.split(Pattern.quote(File.pathSeparator))) {
      if (entry == null || entry.trim().isEmpty()) {
        continue
      }
      File candidate = new File(entry, name)
      if (candidate.exists() && candidate.canExecute()) {
        return true
      }
    }
    false
  }

  private static void writeStubCurl(Path binDir, String releaseJson) {
    Path releaseFile = binDir.resolve("release.json")
    Files.writeString(releaseFile, releaseJson)
    Path curlPath = binDir.resolve("curl")
    Files.writeString(
      curlPath,
      """#!/usr/bin/env bash
set -euo pipefail

output=""
while [ "\$#" -gt 0 ]; do
  if [ "\$1" = "-o" ]; then
    shift
    output="\$1"
  fi
  shift
done

if [ -n "\$output" ]; then
  mkdir -p "\$(dirname "\$output")"
  printf '%s' "\${LCA_FAKE_JAR_CONTENT:-jar}" > "\$output"
  exit 0
fi

if [ -n "\${LCA_FAKE_RELEASE_FILE:-}" ]; then
  cat "\$LCA_FAKE_RELEASE_FILE"
  exit 0
fi

exit 1
"""
    )
    curlPath.toFile().setExecutable(true)
  }

  /**
   * A stateful ollama stub: {@code list} reflects a backing state file that {@code pull} and
   * {@code create} append to (each with a synthetic, resolvable model id) and {@code rm} removes
   * from - so {@code get_model_id} in lca resolves realistically after a pull/create, letting
   * tests exercise rebuild_custom_model_if_changed's id-comparison logic, not just existence.
   * Pre-seed {@code stateFile} (tab-separated "name\tid" lines) to simulate models that are
   * already present before the script runs.
   */
  private static void writeStubOllama(Path binDir, Path stateFile) {
    if (!Files.exists(stateFile)) {
      Files.writeString(stateFile, "")
    }
    Path ollamaPath = binDir.resolve("ollama")
    Files.writeString(
      ollamaPath,
      """#!/usr/bin/env bash
set -euo pipefail

STATE_FILE="${stateFile}"

command="\${1:-}"
case "\$command" in
  list)
    if [ -n "\${LCA_OLLAMA_LOG:-}" ]; then
      echo "list" >> "\$LCA_OLLAMA_LOG"
    fi
    cat "\$STATE_FILE" 2>/dev/null || true
    ;;
  pull)
    model="\${2:-}"
    if [ -n "\${LCA_OLLAMA_LOG:-}" ]; then
      echo "pull \$model" >> "\$LCA_OLLAMA_LOG"
    fi
    printf '%s\\tid-%s-%s\\n' "\$model" "\$\$" "\$RANDOM" >> "\$STATE_FILE"
    ;;
  create)
    name="\${2:-}"
    modelfile_path="\${4:-}"
    if [ -n "\${LCA_OLLAMA_LOG:-}" ]; then
      echo "create \$*" >> "\$LCA_OLLAMA_LOG"
      if [ -n "\$modelfile_path" ] && [ -f "\$modelfile_path" ]; then
        echo "--- modelfile:\$name ---" >> "\$LCA_OLLAMA_LOG"
        cat "\$modelfile_path" >> "\$LCA_OLLAMA_LOG"
        echo "--- end modelfile:\$name ---" >> "\$LCA_OLLAMA_LOG"
      fi
    fi
    printf '%s:latest\\tid-%s-%s\\n' "\$name" "\$\$" "\$RANDOM" >> "\$STATE_FILE"
    ;;
  rm)
    name="\${2:-}"
    if [ -n "\${LCA_OLLAMA_LOG:-}" ]; then
      echo "rm \$name" >> "\$LCA_OLLAMA_LOG"
    fi
    grep -v "^\${name}[[:space:]]" "\$STATE_FILE" > "\${STATE_FILE}.tmp" 2>/dev/null || true
    mv "\${STATE_FILE}.tmp" "\$STATE_FILE" 2>/dev/null || true
    ;;
  *)
    if [ -n "\${LCA_OLLAMA_LOG:-}" ]; then
      echo "\$command \$*" >> "\$LCA_OLLAMA_LOG"
    fi
    ;;
esac

exit 0
"""
    )
    ollamaPath.toFile().setExecutable(true)
  }

  private static void writeStubJava(Path binDir) {
    Path javaPath = binDir.resolve("java")
    Files.writeString(
      javaPath,
      """#!/usr/bin/env bash
set -euo pipefail

if [ -n "\${LCA_JAVA_LOG:-}" ]; then
  echo "\$*" >> "\$LCA_JAVA_LOG"
  env | grep '^LCA_[A-Z_]*=' >> "\$LCA_JAVA_LOG" || true
fi

exit 0
"""
    )
    javaPath.toFile().setExecutable(true)
  }

  private static String releaseJsonFor(String version) {
    String url = "https://github.com/Alipsa/local-coding-assistant/releases/download/v${version}/" +
      "local-coding-assistant-${version}-exec.jar"
    """{
  "tag_name": "v${version}",
  "assets": [
    {
      "browser_download_url": "${url}"
    }
  ]
}
"""
  }

  private static Map<String, Object> runScript(Path scriptPath, List<String> args, Map<String, String> env) {
    List<String> command = [scriptPath.toString()] + args
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(projectRoot().toFile())
    processBuilder.redirectErrorStream(true)
    processBuilder.environment().putAll(env)
    Process process = processBuilder.start()
    String output = process.inputStream.text
    int exitCode = process.waitFor()
    [exitCode: exitCode, output: output]
  }
}
