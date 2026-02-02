#!/bin/bash

# 1. Path to your virtual environment
VENV_PATH="./venv/bin/activate"

# 2. Configuration
export OLLAMA_API_BASE="http://127.0.0.1:11434"
MODEL="ollama_chat/qwen3-coder:30b"
# ollama_chat/: This prefix is essential. It tells Aider to use the Chat completion API, which handles the "System Prompt" much better than the basic generation API.

# Check if venv exists
if [ -f "$VENV_PATH" ]; then
    echo "--- Activating Virtual Environment ---"
    source "$VENV_PATH"
else
    echo "Error: Virtual environment not found at $VENV_PATH"
    exit 1
fi

EXTRA_ARGS=""
if [ -f "AGENTS.md" ]; then
    echo "--- Found AGENTS.md, adding to context ---"
    EXTRA_ARGS="--read AGENTS.md"
fi

# 3. Launch Aider
# --architect: Uses a powerful model for planning and a smaller one for editing (if configured). This uses a "reasoning" workflow where the model first describes the plan and then writes the code.
# --auto-commits: Automatically commits changes to git
echo "--- Launching Aider with $MODEL ---"
aider --model "$MODEL" --architect $EXTRA_ARGS
