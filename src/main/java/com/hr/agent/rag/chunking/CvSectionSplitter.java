package com.hr.agent.rag.chunking;

import com.hr.agent.rag.CvChunk;
import com.hr.agent.rag.CvSection;
import com.hr.agent.rag.bm25.BM25Scorer;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a raw CV text into semantic, section-based chunks.
 *
 * Pipeline:
 *  1. normalizeInlineHeaders() — inserts newlines before ALL-CAPS section headers
 *     that PDFBox collapsed onto the same line as the preceding sentence.
 *  2. detectSections()        — accumulates content per detected section.
 *  3. buildChunks()           — sub-splits oversized sections and extracts BM25 keywords.
 */
@Component
@RequiredArgsConstructor
public class CvSectionSplitter {

    private final BM25Scorer bm25Scorer;

    @Value("${rag.ingestor.max-section-chars:1500}")
    private int maxSectionChars;

    @Value("${rag.ingestor.subsection-overlap:150}")
    private int subsectionOverlap;

    @Value("${rag.ingestor.keywords-per-chunk:15}")
    private int keywordsPerChunk;

    /**
     * Matches an ALL-CAPS phrase (letters, spaces, &, /) that immediately follows a
     * sentence-ending character. Used to split headers that PDFBox did not put on
     * their own line (e.g. "...environments. CORE SKILLS & TECHNOLOGIES Hands On:").
     */
    private static final Pattern INLINE_CAPS_PHRASE = Pattern.compile(
            "(?<=[.!?])\\s+([A-Z][A-Z &/\\-]{1,50}[A-Z])(?=\\s+\\S)");

    private static final List<Map.Entry<Pattern, CvSection>> SECTION_PATTERNS = buildSectionPatterns();

    // ── Public entry point ─────────────────────────────────────────────────

    public List<CvChunk> split(String rawText) {
        String normalized = normalizeInlineHeaders(rawText);
        List<SectionContent> sections = detectSections(normalized);
        return buildChunks(sections);
    }

    // ── Step 1 — inline-header normalization ───────────────────────────────

    /**
     * Scans each line for ALL-CAPS phrases that immediately follow a sentence end
     * and that match a known section header. Inserts newlines around them so that
     * the subsequent line-by-line detection pass can pick them up cleanly.
     */
    private String normalizeInlineHeaders(String text) {
        String[] lines = text.split("\\r?\\n", -1);
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            out.append(splitOnInlineHeaders(line)).append("\n");
        }
        return out.toString();
    }

    private String splitOnInlineHeaders(String line) {
        Matcher m = INLINE_CAPS_PHRASE.matcher(line);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String phrase = m.group(1).trim();
            if (detectHeader(phrase) != null) {
                // Place the header on its own line so detectSections() sees it cleanly
                m.appendReplacement(sb, "\n" + phrase + "\n");
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ── Step 2 — section detection ─────────────────────────────────────────

    private List<SectionContent> detectSections(String text) {
        String[] lines = text.split("\\r?\\n");
        List<SectionContent> sections = new ArrayList<>();

        CvSection currentSection = CvSection.OTHER;
        StringBuilder buffer = new StringBuilder();

        for (String line : lines) {
            CvSection detected = detectHeader(line);
            if (detected != null) {
                flushSection(sections, currentSection, buffer);
                currentSection = detected;
                buffer = new StringBuilder();
            } else {
                if (!buffer.isEmpty() || !line.isBlank()) {
                    buffer.append(line).append("\n");
                }
            }
        }
        flushSection(sections, currentSection, buffer);
        return sections;
    }

    private void flushSection(List<SectionContent> out, CvSection section, StringBuilder buffer) {
        String content = buffer.toString().trim();
        if (!content.isBlank()) {
            out.add(new SectionContent(section, content));
        }
    }

    private static CvSection detectHeader(String line) {
        // Strip markdown markers, decorative borders, and trailing punctuation
        String clean = line.trim()
                           .replaceAll("^[#*=\\-─_|\\s]+", "")
                           .replaceAll("[#*=\\-─_|:\\s]+$", "")
                           .trim();
        if (clean.isEmpty() || clean.length() > 60) return null;
        for (var entry : SECTION_PATTERNS) {
            if (entry.getKey().matcher(clean).matches()) {
                return entry.getValue();
            }
        }
        return null;
    }

    // ── Step 3 — chunk building ────────────────────────────────────────────

    private List<CvChunk> buildChunks(List<SectionContent> sections) {
        List<CvChunk> chunks = new ArrayList<>();
        int globalIndex = 0;
        for (SectionContent sec : sections) {
            List<String> subChunks = splitIfOversized(sec.content());
            for (int i = 0; i < subChunks.size(); i++) {
                String content = subChunks.get(i);
                List<String> keywords = bm25Scorer.extractKeywords(content, keywordsPerChunk);
                chunks.add(new CvChunk(sec.section(), content, globalIndex++, i, keywords));
            }
        }
        return chunks;
    }

    private List<String> splitIfOversized(String text) {
        if (text.length() <= maxSectionChars) return List.of(text);

        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxSectionChars, text.length());
            // Prefer breaking at a newline near the boundary
            if (end < text.length()) {
                int newline = text.lastIndexOf('\n', end);
                if (newline > start + maxSectionChars / 2) {
                    end = newline + 1;
                }
            }
            parts.add(text.substring(start, end).trim());
            if (end >= text.length()) break; // reached end — no trailing overlap chunk
            int nextStart = end - subsectionOverlap;
            if (nextStart <= start) break; // guard: no forward progress
            start = nextStart;
        }
        return parts;
    }

    // ── Section pattern registry ───────────────────────────────────────────

    private static List<Map.Entry<Pattern, CvSection>> buildSectionPatterns() {
        return List.of(
            Map.entry(
                Pattern.compile("(?i)(contact( information| details| info)?|personal (info|details|information|data))"),
                CvSection.CONTACT),
            Map.entry(
                Pattern.compile("(?i)(summary|professional summary|profile|objective|about me|career objective|professional profile)"),
                CvSection.SUMMARY),
            Map.entry(
                Pattern.compile("(?i)(experience|work experience|employment( history)?|work history|professional experience|career history|work record)"),
                CvSection.EXPERIENCE),
            Map.entry(
                Pattern.compile("(?i)(education(al background)?|academic( background)?|qualifications?|degrees?|academic history)"),
                CvSection.EDUCATION),
            // Handles: Skills, Technical Skills, Core Skills, Core Skills & Technologies,
            // Key Skills, Core Competencies, Expertise, Technologies, Tech Stack
            Map.entry(
                Pattern.compile("(?i)((?:(?:core|technical|key|professional|hard|soft)\\s+)?skills?(?:\\s*[&,/\\s]+(?:technologies?|competencies?|expertise?))?|core competencies?|expertise|technologies|tech stack)"),
                CvSection.SKILLS),
            Map.entry(
                Pattern.compile("(?i)(certifications?|certificates?|credentials?|licenses?|accreditations?)"),
                CvSection.CERTIFICATIONS),
            Map.entry(
                Pattern.compile("(?i)(projects?|portfolio|key projects?|personal projects?|notable projects?)"),
                CvSection.PROJECTS),
            Map.entry(
                Pattern.compile("(?i)(languages?|language skills?|spoken languages?|foreign languages?)"),
                CvSection.LANGUAGES)
        );
    }

    private record SectionContent(CvSection section, String content) {}
}