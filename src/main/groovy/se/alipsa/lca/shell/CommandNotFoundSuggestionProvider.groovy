package se.alipsa.lca.shell

import groovy.transform.CompileStatic
import org.springframework.context.annotation.Primary
import org.springframework.shell.result.CommandNotFoundMessageProvider
import org.springframework.stereotype.Component

import java.util.Locale

@Component
@Primary
@CompileStatic
class CommandNotFoundSuggestionProvider implements CommandNotFoundMessageProvider {

  @Override
  String apply(CommandNotFoundMessageProvider.ProviderContext context) {
    String command = extractCommand(context?.text())
    if (command == null) {
      return "Command not found."
    }
    String message = "The command ${command} does not exist."
    String suggestion = suggest(command, context?.registrations()?.keySet())
    if (suggestion != null) {
      return "${message} Did you mean ${suggestion}?"
    }
    message
  }

  private static String extractCommand(String text) {
    if (text == null) {
      return null
    }
    String trimmed = text.trim()
    if (!trimmed) {
      return null
    }
    int spaceIndex = trimmed.indexOf(' ')
    spaceIndex == -1 ? trimmed : trimmed.substring(0, spaceIndex)
  }

  private static String suggest(String input, Collection<String> candidates) {
    if (!input || candidates == null || candidates.isEmpty()) {
      return null
    }
    List<String> pool = new ArrayList<>(candidates)
    if (input.startsWith("/")) {
      pool = pool.findAll { String candidate -> candidate != null && candidate.startsWith("/") }
    }
    if (pool.isEmpty()) {
      return null
    }
    String normalisedInput = normalise(input)
    String best = null
    int bestDistance = Integer.MAX_VALUE
    for (String candidate : pool) {
      if (candidate == null) {
        continue
      }
      String normalisedCandidate = normalise(candidate)
      int distance = levenshtein(normalisedInput, normalisedCandidate)
      if (distance < bestDistance) {
        bestDistance = distance
        best = candidate
      }
    }
    if (best == null) {
      return null
    }
    int maxLen = Math.max(normalisedInput.length(), normalise(best).length())
    if (maxLen == 0) {
      return null
    }
    int threshold = Math.max(2, (int) Math.ceil(maxLen / 3.0d))
    if (bestDistance > threshold) {
      return null
    }
    best
  }

  private static String normalise(String value) {
    value == null ? "" : value.toLowerCase(Locale.ROOT)
  }

  private static int levenshtein(String left, String right) {
    if (left == null || right == null) {
      return Integer.MAX_VALUE
    }
    int leftLength = left.length()
    int rightLength = right.length()
    if (leftLength == 0) {
      return rightLength
    }
    if (rightLength == 0) {
      return leftLength
    }
    int[] costs = new int[rightLength + 1]
    for (int j = 0; j <= rightLength; j++) {
      costs[j] = j
    }
    for (int i = 1; i <= leftLength; i++) {
      int previousCost = costs[0]
      costs[0] = i
      for (int j = 1; j <= rightLength; j++) {
        int currentCost = costs[j]
        int substitutionCost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1
        costs[j] = Math.min(
          Math.min(costs[j] + 1, costs[j - 1] + 1),
          previousCost + substitutionCost
        )
        previousCost = currentCost
      }
    }
    costs[rightLength]
  }
}
