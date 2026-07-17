package se.alipsa.lca.gui

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import se.alipsa.lca.shell.BangCommandHandler
import se.alipsa.lca.shell.SessionState
import se.alipsa.lca.tools.FileEditingTool
import se.alipsa.lca.tools.GitTool
import se.alipsa.lca.tools.Workspace

import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingWorker
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import java.awt.Component
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

/**
 * The top status strip: base directory (+ change button), current git branch (a combo box that
 * switches branch), and the main / small model names. Values are pulled live from the same beans
 * the rest of the app uses. Switching branch runs {@code git checkout} via the shared shell
 * handler so the assistant is informed of the action.
 */
@CompileStatic
class HeaderBar extends JPanel {

  private static final Logger log = LoggerFactory.getLogger(HeaderBar)
  private static final String NO_BRANCH = "(none)"

  /**
   * Branch names shown in the combo box come from repository data (including remotes that may be
   * untrusted), and git permits characters such as {@code $}, backtick and {@code "} in ref names.
   * Because the switch is issued as a shell string ({@code ! git checkout "<name>"}), such a name
   * could inject a command (e.g. command substitution inside the double quotes). Only allow a
   * conservative ref shape and reject a leading {@code -} (which would look like a git option).
   */
  private static final java.util.regex.Pattern SAFE_BRANCH = ~/^[A-Za-z0-9._\/-]+$/

  private final FileEditingTool fileEditingTool
  private final GitTool gitTool
  private final SessionState sessionState
  private final Workspace workspace
  private final BangCommandHandler bangCommandHandler
  private final Runnable onBaseDirChanged
  private final Consumer<String> onCommandOutput

  private final JLabel baseDirLabel = new JLabel()
  private final JComboBox<String> branchCombo = new JComboBox<>()
  private final JLabel pullRequestLabel = new JLabel()
  private final JLabel mainModelLabel = new JLabel()
  private final JLabel smallModelLabel = new JLabel()

  private static final int SEGMENT_GAP = 12

  private final Set<String> localBranches = new LinkedHashSet<>()
  private boolean populating = false
  // Guards against a slower popup-open's worker overwriting a faster, more recent one's result.
  private final AtomicInteger populateGeneration = new AtomicInteger(0)
  // Separate from populateGeneration: the PR check is triggered by refresh(), not popup-open, so
  // a popup-open must not be able to invalidate an unrelated in-flight PR check (and vice versa).
  private final AtomicInteger prCheckGeneration = new AtomicInteger(0)

  HeaderBar(FileEditingTool fileEditingTool, GitTool gitTool, SessionState sessionState,
            Workspace workspace, BangCommandHandler bangCommandHandler,
            Runnable onBaseDirChanged, Consumer<String> onCommandOutput) {
    this.fileEditingTool = fileEditingTool
    this.gitTool = gitTool
    this.sessionState = sessionState
    this.workspace = workspace
    this.bangCommandHandler = bangCommandHandler
    this.onBaseDirChanged = onBaseDirChanged
    this.onCommandOutput = onCommandOutput
    setLayout(new BoxLayout(this, BoxLayout.X_AXIS))
    setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8))
    configureBranchCombo()
    pullRequestLabel.setVisible(false)
    add(baseDirLabel)
    add(Box.createHorizontalStrut(SEGMENT_GAP))
    add(changeDirButton())
    add(spacer())
    add(new JLabel("branch: "))
    add(branchCombo)
    add(Box.createHorizontalStrut(SEGMENT_GAP))
    add(pullRequestLabel)
    add(spacer())
    add(mainModelLabel)
    add(spacer())
    add(smallModelLabel)
    refresh()
  }

  private JButton changeDirButton() {
    JButton button = new JButton("…")
    button.setToolTipText("Change base dir")
    button.setMargin(new java.awt.Insets(0, 4, 0, 4))
    button.addActionListener({ ActionEvent e -> chooseBaseDir() } as ActionListener)
    button
  }

  private void configureBranchCombo() {
    branchCombo.setToolTipText("Current branch — open to switch (local and remote branches)")
    branchCombo.setMaximumSize(new Dimension(260, 28))
    branchCombo.addPopupMenuListener(new PopupMenuListener() {
      @Override
      void popupMenuWillBecomeVisible(PopupMenuEvent e) {
        populateBranches()
      }

      @Override
      void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}

      @Override
      void popupMenuCanceled(PopupMenuEvent e) {}
    })
    branchCombo.addActionListener({ ActionEvent e ->
      if (!populating) {
        Object selected = branchCombo.getSelectedItem()
        if (selected != null) {
          switchBranch(selected.toString())
        }
      }
    } as ActionListener)
  }

  private void chooseBaseDir() {
    if (workspace == null) {
      return
    }
    JFileChooser chooser = new JFileChooser(workspace.baseDir.toFile())
    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY)
    chooser.setDialogTitle("Select base directory")
    if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
      File dir = chooser.getSelectedFile()
      if (dir != null) {
        Workspace.ChangeResult result = workspace.changeBaseDir(dir.absolutePath)
        if (!result.success) {
          // Do not signal success: the tools still point at the previous directory.
          if (onCommandOutput != null) {
            onCommandOutput.accept("Could not change base dir: ${result.message}".toString())
          }
          return
        }
        refresh()
        if (onBaseDirChanged != null) {
          onBaseDirChanged.run()
        }
      }
    }
  }

  @PackageScope
  void populateBranches() {
    // Listing branches shells out to git; run it off the EDT so opening the combo never blocks
    // the UI, then apply the result back on the EDT in done(). Each open gets its own generation
    // so a slower worker from an earlier open can detect it's been superseded and skip applying.
    int generation = beginPopulateGeneration()
    new SwingWorker<BranchData, Void>() {
      @Override
      protected BranchData doInBackground() {
        collectBranchData()
      }

      @Override
      protected void done() {
        if (!isCurrentPopulateGeneration(generation)) {
          return
        }
        try {
          applyBranchData(get())
        } catch (Exception e) {
          log.warn("Could not list branches: {}", e.message)
        }
      }
    }.execute()
  }

  /** Starts a new populate generation, superseding any still-running earlier one. */
  @PackageScope
  int beginPopulateGeneration() {
    populateGeneration.incrementAndGet()
  }

  /** Whether {@code generation} is still the most recently started populate call. */
  @PackageScope
  boolean isCurrentPopulateGeneration(int generation) {
    generation == populateGeneration.get()
  }

  /** The combo box's current items, in order — exposed read-only for tests. */
  @PackageScope
  List<String> currentBranchItems() {
    (0..<branchCombo.itemCount).collect { branchCombo.getItemAt(it) }
  }

  private BranchData collectBranchData() {
    String current = currentBranchOrNull()
    List<String> locals = gitTool != null ? gitTool.listLocalBranches() : List.<String> of()
    List<String> remotes = gitTool != null ? gitTool.listRemoteBranches() : List.<String> of()
    new BranchData(current, locals, remotes)
  }

  private void applyBranchData(BranchData data) {
    populating = true
    try {
      localBranches.clear()
      localBranches.addAll(data.locals)
      List<String> items = branchItemsFor(data.current, data.locals, data.remotes)
      branchCombo.setModel(new DefaultComboBoxModel<String>(items.toArray(new String[0])))
      branchCombo.setSelectedItem(data.current ?: items.get(0))
    } finally {
      populating = false
    }
  }

  /**
   * The ordered combo items for a set of branches: the current branch first (if any), then the
   * local branches, then remote branches whose short name is not already a local branch. Falls
   * back to the current branch or {@link #NO_BRANCH} when nothing else is available.
   */
  static List<String> branchItemsFor(String current, List<String> locals, List<String> remotes) {
    LinkedHashSet<String> items = new LinkedHashSet<>()
    if (current != null) {
      items.add(current)
    }
    items.addAll(locals)
    Set<String> localShortNames = new HashSet<>(locals)
    for (String remote : remotes) {
      if (!localShortNames.contains(stripRemote(remote))) {
        items.add(remote)
      }
    }
    if (items.isEmpty()) {
      items.add(current ?: NO_BRANCH)
    }
    new ArrayList<>(items)
  }

  private void switchBranch(String selected) {
    if (selected == null || selected == NO_BRANCH || bangCommandHandler == null) {
      return
    }
    boolean isLocal = localBranches.contains(selected)
    // Validate the exact ref handed to git: a remote selection keeps its "remote/" qualifier.
    String command = checkoutCommandFor(selected, isLocal)
    if (command == null) {
      if (onCommandOutput != null) {
        onCommandOutput.accept("Refusing to switch to branch with unsafe name: ${selected}".toString())
      }
      refresh()
      return
    }
    // Reading the current branch and running the checkout both shell out to git; do them off the
    // EDT and disable the combo meanwhile so the UI stays responsive and the switch is atomic.
    branchCombo.setEnabled(false)
    new SwingWorker<String, Void>() {
      @Override
      protected String doInBackground() {
        String current = currentBranchOrNull()
        String shortName = isLocal ? selected : stripRemote(selected)
        if (shortName == null || shortName.isEmpty() || shortName == current) {
          return null
        }
        bangCommandHandler.handle(command, "default", true)
      }

      @Override
      protected void done() {
        try {
          String output = get()
          if (onCommandOutput != null && output != null && !output.trim().isEmpty()) {
            onCommandOutput.accept(output)
          }
        } catch (Exception e) {
          log.warn("Branch switch failed: {}", e.message)
          if (onCommandOutput != null) {
            onCommandOutput.accept("Branch switch failed: ${e.message}".toString())
          }
        } finally {
          branchCombo.setEnabled(true)
          refresh()
        }
      }
    }.execute()
  }

  /**
   * The {@code ! git checkout ...} command for a branch selection, or {@code null} when the ref
   * fails the {@link #isSafeBranchName} allowlist. A local branch is checked out directly; a
   * remote-only branch creates a tracking branch from the fully-qualified ref, so the checkout is
   * unambiguous even when several remotes share the same short name.
   */
  static String checkoutCommandFor(String selected, boolean isLocal) {
    if (!isSafeBranchName(selected)) {
      return null
    }
    isLocal
      ? "! git checkout \"${selected}\"".toString()
      : "! git checkout --track \"${selected}\"".toString()
  }

  final void refresh() {
    baseDirLabel.text = "Base dir: ${baseDir()}"
    updateBranchSelection()
    mainModelLabel.text = "Main model: ${sessionState?.defaultModel ?: '-'}"
    smallModelLabel.text = "Small model: ${sessionState?.fallbackModel ?: '-'}"
    refreshPullRequestStatus()
  }

  /**
   * Checks for open PRs on the current branch off the EDT (shells out to {@code gh}) and shows
   * the result once done — hidden when there is none, the check fails, or this isn't a git repo.
   * Mirrors {@link #populateBranches}'s SwingWorker + generation-guard pattern, with its own
   * generation counter since this is triggered by {@link #refresh}, not popup-open.
   */
  @PackageScope
  void refreshPullRequestStatus() {
    int generation = beginPrCheckGeneration()
    new SwingWorker<String, Void>() {
      @Override
      protected String doInBackground() {
        collectPullRequestLabel()
      }

      @Override
      protected void done() {
        if (!isCurrentPrCheckGeneration(generation)) {
          return
        }
        String text
        try {
          text = get()
        } catch (Exception e) {
          log.warn("Could not check open PRs: {}", e.message)
          text = null
        }
        applyPullRequestLabel(text)
      }
    }.execute()
  }

  /** Starts a new PR-check generation, superseding any still-running earlier one. */
  @PackageScope
  int beginPrCheckGeneration() {
    prCheckGeneration.incrementAndGet()
  }

  /** Whether {@code generation} is still the most recently started PR-check call. */
  @PackageScope
  boolean isCurrentPrCheckGeneration(int generation) {
    generation == prCheckGeneration.get()
  }

  /** The PR label's shown text, or {@code null} when hidden — exposed read-only for tests. */
  @PackageScope
  String currentPullRequestText() {
    pullRequestLabel.isVisible() ? pullRequestLabel.text : null
  }

  private String collectPullRequestLabel() {
    if (gitTool == null) {
      return null
    }
    try {
      GitTool.GitResult result = gitTool.openPullRequestsForCurrentBranch()
      result.success ? pullRequestLabelFor(GitTool.parsePullRequestJson(result.output)) : null
    } catch (Exception e) {
      log.warn("Could not check open PRs: {}", e.message)
      null
    }
  }

  private void applyPullRequestLabel(String text) {
    pullRequestLabel.setText(text ?: "")
    pullRequestLabel.setVisible(text != null)
  }

  /**
   * Display text for the PR indicator from parsed {@code gh pr list} output, or {@code null} when
   * there is nothing to show (no open PR for the branch, or every entry lacks a usable number) —
   * callers hide the label in that case.
   */
  static String pullRequestLabelFor(List<Map> pullRequests) {
    if (pullRequests == null || pullRequests.isEmpty()) {
      return null
    }
    List<String> numbers = pullRequests.findAll { it?.get("number") != null }
                                        .collect { "#${it.get('number')}".toString() }
    if (numbers.isEmpty()) {
      return null
    }
    numbers.size() == 1 ? "PR ${numbers.get(0)}".toString() : "PRs ${numbers.join(', ')}".toString()
  }

  private void updateBranchSelection() {
    populating = true
    try {
      String current = currentBranchOrNull() ?: NO_BRANCH
      boolean present = false
      for (int i = 0; i < branchCombo.itemCount; i++) {
        if (current == branchCombo.getItemAt(i)) {
          present = true
          break
        }
      }
      if (!present) {
        branchCombo.addItem(current)
      }
      branchCombo.setSelectedItem(current)
    } finally {
      populating = false
    }
  }

  private String baseDir() {
    try {
      return fileEditingTool?.projectRoot?.toString() ?: '.'
    } catch (Exception ignored) {
      return '.'
    }
  }

  private String currentBranchOrNull() {
    try {
      String branch = gitTool?.currentBranch()
      (branch != null && branch != "HEAD") ? branch : null
    } catch (Exception ignored) {
      null
    }
  }

  /** True when {@code name} matches the conservative ref shape and is not a leading-dash option. */
  static boolean isSafeBranchName(String name) {
    name != null && !name.startsWith("-") && SAFE_BRANCH.matcher(name).matches()
  }

  private static String stripRemote(String ref) {
    int slash = ref.indexOf((int) ('/' as char))
    slash >= 0 ? ref.substring(slash + 1) : ref
  }

  private static Component spacer() {
    Box.createHorizontalStrut(SEGMENT_GAP)
  }

  /** Immutable snapshot of branch state collected off the EDT and applied on it. */
  @CompileStatic
  private static class BranchData {
    final String current
    final List<String> locals
    final List<String> remotes

    BranchData(String current, List<String> locals, List<String> remotes) {
      this.current = current
      this.locals = locals != null ? locals : List.<String> of()
      this.remotes = remotes != null ? remotes : List.<String> of()
    }
  }
}
