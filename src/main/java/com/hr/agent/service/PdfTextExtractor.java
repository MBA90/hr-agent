package com.hr.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;

@Component
@Slf4j
public class PdfTextExtractor {

    @Value("${hr.agent.cv-storage-path:./cv-uploads/}")
    private String cvStoragePath;

    public String extract(String filePath) throws Exception {
        File pdfFile = new File(filePath);
        if (!pdfFile.exists()) {
            pdfFile = new File(cvStoragePath + filePath);
        }
        log.debug("Extracting text from PDF: {}", pdfFile.getAbsolutePath());
        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            return new PDFTextStripper().getText(doc);
        }
    }
}