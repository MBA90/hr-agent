package com.hr.agent.dto;

import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor
public class ChatRequest {
    private String recruiterId;
    private String message;
}
