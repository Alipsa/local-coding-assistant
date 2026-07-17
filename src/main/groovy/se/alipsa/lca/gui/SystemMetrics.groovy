package se.alipsa.lca.gui

import com.sun.management.OperatingSystemMXBean
import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.springframework.stereotype.Component

import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Best-effort host memory metrics for the GUI footer.
 *
 * <p>Total RAM comes from the JVM's {@link com.sun.management.OperatingSystemMXBean}. For
 * <em>used</em> memory we deliberately do NOT use {@code getFreeMemorySize()}: on macOS and
 * Linux that reports only strictly-free pages, so it is almost always near zero (the OS keeps
 * inactive/cached/purgeable pages resident but reclaimable) and would make the gauge read
 * ~full at all times. Instead we compute <em>available</em> memory — free plus reclaimable —
 * from {@code vm_stat} (macOS) or {@code /proc/meminfo} {@code MemAvailable} (Linux), and
 * report {@code used = total - available}. Other platforms fall back to the MXBean.
 *
 * <p>GPU memory has no portable source (notably macOS unified memory) and is left to the
 * footer to render as {@code n/a}.
 */
@Component
@CompileStatic
class SystemMetrics {

  private static final double BYTES_PER_GB = 1024.0d * 1024.0d * 1024.0d
  private static final long AVAILABLE_CACHE_MILLIS = 1500L
  // vm_stat runs once and exits near-instantly; this is a safety bound against a hung/runaway
  // process, not a tuning knob.
  private static final long VM_STAT_TIMEOUT_MILLIS = 2000L

  private final OperatingSystemMXBean osBean =
    (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()

  private volatile long cachedAvailable = -1L
  private volatile long cachedAvailableAt = 0L

  long totalMemoryBytes() {
    Math.max(0L, osBean.getTotalMemorySize())
  }

  /** Reclaimable + free physical memory, i.e. what a new process could realistically use. */
  long availableMemoryBytes() {
    long now = System.currentTimeMillis()
    if (cachedAvailable >= 0L && (now - cachedAvailableAt) < AVAILABLE_CACHE_MILLIS) {
      return cachedAvailable
    }
    long value = computeAvailableMemoryBytes()
    cachedAvailable = value
    cachedAvailableAt = now
    value
  }

  long usedMemoryBytes() {
    Math.max(0L, totalMemoryBytes() - availableMemoryBytes())
  }

  int usedMemoryPercent() {
    long total = totalMemoryBytes()
    total <= 0L ? 0 : (int) Math.min(100L, Math.round((usedMemoryBytes() * 100.0d) / total))
  }

  static String formatGb(long bytes) {
    String.format(Locale.UK, "%.0f", bytes / BYTES_PER_GB)
  }

  String memorySummary() {
    "${formatGb(usedMemoryBytes())} / ${formatGb(totalMemoryBytes())} Gb"
  }

  private long computeAvailableMemoryBytes() {
    String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
    if (osName.contains("mac")) {
      Long viaVmStat = macAvailableViaVmStat()
      if (viaVmStat != null) {
        return viaVmStat
      }
    } else {
      Long viaProc = linuxAvailableViaProc()
      if (viaProc != null) {
        return viaProc
      }
    }
    Math.max(0L, osBean.getFreeMemorySize())
  }

  private static Long macAvailableViaVmStat() {
    try {
      parseAvailable(new ProcessBuilder("vm_stat").redirectErrorStream(true).start())
    } catch (Exception ignored) {
      null
    }
  }

  /**
   * Bounds the wait BEFORE reading any output: vm_stat's output is tiny (well under a pipe
   * buffer's worth), so it can't itself block on a full pipe waiting for us to drain it — meaning
   * it's safe to wait first and only read once we know the process actually exited. Reading first
   * blocks on {@code readAllBytes()} until the process closes its stdout, which for a truly hung
   * process never happens, so the bounded {@link #awaitVmStat} below would never get a chance to
   * run.
   */
  @PackageScope
  static Long parseAvailable(Process process) {
    try {
      if (!awaitVmStat(process)) {
        return null
      }
      String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
      long pageSize = pageSizeFromVmStat(out)
      long pages = availablePagesFromVmStat(out)
      pages > 0L ? Long.valueOf(pages * pageSize) : null
    } catch (Exception e) {
      process.destroyForcibly()
      null
    }
  }

  /**
   * Bounds vm_stat's exit wait to {@link #VM_STAT_TIMEOUT_MILLIS} and force-kills it if that
   * elapses or the wait is interrupted. On interrupt, restores the flag rather than swallowing
   * it: this runs on a SwingWorker pool thread, so losing it would make the interrupt vanish for
   * whichever unrelated task the pooled thread picks up next.
   *
   * @return {@code true} if the process exited within the timeout; {@code false} if it had to be
   *         force-killed (timeout or interrupt), in which case its output must not be trusted/read.
   */
  @PackageScope
  static boolean awaitVmStat(Process process) {
    try {
      if (process.waitFor(VM_STAT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
        return true
      }
      process.destroyForcibly()
      return false
    } catch (InterruptedException e) {
      process.destroyForcibly()
      Thread.currentThread().interrupt()
      return false
    }
  }

  private static Long linuxAvailableViaProc() {
    Path meminfo = Paths.get("/proc/meminfo")
    if (!Files.exists(meminfo)) {
      return null
    }
    try {
      for (String line : Files.readAllLines(meminfo)) {
        if (line.startsWith("MemAvailable:")) {
          long kb = firstLong(line)
          return kb > 0L ? Long.valueOf(kb * 1024L) : null
        }
      }
    } catch (Exception ignored) {
      // fall through
    }
    null
  }

  /** Parse the page size (bytes) from {@code vm_stat} output; defaults to 4096 if absent. */
  static long pageSizeFromVmStat(String vmStatOutput) {
    if (vmStatOutput == null) {
      return 4096L
    }
    for (String line : vmStatOutput.readLines()) {
      if (line.contains("page size of")) {
        long size = firstLong(line)
        return size > 0L ? size : 4096L
      }
    }
    4096L
  }

  /** Sum the reclaimable page counts (free + inactive + speculative + purgeable). */
  static long availablePagesFromVmStat(String vmStatOutput) {
    if (vmStatOutput == null) {
      return 0L
    }
    long total = 0L
    total += pagesForLabel(vmStatOutput, "Pages free")
    total += pagesForLabel(vmStatOutput, "Pages inactive")
    total += pagesForLabel(vmStatOutput, "Pages speculative")
    total += pagesForLabel(vmStatOutput, "Pages purgeable")
    total
  }

  private static long pagesForLabel(String vmStatOutput, String label) {
    for (String line : vmStatOutput.readLines()) {
      if (line.trim().startsWith(label + ":")) {
        return firstLong(line)
      }
    }
    0L
  }

  private static final Pattern DIGITS = Pattern.compile("\\d+")

  /** The first run of digits in {@code text}, e.g. {@code 16384} from "page size of 16384 bytes". */
  private static long firstLong(String text) {
    if (text == null) {
      return 0L
    }
    Matcher matcher = DIGITS.matcher(text)
    matcher.find() ? Long.parseLong(matcher.group()) : 0L
  }
}
