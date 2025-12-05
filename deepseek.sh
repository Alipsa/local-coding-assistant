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

model_to_run=""
installed_models=$(ollama list | grep deepseek-coder | awk '{print $1}')

if [ -z "$installed_models" ]; then
  echo "No deepseek-coder models found. Installing one based on available RAM."
  ram_gb=0
  case "$os" in
    mac)
      ram_bytes=$(sysctl -n hw.memsize)
      ram_gb=$((ram_bytes / 1024 / 1024 / 1024))
      ;;
    linux)
      ram_kb=$(grep MemTotal /proc/meminfo | awk '{print $2}')
      ram_gb=$((ram_kb / 1024 / 1024))
      ;;
    windows)
      echo "Cannot automatically determine RAM on Windows. Please install a deepseek-coder model manually."
      echo "For example: ollama pull deepseek-coder:6.7b"
      exit 1
      ;;
  esac

  echo "Detected $ram_gb GB of RAM."

  if [ "$ram_gb" -gt 32 ]; then
    model_to_run="deepseek-coder:33b"
  elif [ "$ram_gb" -gt 16 ]; then
    model_to_run="deepseek-coder:6.7b"
  else
    model_to_run="deepseek-coder:1.3b"
  fi
  
  echo "Pulling $model_to_run..."
  ollama pull "$model_to_run"
else
  # If models are installed, use the first one in the list
  model_to_run=$(echo "$installed_models" | head -n 1)
  echo "Using existing model: $model_to_run"
fi


echo "Running model: $model_to_run"
ollama run "$model_to_run"
