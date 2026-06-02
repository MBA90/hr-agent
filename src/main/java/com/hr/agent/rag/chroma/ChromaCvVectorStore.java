package com.hr.agent.rag.chroma;

import com.hr.agent.entity.Application;
import com.hr.agent.rag.CvVectorStore;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ChromaCvVectorStore implements CvVectorStore {

    private final EmbeddingStore<TextSegment> chromaEmbeddingStore;
    private final EmbeddingStoreIngestor cvIngestor;

    @Override
    public void index(Application application, String rawText) {
        String appRefNo = application.getAppRefNo();

        // Idempotent: wipe any previous vectors for this application before re-indexing.
        // CV re-uploads (v2, v3 …) must replace rather than accumulate.
        try {
            chromaEmbeddingStore.removeAll(new IsEqualTo("app_ref_no", appRefNo));
            log.debug("Cleared previous vectors for appRefNo={}", appRefNo);
        } catch (Exception e) {
            log.warn("Could not clear previous vectors for {} — skipping deduplication: {}", appRefNo, e.getMessage());
        }

        Metadata metadata = new Metadata()
                .put("app_ref_no",       appRefNo)
                .put("candidate_name",   application.getCandidate().getFullName())
                .put("candidate_email",  application.getCandidate().getEmail())
                .put("job_id",           String.valueOf(application.getJobPosting().getId()))
                .put("job_title",        application.getJobPosting().getTitle())
                .put("cv_version",       String.valueOf(application.getCvVersion()));

        Document document = Document.from(rawText, metadata);
        cvIngestor.ingest(document);

        log.info("Indexed CV for appRefNo={} jobId={} version={}",
                appRefNo, application.getJobPosting().getId(), application.getCvVersion());
    }
}