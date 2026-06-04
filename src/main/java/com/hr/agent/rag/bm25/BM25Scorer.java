package com.hr.agent.rag.bm25;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class BM25Scorer {

    private static final double K1 = 1.5;
    private static final double B  = 0.75;

    private static final Set<String> STOP_WORDS = Set.of(
            "a","an","the","and","or","but","in","on","at","to","for","of","with",
            "by","from","up","about","into","through","during","is","are","was","were",
            "be","been","being","have","has","had","do","does","did","will","would",
            "could","should","may","might","shall","can","not","no","nor","so","yet",
            "both","either","neither","each","few","more","most","other","some","such",
            "this","that","these","those","my","your","his","her","its","our","their",
            "i","me","we","you","he","she","it","they","them","us","who","which","what"
    );

    /**
     * Extracts the top-N keywords from text by raw term frequency,
     * after tokenizing and removing stop words.
     */
    public List<String> extractKeywords(String text, int topN) {
        Map<String, Integer> tf = new HashMap<>();
        for (String token : tokenize(text)) {
            if (!STOP_WORDS.contains(token) && token.length() > 2) {
                tf.merge(token, 1, Integer::sum);
            }
        }
        return tf.entrySet().stream()
                 .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                 .limit(topN)
                 .map(Map.Entry::getKey)
                 .collect(Collectors.toList());
    }

    /**
     * Computes a BM25 score for a query against a single document.
     * Uses simplified per-document IDF (ln(2 + normalizedTF)) since full corpus
     * statistics are not maintained; suitable for re-ranking a candidate set.
     */
    public double score(List<String> queryTerms, String document, int avgDocLen) {
        List<String> docTokens = tokenize(document);
        int docLen = docTokens.isEmpty() ? 1 : docTokens.size();
        Map<String, Long> docTf = docTokens.stream()
                .collect(Collectors.groupingBy(t -> t, Collectors.counting()));

        double bm25 = 0.0;
        for (String term : queryTerms) {
            long f = docTf.getOrDefault(term, 0L);
            if (f > 0) {
                double normalizedTf = (f * (K1 + 1.0))
                        / (f + K1 * (1.0 - B + B * docLen / (double) avgDocLen));
                bm25 += Math.log(2.0 + normalizedTf);
            }
        }
        return bm25;
    }

    public List<String> tokenize(String text) {
        return Arrays.stream(text.toLowerCase().split("[^a-z0-9+#.]+"))
                     .filter(t -> t.length() > 1)
                     .collect(Collectors.toList());
    }
}