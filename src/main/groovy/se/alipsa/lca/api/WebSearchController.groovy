package se.alipsa.lca.api

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import se.alipsa.lca.agent.WebSearchAgent

@RestController
@RequestMapping("/api/search")
class WebSearchController {

    private final WebSearchAgent webSearchAgent

    WebSearchController(WebSearchAgent webSearchAgent) {
        this.webSearchAgent = webSearchAgent
    }

    @GetMapping
    List<WebSearchAgent.SearchResult> search(@RequestParam String query) {
        webSearchAgent.search(query)
    }
}
