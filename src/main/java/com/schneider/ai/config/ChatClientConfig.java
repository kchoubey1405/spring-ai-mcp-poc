package com.schneider.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI ChatClient configuration.
 * Configures the official Spring AI ChatClient.Builder and ChatClient beans
 * using the framework-provided ChatModel.
 */
@Configuration
public class ChatClientConfig {

    @Bean
    @ConditionalOnMissingBean(ChatClient.Builder.class)
    public ChatClient.Builder chatClientBuilder(ChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }

    @Bean
    @ConditionalOnMissingBean(ChatClient.class)
    public ChatClient defaultChatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
