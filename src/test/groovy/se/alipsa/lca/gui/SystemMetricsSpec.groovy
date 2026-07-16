package se.alipsa.lca.gui

import spock.lang.Specification

class SystemMetricsSpec extends Specification {

  SystemMetrics metrics = new SystemMetrics()

  def "reports positive total physical memory"() {
    expect:
    metrics.totalMemoryBytes() > 0
  }

  def "used memory is non-negative and within total"() {
    expect:
    metrics.usedMemoryBytes() >= 0
    metrics.usedMemoryBytes() <= metrics.totalMemoryBytes()
  }

  def "used percent is bounded to 0..100"() {
    when:
    int pct = metrics.usedMemoryPercent()

    then:
    pct >= 0
    pct <= 100
  }

  def "formatGb converts bytes to whole gigabytes"() {
    expect:
    SystemMetrics.formatGb(2L * 1024 * 1024 * 1024) == "2"
  }

  def "memorySummary has the used / total Gb shape"() {
    expect:
    metrics.memorySummary() ==~ /\d+ \/ \d+ Gb/
  }

  def "parses page size and reclaimable pages from vm_stat output"() {
    given:
    String vmStat = '''Mach Virtual Memory Statistics: (page size of 16384 bytes)
Pages free:                                     4555.
Pages active:                                2289892.
Pages inactive:                              1087139.
Pages speculative:                              7123.
Pages wired down:                             170966.
Pages purgeable:                               13394.
File-backed pages:                           1050019.
'''

    expect:
    SystemMetrics.pageSizeFromVmStat(vmStat) == 16384L
    // free + inactive + speculative + purgeable (active/wired/file-backed excluded)
    SystemMetrics.availablePagesFromVmStat(vmStat) == (4555L + 1087139L + 7123L + 13394L)
  }

  def "pageSizeFromVmStat defaults to 4096 when the page size is absent"() {
    expect:
    SystemMetrics.pageSizeFromVmStat("no page-size line here") == 4096L
    SystemMetrics.pageSizeFromVmStat(null) == 4096L
  }
}
