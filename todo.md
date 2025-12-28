1. (done) Fix the /exit command to leave the shell. Currently it responds with "No command found for 'exit'":
```
   starwars> /exit
   No command found for 'exit'
   Stay on target.
   starwars> /quit
   No command found for 'quit'
```
2. Add a /version command to display the current version of the application.
3. Implement logging of all commands executed in the shell to a log file.
4. Add support for piping output from one command to another (e.g., `/codesearch | /review`).
5. Implement a /config command to view and modify application settings.
6. (done) As of Embabel 0.3.1 (which is what we have) it is much easier to write chatbots in Embabel:
```
The Utility planner is ideal for chatbots, and the new trigger field on @Action enables reactive chatbot patterns, causing the action to fire only when a specific type is most recent on the blackboard:

@Action(canRerun = true, trigger = UserMessage.class)
void respond(Conversation conversation, ActionContext context) {
var reply = context.ai()
.withTemplate("chatbot")
.respondWithSystemPrompt(conversation, Map.of());
context.sendMessage(conversation.addMessage(reply));
}
Multiple actions can respond to messages with different values. The planner picks the most valuable. The @State mechanism also works well with chatbots.

See the https://github.com/embabel/ragbot repository for an example of a chatbot using RAG.
```
Can we take advantage of this in LCA to simplify the codebase?