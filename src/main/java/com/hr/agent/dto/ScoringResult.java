package com.hr.agent.dto;

import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ScoringResult {
    private Long   candidateId;
    private Long   jobId;
    private Double score;
    private String reason;
    private String strengths;
    private String gaps;
    private String recommendation;

    public boolean isShortlisted(double threshold) {
        return score != null && score >= threshold;
    }
}
