package se.alipsa.lca.validation

import groovy.transform.CompileStatic

/**
 * Result of validating an implementation request
 */
@CompileStatic
class ValidationResult {

  final boolean needsClarification
  final String reason
  final List<String> questions
  final String detectedType

  ValidationResult(boolean needsClarification, String reason, List<String> questions, String detectedType = null) {
    this.needsClarification = needsClarification
    this.reason = reason
    this.questions = questions ?: []
    this.detectedType = detectedType
  }

  static ValidationResult ok() {
    return new ValidationResult(false, null, [])
  }

  static ValidationResult needsClarification(String reason, List<String> questions, String detectedType = null) {
    return new ValidationResult(true, reason, questions, detectedType)
  }

}
