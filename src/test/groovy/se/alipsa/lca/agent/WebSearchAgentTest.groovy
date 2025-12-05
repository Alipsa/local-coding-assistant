package se.alipsa.lca.agent

import com.microsoft.playwright.*;
import org.junit.jupiter.api.Test
import org.mockito.Mockito

import static org.junit.jupiter.api.Assertions.assertEquals

class WebSearchAgentTest {

    @Test
    void testSearch() {
        def mockPlaywright = Mockito.mock(Playwright.class)
        def mockBrowser = Mockito.mock(Browser.class)
        def mockPage = Mockito.mock(Page.class)
        def mockElement = Mockito.mock(ElementHandle.class)
        def mockTitleElement = Mockito.mock(ElementHandle.class)

        def mockSnippetElement = Mockito.mock(ElementHandle.class)

        Mockito.when(mockPlaywright.chromium()).thenReturn(Mockito.mock(BrowserType.class))
        Mockito.when(mockPlaywright.chromium().launch()).thenReturn(mockBrowser)
        Mockito.when(mockBrowser.newPage()).thenReturn(mockPage)
        Mockito.when(mockPage.querySelectorAll(Mockito.anyString())).thenReturn([mockElement])
        Mockito.when(mockElement.querySelector("h2 a")).thenReturn(mockTitleElement)
        Mockito.when(mockTitleElement.innerText()).thenReturn("Test Title")
        Mockito.when(mockTitleElement.getAttribute("href")).thenReturn("http://example.com")
        Mockito.when(mockElement.querySelector("span.result__snippet")).thenReturn(mockSnippetElement)
        Mockito.when(mockSnippetElement.innerText()).thenReturn("Test Snippet")

        try (var mockedStatic = Mockito.mockStatic(Playwright.class)) {
            mockedStatic.when(Playwright::create).thenReturn(mockPlaywright)

            def agent = new WebSearchAgent()
            def results = agent.search("test query")

            assertEquals(1, results.size())
            assertEquals("Test Title", results[0].title)
            assertEquals("http://example.com", results[0].url)
            assertEquals("Test Snippet", results[0].snippet)
        }
    }
}
