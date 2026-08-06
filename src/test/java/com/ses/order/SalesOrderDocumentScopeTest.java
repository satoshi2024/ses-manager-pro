package com.ses.order;

import com.ses.common.exception.BusinessException;
import com.ses.entity.SalesOrder;
import com.ses.mapper.SalesOrderMapper;
import com.ses.service.DocumentService;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.impl.FileScopeValidationService;
import com.ses.service.impl.DocumentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * T056定向テスト: 注文文書（ORDER_RECEIVED/ORDER_ACKNOWLEDGEMENT）のACLは
 * 注文一覧と同じscope（顧客DataScope）を通す（design §5.2 / archive spec §6.2）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class SalesOrderDocumentScopeTest {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired SalesOrderMapper salesOrderMapper;
    @Autowired com.ses.mapper.DocumentMapper documentMapper;
    @Autowired DocumentService documentService;
    @Autowired FileScopeValidationService fileScopeValidationService;

    @MockBean DataScopeService dataScopeService;

    private long allowedCustomerId;
    private long otherCustomerId;
    private long allowedOrderId;
    private long otherOrderId;
    private long allowedDocId;
    private long otherDocId;
    private String allowedStorageKey;
    private String otherStorageKey;

    @BeforeEach
    void setUp() {
        String suffix = "-" + System.nanoTime();
        allowedCustomerId = newCustomer("ACL許可顧客" + suffix);
        otherCustomerId = newCustomer("ACL他顧客" + suffix);

        allowedOrderId = newOrder("O-ACL-OK-" + suffix, allowedCustomerId);
        otherOrderId = newOrder("O-ACL-NG-" + suffix, otherCustomerId);

        allowedDocId = newDocument("O-ACL-OK-" + suffix, allowedOrderId);
        otherDocId = newDocument("O-ACL-NG-" + suffix, otherOrderId);
        allowedStorageKey = "acl-storage-" + suffix + "-ok";
        otherStorageKey = "acl-storage-" + suffix + "-ng";
        addVersion(allowedDocId, allowedOrderId, allowedStorageKey);
        addVersion(otherDocId, otherOrderId, otherStorageKey);

        when(dataScopeService.isScoped()).thenReturn(true);
        when(dataScopeService.allowedCustomerIds()).thenReturn(Set.of(allowedCustomerId));
        doThrow(new BusinessException(404, "error.scope.notFound"))
                .when(dataScopeService).assertAllowedCustomer(otherCustomerId);

        // FileScopeValidationService が文書DL時に document-archive menuを要求するため、
        // テスト用に営業へ付与する（共有H2 replayにはmenu seedが無い）。
        jdbcTemplate.update("INSERT IGNORE INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)"
                + " VALUES ('document-archive', '法定文書保存', '/document', '/api/documents', 90)");
        jdbcTemplate.update("INSERT IGNORE INTO t_role_menu (role, menu_id)"
                + " SELECT '営業', id FROM m_menu WHERE menu_key = 'document-archive'");
    }

    private long newCustomer(String name) {
        jdbcTemplate.update("INSERT INTO m_customer (company_name, trust_level, deleted_flag) VALUES (?, 'B', 0)", name);
        return jdbcTemplate.queryForObject("SELECT id FROM m_customer WHERE company_name = ?", Long.class, name);
    }

    private long newOrder(String orderNo, long customerId) {
        SalesOrder order = new SalesOrder();
        order.setOrderNo(orderNo);
        order.setCustomerId(customerId);
        order.setOrderDate(LocalDate.of(2026, 8, 5));
        order.setStatus("下書き");
        salesOrderMapper.insert(order);
        return order.getId();
    }

    private long newDocument(String documentNo, long orderId) {
        jdbcTemplate.update(
                "INSERT INTO t_document (tenant_id, document_type, document_no, direction, status, deleted_flag)"
                        + " VALUES ('default', 'ORDER_RECEIVED', ?, 'INCOMING', 'CONFIRMED', 0)",
                documentNo);
        long docId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_document WHERE document_no = ?", Long.class, documentNo);
        jdbcTemplate.update(
                "INSERT INTO t_document_link (document_id, target_type, target_id, deleted_flag)"
                        + " VALUES (?, 'SALES_ORDER', ?, 0)",
                docId, orderId);
        return docId;
    }

    private void addVersion(long docId, long orderId, String storageKey) {
        jdbcTemplate.update(
                "INSERT INTO t_document_version (tenant_id, document_id, version_no, storage_key, original_name,"
                        + " sha256, source_type, business_key, version_discriminator, scan_status, created_by)"
                        + " VALUES ('default', ?, 1, ?, 'order.pdf', '" + "a".repeat(64)
                        + "', 'RECEIVED', 'ORDER_RECEIVED:" + orderId + "', '1', 'CLEAN', 1)",
                docId, storageKey);
    }

    private com.ses.entity.Document doc(long id) {
        com.ses.entity.Document d = new com.ses.entity.Document();
        d.setId(id);
        return d;
    }

    private void login(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user", "x",
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test
    @DisplayName("許可顧客の注文に紐づく文書は可視、他顧客の注文に紐づく文書は403")
    void documentAccessFollowsOrderScope() {
        login("営業");
        DocumentServiceImpl impl = (DocumentServiceImpl) documentService;
        assertDoesNotThrow(() -> impl.assertDocumentAccessAllowed(doc(allowedDocId)));
        assertThrows(BusinessException.class, () -> impl.assertDocumentAccessAllowed(doc(otherDocId)));
    }

    @Test
    @DisplayName("FileScopeValidationServiceもSALES_ORDERリンクで顧客scopeを適用する")
    void fileDownloadFollowsOrderScope() {
        login("営業");
        assertDoesNotThrow(() -> fileScopeValidationService.assertDownloadAllowed(allowedStorageKey));
        assertThrows(BusinessException.class, () -> fileScopeValidationService.assertDownloadAllowed(otherStorageKey));
    }

    @Test
    @DisplayName("applyDataScopeFilterはSALES_ORDERリンクを許可顧客から導出した注文IDで絞る")
    void dataScopeFilterIncludesSalesOrderLinks() {
        login("営業");
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.ses.entity.Document> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        ((DocumentServiceImpl) documentService).applyDataScopeFilter(wrapper);
        // wrapperを実SQLとしてDocumentMapperへ渡し、許可顧客の注文に紐づく文書1件だけが
        // 母集団に残ること（他顧客の文書は除外）を検証する
        Long count = documentMapper.selectCount(wrapper);
        assertEquals(1L, count, "applyDataScopeFilterで許可顧客の注文文書だけが残るべき");
        // 全文書は2件（許可1 + 他顧客1）であり、他顧客分はscope条件で除外されている
        assertEquals(2L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_document WHERE deleted_flag = 0", Long.class));
    }
}
