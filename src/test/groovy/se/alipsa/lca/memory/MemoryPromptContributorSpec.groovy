package se.alipsa.lca.memory

import spock.lang.Specification

import java.time.Instant

class MemoryPromptContributorSpec extends Specification {

  def "wraps RecalledMemoryFormatter output with an introductory header"() {
    given:
    def entry = new MemoryEntry("id-1", "the user prefers 2-space indentation", Instant.EPOCH, Instant.EPOCH, null, null)
    def recalled = [new RecalledMemory(entry, 0.77d)]
    MemoryPromptContributor contributor = new MemoryPromptContributor(recalled, 2000)

    expect:
    contributor.contribution() ==
      "Relevant memories from earlier conversations:\n${RecalledMemoryFormatter.render(recalled, 2000)}"
    contributor.contribution().contains("the user prefers 2-space indentation")
  }

  def "still renders a header even when recalled memories are empty"() {
    given:
    MemoryPromptContributor contributor = new MemoryPromptContributor([], 2000)

    expect:
    contributor.contribution() == "Relevant memories from earlier conversations:\n"
  }

  def "honours maxContextChars the same way RecalledMemoryFormatter does"() {
    given:
    def entry1 = new MemoryEntry("id-1", "a" * 20, Instant.EPOCH, Instant.EPOCH, null, null)
    def entry2 = new MemoryEntry("id-2", "b" * 20, Instant.EPOCH, Instant.EPOCH, null, null)
    def recalled = [new RecalledMemory(entry1, 0.9d), new RecalledMemory(entry2, 0.8d)]
    MemoryPromptContributor generousContributor = new MemoryPromptContributor(recalled, 2000)
    MemoryPromptContributor tightContributor = new MemoryPromptContributor(recalled, 10)

    expect:
    generousContributor.contribution().contains(entry2.content)
    !tightContributor.contribution().contains(entry2.content)
  }
}
