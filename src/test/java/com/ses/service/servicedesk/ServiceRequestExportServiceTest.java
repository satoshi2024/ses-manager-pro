package com.ses.service.servicedesk;

import com.ses.dto.servicedesk.ServiceRequestCreateRequest;
import com.ses.entity.Customer;
import com.ses.mapper.CustomerMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ServiceRequestExportServiceTest {

    @Autowired
    private ServiceRequestExportService exportService;

    @Autowired
    private ServiceRequestService serviceRequestService;

    @Autowired
    private CustomerMapper customerMapper;

    private Customer testCustomer;

    @BeforeEach
    void setUp() {
        testCustomer = Customer.builder()
                .companyName("CSVテスト顧客-" + UUID.randomUUID().toString().substring(0, 6))
                .build();
        customerMapper.insert(testCustomer);

        ServiceRequestCreateRequest req = ServiceRequestCreateRequest.builder()
                .customerId(testCustomer.getId())
                .category("BILLING")
                .priority("P2")
                .subject("CSVエクスポート検証問い合わせ")
                .description("請求書送付先変更の依頼")
                .build();
        serviceRequestService.createRequest(req, 100L, false, null);
    }

    @Test
    @DisplayName("CSVエクスポートがUTF-8 BOM付きで出力されヘッダーとデータが含まれること")
    void testExportCsv_withBomAndHeaders() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        exportService.exportRequestsToCsv(baos, null, null, null, null, testCustomer.getId());

        byte[] bytes = baos.toByteArray();
        assertTrue(bytes.length > 3, "データが出力されていること");

        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        assertArrayEquals(bom, new byte[]{bytes[0], bytes[1], bytes[2]}, "先頭にUTF-8 BOMが付与されていること");

        String csvText = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertTrue(csvText.contains("リクエスト番号") && csvText.contains("顧客名") && csvText.contains("カテゴリ"),
                "ヘッダー行が含まれていること");
        assertTrue(csvText.contains("CSVエクスポート検証問い合わせ"), "作成した問い合わせデータが含まれていること");
        assertTrue(csvText.contains(testCustomer.getCompanyName()), "顧客名が含まれていること");
    }
}
