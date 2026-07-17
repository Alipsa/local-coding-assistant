package se.alipsa.lca.gui

import com.sun.management.OperatingSystemMXBean
import groovy.transform.CompileStatic
import org.springframework.stereotype.Component

import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
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
      Process process = new ProcessBuilder("vm_stat").redirectErrorStream(true).start()
      String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
      process.waitFor()
      long pageSize = pageSizeFromVmStat(out)
      long pages = availablePagesFromVmStat(out)
      pages > 0L ? Long.valueOf(pages * pageSize) : null
    } catch (Exception ignored) {
      null
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
