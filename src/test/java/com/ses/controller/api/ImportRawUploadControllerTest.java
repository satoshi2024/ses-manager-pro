package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.importcenter.metadata.ImportCsvIngestionService;
import com.ses.importcenter.metadata.ImportMetadataService;
import com.ses.importcenter.metadata.ImportUploadJobDetail;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImportRawUploadControllerTest {
    @Test
    void invalidEncodingIsRejectedWithoutOpeningBody() {
        ImportCsvIngestionService ingestion = mock(ImportCsvIngestionService.class);
        ImportCenterApiController controller = new ImportCenterApiController(ingestion, mock(ImportMetadataService.class));
        TrackingRequest request = new TrackingRequest();
        request.addHeader("X-Import-Source-Code", "synthetic-a");
        request.addHeader("X-Import-Encoding", "UTF-16");

        assertThrows(BusinessException.class, () -> controller.upload(request));

        assertFalse(request.bodyOpened.get());
        verify(ingestion, never()).upload(any(), any());
    }

    @Test
    void controllerPassesLazyBodySupplierAndReadsOnlyWhenServiceOpensIt() throws Exception {
        ImportCsvIngestionService ingestion = mock(ImportCsvIngestionService.class);
        ImportCenterApiController controller = new ImportCenterApiController(ingestion, mock(ImportMetadataService.class));
        TrackingRequest request = new TrackingRequest();
        request.addHeader("X-Import-Source-Code", "synthetic-a");
        request.addHeader("X-Import-Encoding", "UTF8");
        request.body = new byte[]{65};
        AtomicBoolean supplierCaptured = new AtomicBoolean();
        when(ingestion.upload(any(), any())).thenAnswer(invocation -> {
            ImportCsvIngestionService.InputStreamSupplier supplier = invocation.getArgument(1);
            supplierCaptured.set(true);
            assertFalse(request.bodyOpened.get());
            supplier.open().close();
            assertTrue(request.bodyOpened.get());
            return new ImportUploadJobDetail(1, "UPLOADED", "CUSTOMER", "0123456789ab", 1, "UTF8");
        });

        controller.upload(request);

        assertTrue(supplierCaptured.get());
        verify(ingestion).upload(any(), any());
    }

    private static final class TrackingRequest extends MockHttpServletRequest {
        private final AtomicBoolean bodyOpened = new AtomicBoolean();
        private byte[] body = new byte[0];

        @Override public ServletInputStream getInputStream() throws IOException {
            bodyOpened.set(true);
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(jakarta.servlet.ReadListener listener) { }
            };
        }
    }
}
