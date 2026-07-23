package com.ses.common.util;

import com.lowagie.text.pdf.BaseFont;
import com.ses.common.exception.BusinessException;
import com.ses.config.PdfProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class PdfFontUtils {

    private final PdfProperties pdfProperties;
    private BaseFont cachedBaseFont;

    public PdfFontUtils(PdfProperties pdfProperties) {
        this.pdfProperties = pdfProperties;
    }

    public synchronized BaseFont resolveCjkFont() {
        if (cachedBaseFont != null) {
            return cachedBaseFont;
        }
        try {
            // First try the bundled font
            byte[] fontBytes = org.springframework.util.StreamUtils.copyToByteArray(
                getClass().getClassLoader().getResourceAsStream("fonts/ipaexg.ttf")
            );
            cachedBaseFont = BaseFont.createFont("ipaexg.ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, fontBytes, null);
            return cachedBaseFont;
        } catch (Exception ex) {
            log.warn("Bundled font load failed, falling back to system fonts", ex);
        }

        List<String> candidates = new ArrayList<>();
        if (StringUtils.hasText(pdfProperties.getFontPath())) {
            candidates.add(pdfProperties.getFontPath());
        }
        candidates.addAll(pdfProperties.getDefaultFontCandidates());

        for (String candidate : candidates) {
            String filePath = candidate.contains(",") ? candidate.substring(0, candidate.indexOf(',')) : candidate;
            if (!Files.exists(Paths.get(filePath))) {
                continue;
            }
            try {
                cachedBaseFont = BaseFont.createFont(candidate, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
                return cachedBaseFont;
            } catch (Exception e) {
                log.warn("CJKフォントの読み込みに失敗しました: {}", candidate, e);
            }
        }
        throw new BusinessException(
                "PDF生成用の日本語フォントが見つかりません。app.pdf.font-path でフォントファイルのパスを指定してください。");
    }
}
