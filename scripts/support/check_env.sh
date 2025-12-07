
# Check local prerequisites for Ollama-only use

echo "Checking local environment..."

if ! command -v ollama >/dev/null 2>&1; then
    echo "ERROR: ollama is not installed or not on PATH."
    echo "Install it from https://ollama.ai and ensure the daemon is running."
    exit 1
fi

echo "ollama found. Using local models only."
