package se.alipsa.lca.gui

import se.alipsa.lca.shell.BangCommandHandler
import se.alipsa.lca.shell.SessionState
import se.alipsa.lca.tools.FileEditingTool
import se.alipsa.lca.tools.GitTool
import se.alipsa.lca.tools.Workspace
import spock.lang.Specification
import spock.lang.Unroll
import spock.util.concurrent.PollingConditions

import javax.swing.JLabel

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

  def "a fresh populateBranches call supersedes the generation an earlier, still-running one captured"() {
    given:
    HeaderBar header = new HeaderBar(
      Mock(FileEditingTool), Mock(GitTool), Mock(SessionState), Mock(Workspace), Mock(BangCommandHandler), null, null)

    when: "an earlier open's worker captures its generation before a second open starts"
    int firstGeneration = header.beginPopulateGeneration()

    then:
    header.isCurrentPopulateGeneration(firstGeneration)

    when: "a second, later open starts before the first worker finishes"
    int secondGeneration = header.beginPopulateGeneration()

    then: "the first worker's captured generation is now stale and must not apply its result"
    !header.isCurrentPopulateGeneration(firstGeneration)
    header.isCurrentPopulateGeneration(secondGeneration)
  }

  def "populateBranches applies its result once its SwingWorker completes"() {
    given:
    GitTool gitTool = Mock(GitTool)
    gitTool.currentBranch() >> "main"
    gitTool.listLocalBranches() >> ["main", "feature-a"]
    gitTool.listRemoteBranches() >> []
    HeaderBar header = new HeaderBar(
      Mock(FileEditingTool), gitTool, Mock(SessionState), Mock(Workspace), Mock(BangCommandHandler), null, null)

    when:
    header.populateBranches()

    then:
    new PollingConditions(timeout: 2).eventually {
      header.currentBranchItems().containsAll(["main", "feature-a"])
    }
  }

  @Unroll
  def "pullRequestLabelFor('#pullRequests') returns '#expected'"() {
    expect:
    HeaderBar.pullRequestLabelFor(pullRequests) == expected

    where:
    pullRequests                                          | expected
    null                                                   | null
    []                                                     | null
    [[number: 7, title: "x", url: "y"]]                    | "PR #7"
    [[number: 7], [number: 9]]                             | "PRs #7, #9"
    [[title: "no number"]]                                 | null
  }

  def "a fresh refreshPullRequestStatus call supersedes the generation an earlier, still-running one captured"() {
    given:
    HeaderBar header = new HeaderBar(
      Mock(FileEditingTool), Mock(GitTool), Mock(SessionState), Mock(Workspace), Mock(BangCommandHandler), null, null)

    when: "an earlier check's worker captures its generation before a second check starts"
    int firstGeneration = header.beginPrCheckGeneration()

    then:
    header.isCurrentPrCheckGeneration(firstGeneration)

    when: "a second, later check starts before the first worker finishes"
    int secondGeneration = header.beginPrCheckGeneration()

    then: "the first worker's captured generation is now stale and must not apply its result"
    !header.isCurrentPrCheckGeneration(firstGeneration)
    header.isCurrentPrCheckGeneration(secondGeneration)
  }

  def "constructing HeaderBar shows the open PR number once the async check completes"() {
    given:
    GitTool gitTool = Mock(GitTool)
    gitTool.openPullRequestsForCurrentBranch() >> new GitTool.GitResult(
      true, true, 0, '[{"number":7,"title":"x","url":"y","headRefName":"b","state":"OPEN"}]', "")

    when:
    HeaderBar header = new HeaderBar(
      Mock(FileEditingTool), gitTool, Mock(SessionState), Mock(Workspace), Mock(BangCommandHandler), null, null)

    then:
    new PollingConditions(timeout: 2).eventually {
      header.currentPullRequestText() == "PR #7"
    }
  }

  def "refreshPullRequestStatus hides the PR label when the gh check fails"() {
    given:
    GitTool gitTool = Mock(GitTool)
    gitTool.openPullRequestsForCurrentBranch() >> new GitTool.GitResult(
      false, true, 1, "", "gh: command not found")

    when:
    HeaderBar header = new HeaderBar(
      Mock(FileEditingTool), gitTool, Mock(SessionState), Mock(Workspace), Mock(BangCommandHandler), null, null)
    header.refreshPullRequestStatus()

    then:
    new PollingConditions(timeout: 2).eventually {
      header.currentPullRequestText() == null
    }
  }

  def "refreshPullRequestStatus hides the PR label when there is no open PR"() {
    given:
    GitTool gitTool = Mock(GitTool)
    gitTool.openPullRequestsForCurrentBranch() >> new GitTool.GitResult(true, true, 0, "[]", "")

    when:
    HeaderBar header = new HeaderBar(
      Mock(FileEditingTool), gitTool, Mock(SessionState), Mock(Workspace), Mock(BangCommandHandler), null, null)
    header.refreshPullRequestStatus()

    then:
    new PollingConditions(timeout: 2).eventually {
      header.currentPullRequestText() == null
    }
  }

  def "the header bar no longer shows pipe separators between segments"() {
    given:
    HeaderBar header = new HeaderBar(
      Mock(FileEditingTool), Mock(GitTool), Mock(SessionState), Mock(Workspace), Mock(BangCommandHandler), null, null)

    expect:
    header.components.findAll { it instanceof JLabel }.every { !((JLabel) it).text?.contains("|") }
  }
}
