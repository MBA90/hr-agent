package com.hr.agent.rag;

import com.hr.agent.entity.Application;

import java.util.List;

public interface CvVectorStore {

    /**
     * Section-chunk and dual-encode (dense + BM25 keywords) the CV text,
     * storing each chunk with rich metadata in the vector store.
     * Idempotent: existing vectors for the same {@code app_ref_no} are replaced.
     */
    void index(Application application, String rawText);

    /** Hybrid search (dense + BM25 re-rank with RRF) across all indexed CVs. */
    List<CvSearchResult> search(String query, int topK);

    /** Hybrid search scoped to a single application's CV chunks. */
    List<CvSearchResult> searchForApplication(String appRefNo, String query, int topK);
}