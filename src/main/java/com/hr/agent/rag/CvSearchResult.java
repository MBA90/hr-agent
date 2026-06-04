package com.hr.agent.rag;

public record CvSearchResult(
        String appRefNo,
        String candidateName,
        String jobTitle,
        CvSection section,
        String content,
        double score
) {}