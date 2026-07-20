package se.alipsa.lca.memory

import com.embabel.common.ai.prompt.PromptContributor
import groovy.transform.CompileStatic

/**
 * Automatic pre-turn injection of recalled memories into the prompt. Delegates rendering and
 * truncation to RecalledMemoryFormatter so the recallMaxContextChars cap applies identically
 * here and in the explicit recallMemory() tool - memory injection can't silently crowd out
 * other prompt context via one path but not the other.
 */
@CompileStatic
class MemoryPromptContributor implements PromptContributor {

  private final List<RecalledMemory> recalled
  private final int maxContextChars

  MemoryPromptContributor(List<RecalledMemory> recalled, int maxContextChars) {
    this.recalled = recalled
    this.maxContextChars = maxContextChars
  }

  @Override
  String contribution() {
    String rendered = RecalledMemoryFormatter.render(recalled, maxContextChars)
    "Relevant memories from earlier conversations:\n${rendered}"
  }
}
