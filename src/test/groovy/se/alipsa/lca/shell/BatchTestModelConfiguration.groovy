package se.alipsa.lca.shell

import com.embabel.common.ai.model.Llm
import groovy.transform.CompileStatic
import org.springframework.ai.chat.metadata.ChatGenerationMetadata
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration
@Profile("batch-test")
@CompileStatic
class BatchTestModelConfiguration {

  @Bean
  ChatModel batchTestChatModel() {
    new ChatModel() {
      @Override
      ChatResponse call(Prompt prompt) {
        // Create a proper mock response with a Generation and AssistantMessage
        def message = new AssistantMessage("Mock LLM response for testing")
        def generation = new Generation(message, ChatGenerationMetadata.NULL)
        new ChatResponse([generation])
      }
    }
  }

  @Bean
  Llm batchTestLlm(ChatModel chatModel) {
    new Llm("qwen3-coder:30b", "test", chatModel)
  }

  @Bean
  Llm batchTestFallbackLlm(ChatModel chatModel) {
    new Llm("gpt-oss:20b", "test", chatModel)
  }
}
