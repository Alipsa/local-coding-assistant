#!/bin/sh

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

    echo "Creating custom model $custom_name from $base_model with context size $context_size..."

    # Check if custom model already exists
    if ollama list 2>/dev/null | grep -q "^$custom_name"; then
      echo "$custom_name already exists."
      return
    fi

    # Create a temporary Modelfile
    modelfile=$(mktemp)
    cat > "$modelfile" << EOF
FROM $base_model
PARAMETER num_ctx $context_size
EOF

    # Create the custom model
    ollama create "$custom_name" -f "$modelfile"

    # Clean up
    rm "$modelfile"

    echo "$custom_name created successfully."
}

# Install base models
#checkAndInstall deepseek-coder:6.7b
checkAndInstall qwen3-coder:30b
checkAndInstall gpt-oss:20b

# Create custom models with larger context
createCustomModel qwen3-coder:30b qwen-coder-32k 32768
createCustomModel gpt-oss:20b gpt-oss-32k 32768