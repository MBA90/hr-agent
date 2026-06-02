package com.hr.agent.rag;

import com.hr.agent.entity.Application;

public interface CvVectorStore {

    /**
     * Chunk and embed the given CV text into the vector store, tagging each
     * chunk with application metadata. Idempotent: existing vectors for the
     * same {@code app_ref_no} are replaced before new ones are added.
     */
    void index(Application application, String rawText);
}