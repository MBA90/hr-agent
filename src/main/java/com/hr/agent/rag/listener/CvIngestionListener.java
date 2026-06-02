package com.hr.agent.rag.listener;

import com.hr.agent.entity.Application;
import com.hr.agent.rag.CvVectorStore;
import com.hr.agent.rag.event.CvUploadedEvent;
import com.hr.agent.repository.ApplicationRepository;
import com.hr.agent.service.PdfTextExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class CvIngestionListener {

    private final ApplicationRepository applicationRepository;
    private final PdfTextExtractor pdfTextExtractor;
    private final CvVectorStore cvVectorStore;

    @Async
    @EventListener
    @Transactional(readOnly = true)
    public void onCvUploaded(CvUploadedEvent event) {
        Long applicationId = event.getApplicationId();
        log.info("Starting async CV ingestion for applicationId={}", applicationId);
        try {
            Application application = applicationRepository
                    .findByIdWithDetails(applicationId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Application not found: " + applicationId));

            String rawText = pdfTextExtractor.extract(application.getCvFilePath());
            cvVectorStore.index(application, rawText);

            log.info("CV ingestion complete for appRefNo={}", application.getAppRefNo());
        } catch (Exception e) {
            // Chroma/Ollama outage must not roll back the HTTP upload — log and move on.
            log.error("CV ingestion failed for applicationId={} — vector store not updated",
                    applicationId, e);
        }
    }
}