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
eval "$(grep -E '^(BASE_CHAT_MODEL|BASE_FALLBACK_MODEL|EMBEDDING_MODEL|CUSTOM_CHAT_MODEL|CUSTOM_CHAT_CONTEXT|QWEN_EXTRA_PARAMS|CUSTOM_FALLBACK_MODEL|CUSTOM_FALLBACK_CONTEXT|REVIEW_MODEL|REVIEW_CONTEXT|DEFAULT_CONTEXT_WINDOW|MODEL_STATE_DIR)=' "$LCA_SCRIPT")"

# Guard against a variable being renamed/added in lca without updating the grep alternation
# above (which would silently eval to empty) or a value in lca referencing another variable
# defined later in that file (which would also evaluate to empty here). QWEN_EXTRA_PARAMS is
# deliberately excluded: an empty extra-params string is a legitimate value, not a bug.
for _v in BASE_CHAT_MODEL BASE_FALLBACK_MODEL EMBEDDING_MODEL CUSTOM_CHAT_MODEL \
  CUSTOM_CHAT_CONTEXT CUSTOM_FALLBACK_MODEL CUSTOM_FALLBACK_CONTEXT REVIEW_MODEL \
  REVIEW_CONTEXT DEFAULT_CONTEXT_WINDOW MODEL_STATE_DIR; do
  eval "_val=\$$_v"
  if [ -z "$_val" ]; then
    echo "Error: $_v not resolved from $LCA_SCRIPT" >&2
    exit 1
  fi
done

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

get_model_id() {
    model="$1"
    ollama list 2>/dev/null | awk -v m="$model" '$1 == m {print $2; exit}'
}

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

    current_id="$(get_model_id "$base_model")"
    if [ -z "$current_id" ]; then
      echo "Warning: could not retrieve ID for $base_model. Skipping $custom_name."
      return
    fi

    # Mirrors src/main/bin/lca's rebuild_custom_model_if_changed: fingerprint the full desired
    # Modelfile recipe (base id + context + extra params), not just the base model's id, so a
    # context/parameter-only change is detected even when the base model itself hasn't changed.
    # Shares lca's MODEL_STATE_DIR so the two scripts agree on whether a custom model is stale.
    desired_signature="${current_id}|${context_size}|${extra_params}"
    state_file="${MODEL_STATE_DIR}/${custom_name}.id"
    saved_signature=""
    if [ -f "$state_file" ]; then
      saved_signature="$(cat "$state_file")"
    fi

    custom_exists="no"
    if ollama list 2>/dev/null | awk '{print $1}' | grep -Fxq "${custom_name}:latest"; then
      custom_exists="yes"
    fi

    if [ "$force" != true ] && [ "$desired_signature" = "$saved_signature" ] && [ "$custom_exists" = "yes" ]; then
      echo "$custom_name is up to date."
      return
    fi

    if [ "$custom_exists" = "yes" ]; then
      echo "Rebuilding $custom_name (base model, context, or parameters changed; or --force)..."
      ollama rm "$custom_name"
    else
      echo "$custom_name not found. Creating..."
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
    mkdir -p "$MODEL_STATE_DIR"
    printf '%s\n' "$desired_signature" > "$state_file"

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