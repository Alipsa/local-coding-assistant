# A2A Support

Embabel integrates with the [A2A](https://github.com/google-a2a/A2A) protocol, allowing you to connect to other
A2A-enabled agents and services. This is optional and your agents still use Ollama-only models; skip this section if you want to stay completely offline.

> Embabel agents can be exposed to A2A with zero developer effort.

Check out the `a2a` branch of this repository to try A2A support.

To try the reference Google A2A web UI you'll need a Google Studio API key (used by that UI for Gemini). This does not change the fact that your agent itself runs on Ollama; omit this if you don't need the UI.

Start the Google A2A web interface using Docker compose:

```bash
docker compose up
```

Go to the web interface running within the container at `http://localhost:12000/`.

Connect to your agent at `host.docker.internal:8080/a2a`. Note that `localhost:8080/a2a` won't work as the server
cannot access it when running in a Docker container.

Your agent will have automatically been exported to A2A. Add it in the UI, and start a chat.
You should see something like this:

<img src="images/a2a_ui.jpg" alt="A2A UI" width="600">
