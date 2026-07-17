# Github integration
- Use hub4j/github-api instead of relying on gh for all operations
```xml
<dependency>
    <groupId>org.kohsuke</groupId>
    <artifactId>github-api</artifactId>
    <version>1.330</version> <!-- Check for the latest version -->
</dependency>
``

## Step 1: Read the Token via gh auth token
By running a quick, non-interactive subprocess command (gh auth token), you let the gh CLI handle the heavy lifting of decrypting the token from the OS keychain.

```groovy
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.IOException

class GitHubAuthHelper {

    static String getGhCliToken() {
        // First check standard environment variables (always respect user overrides)
        String envToken = System.getenv("GH_TOKEN")
        if (envToken == null) {
            envToken = System.getenv("GITHUB_TOKEN")
        }
        if (envToken != null && !envToken.isEmpty()) {
            return envToken
        }

        // Fallback: Query the local gh CLI directly
        try {
            Process process = new ProcessBuilder("gh", "auth", "token").start()
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String token = reader.readLine()
                if (token != null && !token.trim().isEmpty()) {
                    return token.trim()
                }
            }
        } catch (IOException e) {
            // gh CLI might not be installed, or not authenticated
            System.err.println("Could not retrieve token from gh CLI: " + e.getMessage())
        }

        return null;
    }
```

## Step 2: Feed the Token to Your Library
Once you've retrieved the token dynamically, you can pass it right into the library.

```groovy
import org.kohsuke.github.GitHub
import org.kohsuke.github.GitHubBuilder

class App {
    static void main(String[] args) throws Exception {
        String token = GitHubAuthHelper.getGhCliToken()

        if (token == null) {
            println("Error: Please authenticate first by running 'gh auth login' in your terminal.")
            return
        }

        // Build the client using the resolved token
        GitHub github = new GitHubBuilder()
            .withOAuthToken(token)
            .build()

        println("Logged in as: " + github.getMyself().getLogin())
    }
}
```

## Example (Creating a Repository):

```groovy
import org.kohsuke.github.GitHub
import org.kohsuke.github.GHRepository

class GitHubDemo {
     static void main(String[] args) throws Exception {
        // Automatically picks up GH_TOKEN environment variable or ~/.github properties
        GitHub github = GitHub.connect()

        // Equivalent to: gh repo create my-new-repo --description "Built with Java" --public
        GHRepository repo = github.createRepository("my-new-repo")
            .description("Built with Groovy")
            .private_(false)
            .create()

        println("Created: " + repo.getHtmlUrl())
    }
}
```