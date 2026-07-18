# TODO

## Add a way to access command history in the lcaGui.
e.g. shift+up/down to scroll through previous commands, or a dedicated "history" panel.

## Memory and GPU setting
If i run ollama on a remote machine via an ssh tunnel, lcaGui thinks that i am running locally. We should split local memory and ollama memory or (if there is no room), only show the ollama memory.

## Application icon
Currently the duke icon is used for the application. We should create a custom icon for the application.
Create and SVG icon as the base for icon creation. Use a nice font to write LCA on a midnight blue background. Use the SVG to create a PNG icon for the application.

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
