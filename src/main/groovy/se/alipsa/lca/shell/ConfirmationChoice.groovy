package se.alipsa.lca.shell

import groovy.transform.CompileStatic

/**
 * The user's answer to a destructive-action confirmation prompt.
 * {@code ALL} means "yes, and don't ask again for the remaining actions in this batch".
 */
@CompileStatic
enum ConfirmationChoice {
  YES,
  NO,
  ALL
}
