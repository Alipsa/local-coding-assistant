#!/bin/sh

force=false
while [ $# -gt 0 ]; do
  case "$1" in
    -f|--force)
      force=true
      ;;
    *)
      echo "Unknown option: $1"
      echo "Usage: $0 [-f|--force]"
      exit 1
      ;;
  esac
  shift
done

# Model names/contexts are not duplicated here: src/main/bin/lca is the canonical
# source (it must be self-contained since it's distributed standalone), so we read
# its named variables via a targeted grep+eval.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LCA_SCRIPT="$SCRIPT_DIR/src/main/bin/lca"
if [ ! -f "$LCA_SCRIPT" ]; then
  echo "Error: canonical model config not found at $LCA_SCRIPT" >&2
  exit 1
fi
eval "$(grep -E '^(BASE_CHAT_MODEL|BASE_FALLBACK_MODEL|EMBEDDING_MODEL|CUSTOM_CHAT_MODEL|CUSTOM_CHAT_CONTEXT|QWEN_EXTRA_PARAMS|CUSTOM_FALLBACK_MODEL|CUSTOM_FALLBACK_CONTEXT|REVIEW_MODEL|REVIEW_CONTEXT|DEFAULT_CONTEXT_WINDOW)=' "$LCA_SCRIPT")"

os=""
case "$(uname -s)" in
  Darwin)
    os="mac"
    ;;
  Linux)
    os="linux"
    ;;
  CYGWIN*|MINGW*|MSYS*)
    os="windows"
    ;;
  *)
    echo "Unsupported operating system"
    exit 1
    ;;
esac

echo "Detected OS: $os"

if ! command -v ollama >/dev/null 2>&1; then
  echo "ollama could not be found"
  case "$os" in
    mac)
      echo "Installing ollama using Homebrew..."
      if ! command -v brew >/dev/null 2>&1; then
        echo "Homebrew not found. Please install Homebrew first: https://brew.sh/"
        exit 1
      fi
      brew install ollama
      ;;
    linux)
      echo "Installing ollama using curl..."
      curl -fsSL https://ollama.ai/install.sh | sh
      ;;
    windows)
      echo "Please install ollama manually by downloading it from https://ollama.ai"
      exit 1
      ;;
  esac
fi

checkAndInstall() {
    model="$1"
    echo "Checking for $model model..."
    installed_models="$(ollama list 2>/dev/null | grep "$model" | awk '{print $1}')"
    if [ -z "$installed_models" ]; then
      echo "$model model not found. Installing..."
      ollama pull "$model"
    else
      echo "$model model is already installed."
    fi
}

createCustomModel() {
    base_model="$1"
    custom_name="$2"
    context_size="$3"
    extra_params="${4:-}"

    echo "Creating custom model $custom_name from $base_model with context size $context_size..."

    # Check if custom model already exists
    if ollama list 2>/dev/null | grep -q "^$custom_name"; then
      if [ "$force" = true ]; then
        echo "$custom_name already exists. Removing before recreating (--force)..."
        ollama rm "$custom_name"
      else
        echo "$custom_name already exists."
        return
      fi
    fi

    # Create a temporary Modelfile
    modelfile=$(mktemp)
    {
      echo "FROM $base_model"
      echo "PARAMETER num_ctx $context_size"
      if [ -n "$extra_params" ]; then
        old_ifs="$IFS"
        IFS=';'
        for kv in $extra_params; do
          key="${kv%%=*}"
          value="${kv#*=}"
          echo "PARAMETER $key $value"
        done
        IFS="$old_ifs"
      fi
    } > "$modelfile"

    # Create the custom model
    ollama create "$custom_name" -f "$modelfile"

    # Clean up
    rm "$modelfile"

    echo "$custom_name created successfully."
}

# Install base models
checkAndInstall "$BASE_CHAT_MODEL"
checkAndInstall "$BASE_FALLBACK_MODEL"
checkAndInstall "$EMBEDDING_MODEL"

# Create custom models with larger context
createCustomModel "$BASE_CHAT_MODEL" "$CUSTOM_CHAT_MODEL" "$CUSTOM_CHAT_CONTEXT" "$QWEN_EXTRA_PARAMS"
createCustomModel "$BASE_FALLBACK_MODEL" "$CUSTOM_FALLBACK_MODEL" "$CUSTOM_FALLBACK_CONTEXT"

# Create review model with thinking disabled and smaller context for faster response
createCustomModel "$BASE_CHAT_MODEL" "$REVIEW_MODEL" "$REVIEW_CONTEXT" "$QWEN_EXTRA_PARAMS"