# TODO

## demo/openCodeMlx: waiting on upstream opencode features

- **System resource (RAM/GPU) display in opencode's TUI.** Would be nice to
  see live RAM/GPU memory usage in the side panel or status bar while running
  `demo/openCodeMlx`, but opencode's plugin API currently has no hook for
  rendering persistent custom UI (status bar/side panel) - only
  `tui.toast.show`, `tui.prompt.append`, and `tui.command.execute`. No open
  upstream issue found requesting this specifically; revisit once opencode
  exposes a plugin hook for custom TUI surfaces, or consider a tmux-sidecar
  workaround if it becomes worth doing sooner.

- **Visible "% until next auto-compact" indicator.** opencode's TUI already
  shows context-used % (tokens / `model.limit.context`), but not how close
  that is to the auto-compaction threshold. The threshold itself is
  hardcoded (not configurable) at `context_limit - reserved`, where
  `reserved = min(20000, output_limit)` - for `demo/openCodeMlx`'s current
  settings this works out to ~87.2% for both models. Relevant upstream
  feature requests (none implemented yet as of 2026-07-13):
  - https://github.com/anomalyco/opencode/issues/8140
  - https://github.com/anomalyco/opencode/issues/11314
  - https://github.com/anomalyco/opencode/issues/11930
  - https://github.com/anomalyco/opencode/issues/14830
