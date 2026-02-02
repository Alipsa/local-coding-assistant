# Aider - a local coding assistant that rivals Claude CLI or Codex

## The Engine: Ollama & Models
`ollama pull qwen3-coder:30b`

Create a virtual environment
`python3 -m venv venv`

Activate the environment
`source venv/bin/activate`

## Install Aider
`python -m pip install aider-chat`
export OLLAMA_API_BASE=http://127.0.0.1:11434

If Ollama is not running already:
`OLLAMA_NUM_CTX=32768 ollama serve`

If Ollama is already running as a service ensure we have a maximum context window

`sudo systemctl edit ollama.service`
Add the following
```
[Service]
Environment="OLLAMA_NUM_CTX=32768"
```
Reload the service
```shell
sudo systemctl daemon-reload
sudo systemctl restart ollama
```

## Run it
`aider --model ollama_chat/qwen3-coder:30b`

Example prompt
```
Write a groovy script that given a gradle style dependency string either lists the latest version (if the version is omitted) or checks if there is a later version avilable on maven central (if the version is included). Save the script as checkVersion.groovy
```

## Running ollama on a separate computer
Run the "Engine" (Ollama) on the beefy machine and the "Pilot" (Aider/CLI) on your laptop.

### Configure access to your server: use a ssh tunnel
Start the tunnel:
`ssh -L 11434:localhost:11434 user@192.168.1.50`

### Alternatively: Allow access to Ollama on your server remotely
On the server:
`sudo systemctl edit ollama.service`
Add the following so allow ollama to be used from outside of localhost:
```
[Service]
Environment="OLLAMA_HOST=0.0.0.0"
Environment="OLLAMA_NUM_CTX=32768"
```

Point your Laptop to the Server
```
export OLLAMA_API_BASE="http://192.168.1.50:11434"
aider --model ollama_chat/qwen3-coder:30b
```


