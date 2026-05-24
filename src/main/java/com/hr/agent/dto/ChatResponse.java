package com.hr.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.*;

import java.time.LocalDateTime;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ChatResponse {
    private String        recruiterId;
    private String        message;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JsonNode      data;
    private LocalDateTime timestamp;
    @Builder.Default private boolean success = true;
    private String        error;

    public static ChatResponse ok(String recruiterId, String message) {
        return ChatResponse.builder()
                .recruiterId(recruiterId).message(message)
                .timestamp(LocalDateTime.now()).success(true).build();
    }

    public static ChatResponse error(String recruiterId, String errorMsg) {
        return ChatResponse.builder()
                .recruiterId(recruiterId)
                .message("Sorry, something went wrong. Please try again.")
                .error(errorMsg).timestamp(LocalDateTime.now()).success(false).build();
    }
}
