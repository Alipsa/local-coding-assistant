package se.alipsa.lca.memory

import groovy.transform.CompileStatic
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "lca.memory")
@CompileStatic
class MemorySettings {

  /**
   * Whether long-term memory recall/remember is enabled at all.
   */
  boolean enabled = true

  /**
   * Embedding model used to vectorise memory content and recall queries.
   * Kept in sync with application.properties' lca.memory.embedding-model chain
   * (MemorySettingsSpec asserts this default independently of Spring binding).
   */
  String embeddingModel = "nomic-embed-text:latest"

  /**
   * Local directory backing the memory index (created if missing).
   */
  String indexDirectory = "${System.getProperty('user.home')}/.lca/memory-index"

  /**
   * Maximum number of memories returned by a single recall() call.
   */
  int recallTopK = 5

  /**
   * Minimum similarity score (0.0-1.0) for a recalled memory to be surfaced.
   */
  double recallMinScore = 0.6

  /**
   * A memory is eligible for forgetting once it is at least this many days old
   * AND has not been accessed (recalled) for at least maxIdleDays. Both conditions apply.
   */
  int maxAgeDays = 180

  /**
   * See maxAgeDays - both thresholds must be exceeded for a memory to be forgotten.
   */
  int maxIdleDays = 30

  /**
   * Minimum minutes between lazy prune ("forget") sweeps, run opportunistically on writes.
   */
  int pruneMinIntervalMinutes = 60

  /**
   * Whether to run the "surprising learning" classification after each assistant turn
   * (heuristically gated - see SurprisingLearningDetector).
   */
  boolean surprisingLearningDetectionEnabled = true

  /**
   * Optional model override for surprising-learning classification; falls back to
   * assistant.llm.fallback-model when blank.
   */
  String surprisingLearningModel = ""

  /**
   * Caps so memory injection can't silently crowd out other prompt context.
   */
  int recallMaxContextChars = 2000
  int maxMemoryContentChars = 500

  /**
   * Project scoping: how much to over-fetch from the index before filtering to the
   * current project + global memories (the index has no native scope awareness).
   * Used by both recall() and remember()'s supersession check.
   */
  int recallOverFetchFactor = 4

  /**
   * Supersession on write: how similar a new memory must be to an existing one (in the
   * same scope) to replace it rather than sit alongside it. Deliberately stricter than
   * recallMinScore - this must be near-identical topic, not merely "related".
   */
  double supersedeSimilarityThreshold = 0.85

  /**
   * Final, post-scope-filter count of candidates considered for supersession.
   */
  int supersedeCandidateLimit = 3

}
