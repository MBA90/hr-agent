package com.hr.agent.rag.chroma;

import com.hr.agent.entity.Application;
import com.hr.agent.rag.CvChunk;
import com.hr.agent.rag.CvSearchResult;
import com.hr.agent.rag.CvSection;
import com.hr.agent.rag.CvVectorStore;
import com.hr.agent.rag.bm25.BM25Scorer;
import com.hr.agent.rag.chunking.CvSectionSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class ChromaCvVectorStore implements CvVectorStore {

    /** RRF constant — higher value reduces the influence of rank position. */
    private static final int RRF_K = 60;

    private final EmbeddingStore<TextSegment> chromaEmbeddingStore;
    private final EmbeddingModel ollamaEmbeddingModel;
    private final CvSectionSplitter sectionSplitter;
    private final BM25Scorer bm25Scorer;

    // ── Indexing ───────────────────────────────────────────────────────────

    @Override
    public void index(Application application, String rawText) {
        String appRefNo = application.getAppRefNo();

        // Idempotent: wipe any previous vectors before re-indexing
        try {
            chromaEmbeddingStore.removeAll(new IsEqualTo("app_ref_no", appRefNo));
            log.debug("Cleared previous vectors for appRefNo={}", appRefNo);
        } catch (Exception e) {
            log.warn("Could not clear previous vectors for {} — skipping deduplication: {}", appRefNo, e.getMessage());
        }

        // Section-based chunking with BM25 keyword extraction per chunk
        List<CvChunk> chunks = sectionSplitter.split(rawText);

        for (CvChunk chunk : chunks) {
            Metadata metadata = buildMetadata(application, chunk);
            TextSegment segment = TextSegment.from(chunk.content(), metadata);
            Embedding embedding = ollamaEmbeddingModel.embed(segment).content();
            chromaEmbeddingStore.add(embedding, segment);
        }

        log.info("Indexed {} chunks for appRefNo={} jobId={} version={} sections={}",
                chunks.size(), appRefNo,
                application.getJobPosting().getId(),
                application.getCvVersion(),
                chunks.stream().map(c -> c.section().name()).distinct().sorted().collect(Collectors.joining(",")));
    }

    // ── Retrieval ──────────────────────────────────────────────────────────

    @Override
    public List<CvSearchResult> search(String query, int topK) {
        return hybridSearch(query, null, topK);
    }

    @Override
    public List<CvSearchResult> searchForApplication(String appRefNo, String query, int topK) {
        return hybridSearch(query, appRefNo, topK);
    }

    // ── Hybrid search (dense + BM25 re-rank with RRF) ─────────────────────

    private List<CvSearchResult> hybridSearch(String query, String appRefNo, int topK) {
        // 1. Dense retrieval: fetch 3× candidates to give BM25 room to re-rank
        Embedding queryEmbedding = ollamaEmbeddingModel.embed(query).content();
        var reqBuilder = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(topK * 3)
                .minScore(0.0);
        if (appRefNo != null) {
            reqBuilder.filter(new IsEqualTo("app_ref_no", appRefNo));
        }
        List<EmbeddingMatch<TextSegment>> denseMatches =
                chromaEmbeddingStore.search(reqBuilder.build()).matches();

        if (denseMatches.isEmpty()) return Collections.emptyList();

        // 2. BM25 sparse re-rank over the candidate set
        List<String> queryTerms = bm25Scorer.tokenize(query);
        int avgDocLen = (int) denseMatches.stream()
                .mapToInt(m -> m.embedded().text().split("\\s+").length)
                .average().orElse(100);

        List<EmbeddingMatch<TextSegment>> bm25Sorted = denseMatches.stream()
                .sorted(Comparator.comparingDouble(m ->
                        -bm25Scorer.score(queryTerms, m.embedded().text(), avgDocLen)))
                .collect(Collectors.toList());

        // 3. Reciprocal Rank Fusion
        Map<String, Integer> denseRank = buildRankMap(denseMatches);
        Map<String, Integer> bm25Rank  = buildRankMap(bm25Sorted);
        int n = denseMatches.size();

        return denseMatches.stream()
                .sorted(Comparator.comparingDouble(m ->
                        -rrfScore(m.embeddingId(), denseRank, bm25Rank, n)))
                .limit(topK)
                .map(this::toSearchResult)
                .collect(Collectors.toList());
    }

    private Map<String, Integer> buildRankMap(List<EmbeddingMatch<TextSegment>> matches) {
        Map<String, Integer> ranks = new HashMap<>();
        for (int i = 0; i < matches.size(); i++) {
            ranks.put(matches.get(i).embeddingId(), i);
        }
        return ranks;
    }

    private double rrfScore(String id, Map<String, Integer> denseRank,
                            Map<String, Integer> bm25Rank, int n) {
        int dr = denseRank.getOrDefault(id, n);
        int br = bm25Rank.getOrDefault(id, n);
        return (1.0 / (RRF_K + dr + 1)) + (1.0 / (RRF_K + br + 1));
    }

    // ── Metadata & result mapping ──────────────────────────────────────────

    private Metadata buildMetadata(Application application, CvChunk chunk) {
        return new Metadata()
                .put("app_ref_no",         application.getAppRefNo())
                .put("candidate_name",      application.getCandidate().getFullName())
                .put("candidate_email",     application.getCandidate().getEmail())
                .put("job_id",              String.valueOf(application.getJobPosting().getId()))
                .put("job_title",           application.getJobPosting().getTitle())
                .put("cv_version",          String.valueOf(application.getCvVersion()))
                .put("section_type",        chunk.section().name())
                .put("chunk_index",         String.valueOf(chunk.chunkIndex()))
                .put("section_chunk_index", String.valueOf(chunk.sectionChunkIndex()))
                .put("keywords",            String.join(",", chunk.keywords()));
    }

    private CvSearchResult toSearchResult(EmbeddingMatch<TextSegment> match) {
        Metadata meta = match.embedded().metadata();
        return new CvSearchResult(
                meta.getString("app_ref_no"),
                meta.getString("candidate_name"),
                meta.getString("job_title"),
                CvSection.valueOf(meta.getString("section_type")),
                match.embedded().text(),
                match.score()
        );
    }
}