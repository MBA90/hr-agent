package com.hr.agent.rag;

import java.util.List;

public record CvChunk(
        CvSection section,
        String content,
        int chunkIndex,
        int sectionChunkIndex,
        List<String> keywords
) {}