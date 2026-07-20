package se.alipsa.lca.memory

import spock.lang.Specification

import java.time.Instant

class RecalledMemoryFormatterSpec extends Specification {

  private static MemoryEntry entry(String id, String content, String createdAt) {
    Instant created = Instant.parse(createdAt)
    new MemoryEntry(id, content, created, created, null, "proj-1")
  }

  def "returns empty string for empty input"() {
    expect:
    RecalledMemoryFormatter.render([], 2000) == ""
  }

  def "renders a single memory as [date, similarity score] content"() {
    given:
    def recalled = [new RecalledMemory(entry("id-1", "the sky is blue", "2026-03-01T00:00:00Z"), 0.8234d)]

    expect:
    RecalledMemoryFormatter.render(recalled, 2000) == "[2026-03-01, similarity 0.82] the sky is blue"
  }

  def "joins multiple memories with newlines in the given order"() {
    given:
    def recalled = [
      new RecalledMemory(entry("id-1", "first fact", "2026-03-01T00:00:00Z"), 0.9d),
      new RecalledMemory(entry("id-2", "second fact", "2026-05-14T00:00:00Z"), 0.7d),
    ]

    expect:
    RecalledMemoryFormatter.render(recalled, 2000) ==
      "[2026-03-01, similarity 0.90] first fact\n[2026-05-14, similarity 0.70] second fact"
  }

  def "stops before exceeding maxChars rather than truncating mid-line"() {
    given:
    def first = new RecalledMemory(entry("id-1", "a" * 20, "2026-03-01T00:00:00Z"), 0.9d)
    def second = new RecalledMemory(entry("id-2", "b" * 20, "2026-03-02T00:00:00Z"), 0.8d)
    String firstLine = "[2026-03-01, similarity 0.90] ${"a" * 20}"

    when:
    String rendered = RecalledMemoryFormatter.render([first, second], firstLine.length())

    then:
    rendered == firstLine
  }

  def "returns empty string when even the first line exceeds maxChars"() {
    given:
    def recalled = [new RecalledMemory(entry("id-1", "a" * 50, "2026-03-01T00:00:00Z"), 0.9d)]

    expect:
    RecalledMemoryFormatter.render(recalled, 5) == ""
  }
}
