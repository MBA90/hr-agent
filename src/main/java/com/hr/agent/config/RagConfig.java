package com.hr.agent.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaApiVersion;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RagConfig {

    @Value("${ollama.base-url}")
    private String ollamaBaseUrl;

    @Value("${rag.embedding.model}")
    private String embeddingModelName;

    @Value("${rag.chroma.url}")
    private String chromaUrl;

    @Value("${rag.chroma.collection-name}")
    private String collectionName;

    @Value("${rag.chroma.tenant}")
    private String tenantName;

    @Value("${rag.chroma.database}")
    private String databaseName;

    @Bean
    public EmbeddingModel ollamaEmbeddingModel() {
        return OllamaEmbeddingModel.builder()
                .baseUrl(ollamaBaseUrl)
                .modelName(embeddingModelName)
                .build();
    }

    @Bean
    public EmbeddingStore<TextSegment> chromaEmbeddingStore() {
        return ChromaEmbeddingStore.builder()
                .baseUrl(chromaUrl)
                .collectionName(collectionName)
                .tenantName(tenantName)
                .databaseName(databaseName)
                .apiVersion(ChromaApiVersion.V2)
                .build();
    }
}