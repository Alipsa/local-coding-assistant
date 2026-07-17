package se.alipsa.lca.gui

import spock.lang.Specification

import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

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

  def "a line with more than one number still parses the first one, not a concatenation of both"() {
    given:
    // e.g. a hypothetical vm_stat variant reporting a build/version number alongside the page size.
    String vmStat = "Mach Virtual Memory Statistics: (page size of 4096 bytes, v2 build 12345)"

    expect:
    SystemMetrics.pageSizeFromVmStat(vmStat) == 4096L
  }

  def "awaitVmStat force-kills and reports timeout for a process that doesn't exit within the timeout"() {
    given:
    FakeProcess process = new FakeProcess(false, null)

    when:
    boolean exited = SystemMetrics.awaitVmStat(process)

    then:
    !exited
    process.destroyForciblyCalled
  }

  def "awaitVmStat force-kills, reports timeout, and restores the interrupt flag when the wait itself is interrupted"() {
    given:
    FakeProcess process = new FakeProcess(false, new InterruptedException("simulated"))

    when:
    boolean exited = SystemMetrics.awaitVmStat(process)

    then:
    !exited
    process.destroyForciblyCalled
    Thread.currentThread().isInterrupted()

    cleanup:
    // Don't let the restored flag leak into whichever test runs next on this thread.
    Thread.interrupted()
  }

  def "awaitVmStat reports success and leaves a normally-exiting process alone"() {
    given:
    FakeProcess process = new FakeProcess(true, null)

    when:
    boolean exited = SystemMetrics.awaitVmStat(process)

    then:
    exited
    !process.destroyForciblyCalled
  }

  def "parseAvailable never reads output from a process that didn't exit within the timeout"() {
    given:
    // Regression guard for the exact bug this round's review caught: output used to be read
    // BEFORE the bounded wait, so a process that hangs without closing stdout blocked forever on
    // that read and the timeout never got a chance to fire. A stream that fails the test if
    // touched proves parseAvailable now checks awaitVmStat's result first.
    FakeProcess process = new FakeProcess(false, null) {
      @Override
      InputStream getInputStream() {
        throw new AssertionError("output must not be read when the process didn't exit in time")
      }
    }

    expect:
    SystemMetrics.parseAvailable(process) == null
  }

  def "parseAvailable reads and parses output once the process has exited"() {
    given:
    String vmStat = '''Mach Virtual Memory Statistics: (page size of 4096 bytes)
Pages free:                                     100.
Pages inactive:                                  50.
Pages speculative:                                0.
Pages purgeable:                                  0.
'''
    FakeProcess process = new FakeProcess(true, null) {
      @Override
      InputStream getInputStream() {
        new ByteArrayInputStream(vmStat.getBytes(StandardCharsets.UTF_8))
      }
    }

    expect:
    // (100 + 50) pages * 4096 bytes/page
    SystemMetrics.parseAvailable(process) == 150L * 4096L
  }

  private static class FakeProcess extends Process {
    private final boolean exitsInTime
    private final Exception waitForException
    boolean destroyForciblyCalled = false

    FakeProcess(boolean exitsInTime, Exception waitForException) {
      this.exitsInTime = exitsInTime
      this.waitForException = waitForException
    }

    @Override
    OutputStream getOutputStream() {
      OutputStream.nullOutputStream()
    }

    @Override
    InputStream getInputStream() {
      InputStream.nullInputStream()
    }

    @Override
    InputStream getErrorStream() {
      InputStream.nullInputStream()
    }

    @Override
    int waitFor() throws InterruptedException {
      throw new UnsupportedOperationException("not used by awaitVmStat")
    }

    @Override
    boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
      if (waitForException instanceof InterruptedException) {
        throw (InterruptedException) waitForException
      }
      exitsInTime
    }

    @Override
    int exitValue() {
      throw new IllegalThreadStateException("still running")
    }

    @Override
    void destroy() {
    }

    @Override
    Process destroyForcibly() {
      destroyForciblyCalled = true
      this
    }
  }
}
