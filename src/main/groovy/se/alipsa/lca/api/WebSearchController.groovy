package se.alipsa.lca.api

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import se.alipsa.lca.tools.WebSearchTool

@RestController
@RequestMapping("/api/search")
class WebSearchController {

    private final WebSearchTool webSearchAgent

    WebSearchController(WebSearchTool webSearchAgent) {
        this.webSearchAgent = webSearchAgent
    }

    @GetMapping
    List<WebSearchTool.SearchResult> search(@RequestParam String query) {
        webSearchAgent.search(query)
    }
}
