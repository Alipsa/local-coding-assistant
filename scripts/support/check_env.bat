@echo off
:: Check local prerequisites for Ollama-only use
echo Checking local environment...

where ollama >nul 2>nul
if errorlevel 1 (
    echo ERROR: ollama is not installed or not on PATH.
    echo Install it from https://ollama.ai and ensure the daemon is running.
    exit /b 1
)

echo ollama found. Using local models only.
