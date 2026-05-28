package com.hr.agent.config;

import com.hr.agent.service.HrAgentService;
import com.hr.agent.tools.*;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class OllamaConfig {

    @Value("${ollama.base-url}")       private String baseUrl;
    @Value("${ollama.model}")          private String model;
    @Value("${ollama.temperature}")    private double temperature;
    @Value("${hr.agent.memory-max-messages:20}") private int maxMessages;

    @Bean
    public OllamaChatModel ollamaChatModel() {
        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(model)
                .timeout(Duration.ofSeconds(120))
                .temperature(temperature)
                .build();
    }

    @Bean
    public InMemoryChatMemoryStore chatMemoryStore() {
        return new InMemoryChatMemoryStore();
    }

    @Bean
    public HrAgentService hrAgentService(
            OllamaChatModel chatModel,
            InMemoryChatMemoryStore memoryStore,
            CvParserTool cvParserTool,
            ScoringTool scoringTool,
            CandidateTool candidateTool,
            SchedulerTool schedulerTool,
            EmailTool emailTool,
            JobPostingTool jobPostingTool) {

        return AiServices.builder(HrAgentService.class)
                .chatLanguageModel(chatModel)
                .chatMemoryProvider(memoryId ->
                        MessageWindowChatMemory.builder()
                                .id(memoryId)
                                .maxMessages(maxMessages)
                                .chatMemoryStore(memoryStore)
                                .build())
                .tools(cvParserTool, scoringTool, candidateTool, schedulerTool, emailTool, jobPostingTool)
                .build();
    }
}
