package com.hr.agent.rag.event;

public class CvUploadedEvent {

    private final Long applicationId;

    public CvUploadedEvent(Long applicationId) {
        this.applicationId = applicationId;
    }

    public Long getApplicationId() {
        return applicationId;
    }
}