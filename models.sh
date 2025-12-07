#!/usr/bin/env bash

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

if ! command -v ollama &> /dev/null; then
  echo "ollama could not be found"
  case "$os" in
    mac)
      echo "Installing ollama using Homebrew..."
      if ! command -v brew &> /dev/null; then
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

function checkAndInstall() {
    model=$1
    echo "Checking for $model model..."
    installed_models=$(ollama list | grep $model | awk '{print $1}')
    if [ -z "$installed_models" ]; then
      echo "$model model not found. Installing..."
      ollama pull $model
    else
      echo "$model model is already installed."
    fi
}


#checkAndInstall deepseek-coder:6.7b
checkAndInstall qwen3-coder:30b
checkAndInstall gpt-oss:20b