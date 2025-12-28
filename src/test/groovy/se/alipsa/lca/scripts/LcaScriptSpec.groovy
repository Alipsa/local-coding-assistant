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
    result.output.trim().isEmpty()

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
    writeStubOllama(binDir)
    writeStubJava(binDir)

    when:
    def result = runScript(
      scriptPath,
      [],
      [
        HOME: homeDir.toString(),
        PATH: binDir.toString() + File.pathSeparator + System.getenv("PATH"),
        LCA_OLLAMA_LIST: "other-model:1.0",
        LCA_OLLAMA_LOG: ollamaLog.toString(),
        LCA_JAVA_LOG: javaLog.toString()
      ]
    )

    then:
    result.exitCode == 0
    def log = Files.readString(ollamaLog)
    log.contains("pull qwen3-coder:30b")
    log.contains("pull gpt-oss:20b")
    Files.exists(javaLog)

    where:
    scriptName << scriptNames()
  }

  private static Path projectRoot() {
    Paths.get("").toAbsolutePath().normalize()
  }

  private static List<String> scriptNames() {
    ["lca", "lca.zsh"]
  }

  private static void assumeScriptAvailable(String scriptName) {
    if (scriptName.endsWith(".zsh")) {
      assumeTrue(zshAvailable(), "zsh is not available on PATH")
    }
  }

  private static boolean zshAvailable() {
    hasExecutable("zsh")
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

  private static void writeStubOllama(Path binDir) {
    Path ollamaPath = binDir.resolve("ollama")
    Files.writeString(
      ollamaPath,
      """#!/usr/bin/env bash
set -euo pipefail

command="\${1:-}"
case "\$command" in
  list)
    if [ -n "\${LCA_OLLAMA_LOG:-}" ]; then
      echo "list" >> "\$LCA_OLLAMA_LOG"
    fi
    printf '%s\n' "\${LCA_OLLAMA_LIST:-}"
    ;;
  pull)
    if [ -n "\${LCA_OLLAMA_LOG:-}" ]; then
      echo "pull \${2:-}" >> "\$LCA_OLLAMA_LOG"
    fi
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
