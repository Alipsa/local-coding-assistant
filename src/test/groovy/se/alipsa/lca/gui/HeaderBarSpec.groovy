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

  def "checkoutCommandFor checks out a local branch directly"() {
    expect:
    HeaderBar.checkoutCommandFor("feature-a", true) == '! git checkout "feature-a"'
  }

  def "checkoutCommandFor creates a tracking branch from the fully-qualified remote ref"() {
    expect:
    // The remote qualifier is kept so the checkout is unambiguous with multiple remotes.
    HeaderBar.checkoutCommandFor("origin/feature-x", false) == '! git checkout --track "origin/feature-x"'
  }

  def "branchItemsFor lists the current branch first, then locals, then unique remotes"() {
    expect:
    HeaderBar.branchItemsFor("main", ["main", "feature-a"], ["origin/feature-a", "origin/feature-b"]) ==
      ["main", "feature-a", "origin/feature-b"]
  }

  def "branchItemsFor keeps a remote whose short name is not a local branch"() {
    expect:
    HeaderBar.branchItemsFor("main", ["main"], ["origin/main", "origin/topic"]) ==
      ["main", "origin/topic"]
  }

  def "branchItemsFor falls back to the current branch, or (none), when nothing else exists"() {
    expect:
    HeaderBar.branchItemsFor("detached", [], []) == ["detached"]
    HeaderBar.branchItemsFor(null, [], []) == ["(none)"]
  }

  def "branchItemsFor without a current branch lists locals in order"() {
    expect:
    HeaderBar.branchItemsFor(null, ["a", "b"], []) == ["a", "b"]
  }

  @Unroll
  def "checkoutCommandFor refuses the unsafe name '#name' (returns null)"() {
    expect:
    HeaderBar.checkoutCommandFor(name, isLocal) == null

    where:
    name               | isLocal
    'x$(touch pwned)'  | true
    'x`touch pwned`'   | false
    '--upload-pack=e'  | true
    'a;b'              | false
  }
}
