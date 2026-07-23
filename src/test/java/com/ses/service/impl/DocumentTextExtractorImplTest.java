package com.ses.service.impl;

import com.ses.config.UploadProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class DocumentTextExtractorImplTest {

    @Mock
    private UploadProperties uploadProperties;

    @InjectMocks
    private DocumentTextExtractorImpl extractor;

    @Test
    void extract_fileNotFound_returnsEmpty() {
        when(uploadProperties.getBasePath()).thenReturn("target/missing");
        String text = extractor.extract("missing.pdf", "pdf");
        assertEquals("", text);
    }
}
