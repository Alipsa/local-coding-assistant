package se.alipsa.lca.gui

import spock.lang.Specification
import spock.lang.Unroll

class HeaderBarSpec extends Specification {

  @Unroll
  def "accepts safe branch name '#name'"() {
    expect:
    HeaderBar.isSafeBranchName(name)

    where:
    name << ["main", "feature-a", "feature/new_thing", "release-1.3.0", "user/fix.bug", "v2"]
  }

  @Unroll
  def "rejects unsafe or injection-prone branch name '#name'"() {
    expect:
    !HeaderBar.isSafeBranchName(name)

    where:
    name << [
      null,
      "",
      "-rf",                 // leading dash looks like a git option
      "--upload-pack=evil",
      'x$(touch pwned)',     // command substitution inside double quotes
      'x`touch pwned`',      // backtick command substitution
      'a";rm -rf ~;"',       // quote break-out
      'a\\b',                // backslash
      'a;b',                 // command separator
      'a|b',
      'a&b',
      'a b',                 // space
      "a\nb"                 // newline
    ]
  }
}
