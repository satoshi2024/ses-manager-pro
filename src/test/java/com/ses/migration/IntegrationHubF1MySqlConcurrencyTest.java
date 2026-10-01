package com.ses.migration;

import com.ses.config.integrationhub.ExternalApiPrincipal;
import com.ses.config.integrationhub.ExternalApiPublicIdCodec;
import com.ses.common.result.ApiResult;
import com.ses.controller.api.CustomerApiController;
import com.ses.controller.api.EngineerApiController;
import com.ses.dto.customer.CustomerSaveDto;
import com.ses.dto.engineer.EngineerSaveDto;
import com.ses.dto.project.ProjectSaveDto;
import com.ses.entity.Customer;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.entity.Proposal;
import com.ses.entity.Contract;
import com.ses.entity.integrationhub.ApiDelivery;
import com.ses.mapper.ApiDeliveryMapper;
import com.ses.mapper.InboundEventMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.ExternalApiReadMapper;
import com.ses.service.integrationhub.ApiDeliveryService;
import com.ses.service.integrationhub.ApiRetentionPurgeService;
import com.ses.service.integrationhub.ApiUsageBucketService;
import com.ses.service.integrationhub.ExternalDtoSnapshot;
import com.ses.service.integrationhub.InboundEventService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.ContractService;
import com.ses.service.CustomerService;
import com.ses.service.EngineerService;
import com.ses.service.InvoiceService;
import com.ses.service.ProjectService;
import com.ses.test.MySQLContainer;
import com.ses.test.TenantTestSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NF-05 F1: MySQL row lock、delivery CAS、active lease purge predicateの実証。 */
@Tag("mysql")
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class IntegrationHubF1MySqlConcurrencyTest {
    private static final String CLIENT_ID = "f1-mysql-concurrency-client";
    private static final String SCOPE = "integration.project.read";
    private static final String TENANT_ID = "f1-tenant";
    private static final String ROUTE = "/external-api/v1/projects";
    private static final String INBOUND_PROVIDER = "provider-a";
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 30, 12, 0);

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_f1_concurrency")
            .withUsername("root")
            .withPassword("ses");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @Autowired
    private ApiUsageBucketService usageBucketService;
    @Autowired
    private ApiDeliveryService deliveryService;
    @Autowired
    private ApiDeliveryMapper deliveryMapper;
    @Autowired
    private ApiRetentionPurgeService retentionPurgeService;
    @Autowired
    private InboundEventService inboundEventService;
    @Autowired
    private InboundEventMapper inboundEventMapper;
    @Autowired
    private ExternalApiPublicIdCodec publicIdCodec;
    @Autowired
    private ExternalApiReadMapper externalApiReadMapper;
    @Autowired
    private CustomerApiController customerApiController;
    @Autowired
    private EngineerApiController engineerApiController;
    @Autowired
    private CustomerMapper customerMapper;
    @Autowired
    private EngineerMapper engineerMapper;
    @Autowired
    private ProjectMapper projectMapper;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ContractService contractService;
    @Autowired
    private InvoiceService invoiceService;

    private long clientDatabaseId;

    @BeforeEach
    void bindTenant() {
        AccountingTenantContextHolder.setTenantId(TENANT_ID);
    }

    @AfterEach
    void cleanup() throws Exception {
        try (Connection connection = MYSQL.createConnection("")) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM t_api_delivery_replay_audit WHERE event_id LIKE 'f1-%'");
                statement.executeUpdate("DELETE FROM t_api_delivery WHERE client_id = '" + CLIENT_ID + "'");
                statement.executeUpdate("DELETE FROM m_webhook_subscription WHERE client_id = '" + CLIENT_ID + "'");
                statement.executeUpdate("DELETE FROM t_api_usage_bucket WHERE client_id = '" + CLIENT_ID + "'");
                statement.executeUpdate("DELETE FROM t_inbound_event WHERE client_id = '" + CLIENT_ID + "'");
                statement.executeUpdate("DELETE FROM m_api_client_scope WHERE api_client_id IN "
                        + "(SELECT id FROM m_api_client WHERE client_id = '" + CLIENT_ID + "')");
                statement.executeUpdate("DELETE FROM m_api_client WHERE client_id = '" + CLIENT_ID + "'");
                statement.executeUpdate("DELETE FROM t_api_retention_hold");
                statement.executeUpdate("DELETE FROM t_api_purge_checkpoint");
            }
        } finally {
            TenantTestSecurity.clear();
        }
    }

    @Test
    void usageBucketは複数connectionの同時incrementを一つのrowへ直列化する() throws Exception {
        int workers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            futures.add(executor.submit(() -> withTenant(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return usageBucketService.consumeAt(CLIENT_ID, SCOPE, TENANT_ID, ROUTE, NOW).allowed() ? 1 : 0;
            })));
        }
        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        for (java.util.concurrent.Future<Integer> future : futures) {
            assertEquals(1, future.get(30, TimeUnit.SECONDS));
        }
        executor.shutdownNow();

        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT minute_count FROM t_api_usage_bucket WHERE client_id = ?")) {
            statement.setString(1, CLIENT_ID);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                assertEquals(workers, rs.getInt(1));
            }
        }
    }

    @Test
    void externalApiReadMapperはMySQLで法人predicateをlistとcountへ適用する() throws Exception {
        long firstEngineerId = 980051L;
        long secondEngineerId = 980052L;
        long visibleLegalEntityId = 7001L;
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO t_engineer "
                             + "(id, legal_entity_id, full_name, employment_type, status, available_date, deleted_flag) "
                             + "VALUES (?, ?, ?, '正社員', 'Bench', ?, 0)")) {
            statement.setLong(1, firstEngineerId);
            statement.setLong(2, visibleLegalEntityId);
            statement.setString(3, "F1 MySQL visible engineer");
            statement.setObject(4, java.sql.Date.valueOf("2026-08-01"));
            statement.executeUpdate();

            statement.setLong(1, secondEngineerId);
            statement.setLong(2, 7002L);
            statement.setString(3, "F1 MySQL isolated engineer");
            statement.setObject(4, java.sql.Date.valueOf("2026-08-01"));
            statement.executeUpdate();
        }

        try {
            List<com.ses.dto.integrationhub.ExternalApiReadRow> rows = externalApiReadMapper.selectEngineers(
                    List.of(firstEngineerId, secondEngineerId), null, 10, visibleLegalEntityId);
            assertEquals(1, rows.size());
            assertEquals(firstEngineerId, rows.get(0).getId());
            assertEquals(1, externalApiReadMapper.countEngineers(
                    List.of(firstEngineerId, secondEngineerId), visibleLegalEntityId));
            assertEquals(0, externalApiReadMapper.countEngineers(
                    List.of(firstEngineerId, secondEngineerId), 7003L));
        } finally {
            try (Connection connection = MYSQL.createConnection("");
                 PreparedStatement statement = connection.prepareStatement(
                         "DELETE FROM t_engineer WHERE id IN (?, ?)")) {
                statement.setLong(1, firstEngineerId);
                statement.setLong(2, secondEngineerId);
                statement.executeUpdate();
            }
        }
    }

    @Test
    void externalApiReadMapperはMySQLで全joinGraphの法人_deleted_NULLをlistとcountへ適用する() throws Exception {
        long customerId = 980101L;
        long hiddenCustomerId = 980102L;
        long engineerId = 980111L;
        long hiddenEngineerId = 980112L;
        long projectId = 980121L;
        long hiddenProjectId = 980122L;
        long contractId = 980131L;
        long secondContractId = 980132L;
        long wrongContractId = 980133L;
        long nullContractId = 980134L;
        long deletedContractId = 980135L;
        long workRecordId = 980141L;
        long secondWorkRecordId = 980142L;
        long wrongWorkRecordId = 980143L;
        long invoiceId = 980151L;
        long crossLegalInvoiceId = 980152L;
        long deletedInvoiceId = 980153L;
        long nullInvoiceId = 980154L;
        try (Connection connection = MYSQL.createConnection("")) {
            try (Statement cleanup = connection.createStatement()) {
                cleanup.executeUpdate("DELETE FROM t_invoice_item WHERE invoice_id IN (980151,980152,980153,980154)");
                cleanup.executeUpdate("DELETE FROM t_invoice WHERE id IN (980151,980152,980153,980154)");
                cleanup.executeUpdate("DELETE FROM t_work_record WHERE id IN (980141,980142,980143)");
                cleanup.executeUpdate("DELETE FROM t_contract WHERE id IN (980131,980132,980133,980134,980135)");
                cleanup.executeUpdate("DELETE FROM t_project WHERE id IN (980121,980122)");
                cleanup.executeUpdate("DELETE FROM t_engineer WHERE id IN (980111,980112)");
                cleanup.executeUpdate("DELETE FROM m_customer WHERE id IN (980101,980102)");
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO m_customer (id, legal_entity_id, company_name, deleted_flag) VALUES (?, ?, ?, ?);")) {
                insert.setLong(1, customerId); insert.setLong(2, 7001L); insert.setString(3, "F1 graph customer"); insert.setInt(4, 0); insert.executeUpdate();
                insert.setLong(1, hiddenCustomerId); insert.setLong(2, 7002L); insert.setString(3, "F1 hidden customer"); insert.setInt(4, 0); insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO t_engineer (id, legal_entity_id, full_name, employment_type, status, available_date, deleted_flag) VALUES (?, ?, ?, '正社員', 'Bench', '2026-08-01', ?)")) {
                insert.setLong(1, engineerId); insert.setLong(2, 7001L); insert.setString(3, "F1 graph engineer"); insert.setInt(4, 0); insert.executeUpdate();
                insert.setLong(1, hiddenEngineerId); insert.setLong(2, 7002L); insert.setString(3, "F1 deleted graph engineer"); insert.setInt(4, 1); insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO t_project (id, legal_entity_id, project_name, customer_id, status, deleted_flag) VALUES (?, ?, ?, ?, '募集中', ?)")) {
                insert.setLong(1, projectId); insert.setLong(2, 7001L); insert.setString(3, "F1 graph project"); insert.setLong(4, customerId); insert.setInt(5, 0); insert.executeUpdate();
                insert.setLong(1, hiddenProjectId); insert.setLong(2, 7001L); insert.setString(3, "F1 deleted graph project"); insert.setLong(4, customerId); insert.setInt(5, 1); insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO t_contract (id, legal_entity_id, contract_no, engineer_id, project_id, customer_id, contract_type, start_date, selling_price, cost_price, status, deleted_flag) VALUES (?, ?, ?, ?, ?, ?, '準委任', '2026-08-01', 100000, 50000, '稼動中', ?)")) {
                insert.setLong(1, contractId); insert.setLong(2, 7001L); insert.setString(3, "F1-C-001"); insert.setLong(4, engineerId); insert.setLong(5, projectId); insert.setLong(6, customerId); insert.setInt(7, 0); insert.executeUpdate();
                insert.setLong(1, secondContractId); insert.setLong(2, 7001L); insert.setString(3, "F1-C-002"); insert.setLong(4, engineerId); insert.setLong(5, projectId); insert.setLong(6, customerId); insert.setInt(7, 0); insert.executeUpdate();
                insert.setLong(1, wrongContractId); insert.setLong(2, 7002L); insert.setString(3, "F1-C-003"); insert.setLong(4, engineerId); insert.setLong(5, projectId); insert.setLong(6, customerId); insert.setInt(7, 0); insert.executeUpdate();
                insert.setLong(1, nullContractId); insert.setObject(2, null); insert.setString(3, "F1-C-004"); insert.setLong(4, engineerId); insert.setLong(5, projectId); insert.setLong(6, customerId); insert.setInt(7, 0); insert.executeUpdate();
                insert.setLong(1, deletedContractId); insert.setLong(2, 7001L); insert.setString(3, "F1-C-005"); insert.setLong(4, hiddenEngineerId); insert.setLong(5, hiddenProjectId); insert.setLong(6, customerId); insert.setInt(7, 1); insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO t_work_record (id, contract_id, work_month, actual_hours, billing_amount, status) VALUES (?, ?, '2026-08', 160.0, 100000, '確定')")) {
                insert.setLong(1, workRecordId); insert.setLong(2, contractId); insert.executeUpdate();
                insert.setLong(1, secondWorkRecordId); insert.setLong(2, secondContractId); insert.executeUpdate();
                insert.setLong(1, wrongWorkRecordId); insert.setLong(2, wrongContractId); insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO t_invoice (id, invoice_no, legal_entity_id, customer_id, billing_month, subtotal, tax, total, status, issued_date, deleted_flag) VALUES (?, ?, ?, ?, '2026-08', 200000, 20000, 220000, '未送付', '2026-08-31', ?)")) {
                insert.setLong(1, invoiceId); insert.setString(2, "F1-I-001"); insert.setLong(3, 7001L); insert.setLong(4, customerId); insert.setInt(5, 0); insert.executeUpdate();
                insert.setLong(1, crossLegalInvoiceId); insert.setString(2, "F1-I-002"); insert.setLong(3, 7001L); insert.setLong(4, customerId); insert.setInt(5, 0); insert.executeUpdate();
                insert.setLong(1, deletedInvoiceId); insert.setString(2, "F1-I-003"); insert.setLong(3, 7001L); insert.setLong(4, customerId); insert.setInt(5, 1); insert.executeUpdate();
                insert.setLong(1, nullInvoiceId); insert.setString(2, "F1-I-004"); insert.setObject(3, null); insert.setLong(4, customerId); insert.setInt(5, 0); insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO t_invoice_item (invoice_id, work_record_id, description, amount) VALUES (?, ?, 'F1 graph item', 100000)")) {
                insert.setLong(1, invoiceId); insert.setLong(2, workRecordId); insert.executeUpdate();
                insert.setLong(1, invoiceId); insert.setLong(2, secondWorkRecordId); insert.executeUpdate();
                insert.setLong(1, crossLegalInvoiceId); insert.setLong(2, wrongWorkRecordId); insert.executeUpdate();
            }
        }

        try {
            assertEquals(1, externalApiReadMapper.selectEngineers(
                    List.of(engineerId, hiddenEngineerId), null, 10, 7001L).size());
            assertEquals(1, externalApiReadMapper.countEngineers(List.of(engineerId, hiddenEngineerId), 7001L));
            assertEquals(0, externalApiReadMapper.countEngineers(List.of(engineerId), null));

            assertEquals(1, externalApiReadMapper.selectProjects(
                    List.of(projectId, hiddenProjectId), List.of(customerId), null, 10, 7001L).size());
            assertEquals(1, externalApiReadMapper.countProjects(
                    List.of(projectId, hiddenProjectId), List.of(customerId), 7001L));

            List<com.ses.dto.integrationhub.ExternalApiReadRow> contracts = externalApiReadMapper.selectContracts(
                    List.of(contractId, secondContractId, wrongContractId, nullContractId, deletedContractId),
                    List.of(projectId), null, 10, 7001L);
            assertEquals(2, contracts.size());
            assertEquals(2, externalApiReadMapper.countContracts(
                    List.of(contractId, secondContractId, wrongContractId, nullContractId, deletedContractId),
                    List.of(projectId), 7001L));

            List<com.ses.dto.integrationhub.ExternalApiReadRow> invoices = externalApiReadMapper.selectInvoices(
                    List.of(invoiceId, crossLegalInvoiceId, deletedInvoiceId, nullInvoiceId), null,
                    List.of(customerId), null, 10, 7001L);
            assertEquals(1, invoices.size());
            assertEquals(2L, invoices.get(0).getContractCount());
            assertNull(invoices.get(0).getContractId(), "複数contract invoiceは単一contract public IDを返さないこと");
            assertEquals(1, externalApiReadMapper.countInvoices(
                    List.of(invoiceId, crossLegalInvoiceId, deletedInvoiceId, nullInvoiceId), null,
                    List.of(customerId), 7001L));
        } finally {
            try (Connection connection = MYSQL.createConnection(""); Statement cleanup = connection.createStatement()) {
                cleanup.executeUpdate("DELETE FROM t_invoice_item WHERE invoice_id IN (980151,980152,980153,980154)");
                cleanup.executeUpdate("DELETE FROM t_invoice WHERE id IN (980151,980152,980153,980154)");
                cleanup.executeUpdate("DELETE FROM t_work_record WHERE id IN (980141,980142,980143)");
                cleanup.executeUpdate("DELETE FROM t_contract WHERE id IN (980131,980132,980133,980134,980135)");
                cleanup.executeUpdate("DELETE FROM t_project WHERE id IN (980121,980122)");
                cleanup.executeUpdate("DELETE FROM t_engineer WHERE id IN (980111,980112)");
                cleanup.executeUpdate("DELETE FROM m_customer WHERE id IN (980101,980102)");
            }
        }
    }

    @Test
    void 法人境界はMySQLのcustomer_engineer_project_proposalContract_invoice書込みを束縛する() throws Exception {
        final long organizationId = 982011L;
        final long legalEntityId = 8801L;
        final long proposalId = 982021L;
        final String customerName = "NF05 MySQL write customer";
        final String engineerName = "NF05 MySQL write engineer";
        final String projectName = "NF05 MySQL write project";
        Long customerId = null;
        Long engineerId = null;
        Long projectId = null;
        Long contractId = null;
        Long invoiceId = null;

        installLegalEntitySecurityBinding(organizationId, legalEntityId);
        try {
            CustomerSaveDto customerRequest = new CustomerSaveDto();
            customerRequest.setCompanyName(customerName);
            customerRequest.setContactEmail("nf05-write@example.test");
            ApiResult<Boolean> customerSaved = customerApiController.save(customerRequest);
            assertEquals(200, customerSaved.getCode());
            customerId = selectId("m_customer", "company_name", customerName);
            assertEquals(legalEntityId, selectLong("m_customer", "legal_entity_id", customerId));

            Customer customer = customerMapper.selectById(customerId);
            CustomerSaveDto customerUpdate = new CustomerSaveDto();
            customerUpdate.setCompanyName(customerName + " updated");
            customerUpdate.setDeliveryPreference("PDF");
            customerUpdate.setVersion(customer.getVersion());
            assertEquals(200, customerApiController.update(customerId, customerUpdate).getCode());
            assertEquals(legalEntityId, selectLong("m_customer", "legal_entity_id", customerId));

            EngineerSaveDto engineerRequest = new EngineerSaveDto();
            engineerRequest.setFullName(engineerName);
            engineerRequest.setEmploymentType("正社員");
            engineerRequest.setStatus("Bench");
            ApiResult<Engineer> engineerSaved = engineerApiController.save(engineerRequest);
            assertEquals(200, engineerSaved.getCode());
            engineerId = selectId("t_engineer", "full_name", engineerName);
            assertEquals(legalEntityId, selectLong("t_engineer", "legal_entity_id", engineerId));

            Engineer engineer = engineerMapper.selectById(engineerId);
            EngineerSaveDto engineerUpdate = new EngineerSaveDto();
            engineerUpdate.setFullName(engineerName + " updated");
            engineerUpdate.setEmploymentType("正社員");
            engineerUpdate.setStatus("Bench");
            engineerUpdate.setVersion(engineer.getVersion());
            assertEquals(200, engineerApiController.update(engineerId, engineerUpdate).getCode());
            assertEquals(legalEntityId, selectLong("t_engineer", "legal_entity_id", engineerId));

            ProjectSaveDto projectRequest = new ProjectSaveDto();
            projectRequest.setProjectName(projectName);
            projectRequest.setCustomerId(customerId);
            projectRequest.setStatus("募集中");
            projectService.saveProjectWithSkills(projectRequest);
            projectId = projectRequest.getId();
            assertEquals(legalEntityId, selectLong("t_project", "legal_entity_id", projectId));

            projectRequest.setProjectName(projectName + " updated");
            assertTrue(projectService.updateProjectWithSkills(projectRequest));
            assertEquals(legalEntityId, selectLong("t_project", "legal_entity_id", projectId));

            try (Connection connection = MYSQL.createConnection("")) {
                try (PreparedStatement proposal = connection.prepareStatement(
                        "INSERT INTO t_proposal (id, engineer_id, project_id, proposed_unit_price, status, proposed_by, deleted_flag) "
                                + "VALUES (?, ?, ?, 100000, '成約', 1, 0)")) {
                    proposal.setLong(1, proposalId);
                    proposal.setLong(2, engineerId);
                    proposal.setLong(3, projectId);
                    proposal.executeUpdate();
                }
            }

            Proposal source = new Proposal();
            source.setId(proposalId);
            source.setEngineerId(engineerId);
            source.setProjectId(projectId);
            source.setProposedUnitPrice(new java.math.BigDecimal("100000"));
            Contract draft = contractService.createDraftFromProposal(source);
            contractId = draft.getId();
            assertEquals(legalEntityId, draft.getLegalEntityId());
            assertEquals(legalEntityId, selectLong("t_contract", "legal_entity_id", contractId));

            try (Connection connection = MYSQL.createConnection("")) {
                try (PreparedStatement work = connection.prepareStatement(
                        "INSERT INTO t_work_record (id, contract_id, work_month, actual_hours, billing_amount, status) "
                                + "VALUES (982031, ?, '2026-08', 160.0, 100000, '確定')")) {
                    work.setLong(1, contractId);
                    work.executeUpdate();
                }
                try (PreparedStatement acceptance = connection.prepareStatement(
                        "INSERT INTO t_acceptance (contract_id, work_record_id, work_month, status, version, deleted_flag) "
                                + "VALUES (?, 982031, '2026-08', '検収済', 0, 0)")) {
                    acceptance.setLong(1, contractId);
                    acceptance.executeUpdate();
                }
            }

            var invoice = invoiceService.generate(customerId, "2026-08");
            invoiceId = invoice.getId();
            assertEquals(legalEntityId, invoice.getLegalEntityId());
            assertEquals(legalEntityId, selectLong("t_invoice", "legal_entity_id", invoiceId));

            // 通常の請求書更新（ステータス変更）も保存済み法人を再検証する。
            invoiceService.changeStatus(invoiceId, "送付済", null);
            assertEquals(legalEntityId, selectLong("t_invoice", "legal_entity_id", invoiceId));
        } finally {
            try {
                deleteLegalEntityWriteFixture(customerName, engineerName, projectName, proposalId, organizationId);
            } finally {
                TenantTestSecurity.clear();
                AccountingTenantContextHolder.setTenantId(TENANT_ID);
            }
        }
    }

    private void installLegalEntitySecurityBinding(long organizationId, long legalEntityId) throws Exception {
        try (Connection connection = MYSQL.createConnection("")) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM t_user_organization WHERE organization_id = " + organizationId);
                statement.executeUpdate("DELETE FROM m_organization_unit WHERE id = " + organizationId);
            }
            try (PreparedStatement organization = connection.prepareStatement(
                    "INSERT INTO m_organization_unit (id, legal_entity_id, code, name, type, valid_from, status, version, deleted_flag) "
                            + "VALUES (?, ?, 'NF05-WRITE', 'NF05法人境界テスト組織', '事業部', '2026-01-01', '有効', 0, 0)")) {
                organization.setLong(1, organizationId);
                organization.setLong(2, legalEntityId);
                organization.executeUpdate();
            }
            try (PreparedStatement membership = connection.prepareStatement(
                    "INSERT INTO t_user_organization (user_id, organization_id, primary_flag, valid_from, version, deleted_flag) "
                            + "VALUES (1, ?, 1, '2026-01-01', 0, 0)")) {
                membership.setLong(1, organizationId);
                membership.executeUpdate();
            }
        }
        // 法人境界の書込みだけdefault tenantの営業主体として実行する。
        TenantTestSecurity.bindAs(1L, "1", "default", "営業");
    }

    private Long selectId(String table, String column, String value) throws Exception {
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id FROM " + table + " WHERE " + column + " = ? AND deleted_flag = 0 ORDER BY id DESC LIMIT 1")) {
            statement.setString(1, value);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    private Long selectLong(String table, String column, Long id) throws Exception {
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT " + column + " FROM " + table + " WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                long value = result.getLong(1);
                return result.wasNull() ? null : value;
            }
        }
    }

    private void deleteLegalEntityWriteFixture(String customerName, String engineerName,
                                                String projectName, long proposalId, long organizationId)
            throws Exception {
        try (Connection connection = MYSQL.createConnection("")) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM t_invoice_item WHERE invoice_id IN (SELECT id FROM t_invoice WHERE customer_id IN "
                            + "(SELECT id FROM m_customer WHERE company_name LIKE ?))")) {
                statement.setString(1, customerName + "%");
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM t_invoice WHERE customer_id IN (SELECT id FROM m_customer WHERE company_name LIKE ?)")) {
                statement.setString(1, customerName + "%");
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM t_acceptance WHERE contract_id IN (SELECT id FROM t_contract WHERE proposal_id = ?)")) {
                statement.setLong(1, proposalId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM t_work_record WHERE contract_id IN (SELECT id FROM t_contract WHERE proposal_id = ?)")) {
                statement.setLong(1, proposalId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM t_contract WHERE proposal_id = ?")) {
                statement.setLong(1, proposalId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM t_proposal WHERE id = ?")) {
                statement.setLong(1, proposalId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM t_project WHERE project_name LIKE ?")) {
                statement.setString(1, projectName + "%");
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM t_engineer_accounting_history WHERE engineer_id IN "
                            + "(SELECT id FROM t_engineer WHERE full_name LIKE ?)")) {
                statement.setString(1, engineerName + "%");
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM t_engineer_sales WHERE engineer_id IN "
                            + "(SELECT id FROM t_engineer WHERE full_name LIKE ?)")) {
                statement.setString(1, engineerName + "%");
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM t_engineer WHERE full_name LIKE ?")) {
                statement.setString(1, engineerName + "%");
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM m_customer WHERE company_name LIKE ?")) {
                statement.setString(1, customerName + "%");
                statement.executeUpdate();
            }
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM t_user_organization WHERE organization_id = " + organizationId);
                statement.executeUpdate("DELETE FROM m_organization_unit WHERE id = " + organizationId);
            }
        }
    }

    @Test
    void deliveryCASはproviderKey_payloadHash_version_lease_generationを同時に要求する() throws Exception {
        long subscriptionId;
        try (Connection connection = MYSQL.createConnection("")) {
            subscriptionId = insertSubscription(connection);
        }

        ApiDelivery enqueued = deliveryService.enqueue("f1-delivery-cas", subscriptionId, 1, CLIENT_ID, SCOPE,
                TENANT_ID, "project", 1L, "resource.changed", "v1", "correlation-f1-000001",
                ExternalDtoSnapshot.of(outboundSnapshot("f1-delivery-cas", "correlation-f1-000001")), NOW);
        ApiDelivery claimed = deliveryService.claim(enqueued.getId(), "lease-1", NOW, NOW.plusMinutes(5));
        assertEquals("CLAIMED", claimed.getStatus());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(executor.submit(() -> withTenant(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return deliveryService.markSucceeded(claimed.getId(), claimed.getVersion(),
                        claimed.getDeliveryGeneration(), claimed.getLeaseToken(), claimed.getProviderIdempotencyKey(),
                        claimed.getPayloadHash(), "provider-request-1", NOW);
            })));
        }
        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        int succeeded = 0;
        for (java.util.concurrent.Future<Boolean> future : futures) {
            if (future.get(30, TimeUnit.SECONDS)) {
                succeeded++;
            }
        }
        executor.shutdownNow();
        assertEquals(1, succeeded, "同じlease/version/generationのresult CASは一つだけ成功すること");
        assertEquals("SUCCEEDED", deliveryMapper.selectById(enqueued.getId()).getStatus());
    }

    @Test
    void timeout_5xx_attempt8とprovider成功直後crashは実DBのretry_DLQ_recoveryへ収束する() throws Exception {
        long subscriptionId;
        try (Connection connection = MYSQL.createConnection("")) {
            subscriptionId = insertSubscription(connection);
        }

        ApiDelivery timeout = deliveryService.enqueue("f1-delivery-timeout", subscriptionId, 1, CLIENT_ID, SCOPE,
                TENANT_ID, "project", 1L, "resource.changed", "v1", "correlation-timeout-000001",
                ExternalDtoSnapshot.of(outboundSnapshot("f1-delivery-timeout", "correlation-timeout-000001")), NOW);
        ApiDelivery timeoutClaimed = deliveryService.claim(timeout.getId(), "lease-timeout", NOW,
                NOW.plusMinutes(5));
        assertTrue(deliveryService.markRetryable(timeoutClaimed.getId(), timeoutClaimed.getVersion(),
                timeoutClaimed.getDeliveryGeneration(), timeoutClaimed.getLeaseToken(),
                timeoutClaimed.getProviderIdempotencyKey(), timeoutClaimed.getPayloadHash(), "TRANSPORT_ERROR",
                NOW, NOW.plusSeconds(10)));
        assertEquals("RETRYABLE", deliveryMapper.selectById(timeout.getId()).getStatus());

        ApiDelivery fiveHundred = deliveryService.enqueue("f1-delivery-5xx", subscriptionId, 1, CLIENT_ID, SCOPE,
                TENANT_ID, "project", 1L, "resource.changed", "v1", "correlation-5xx-000001",
                ExternalDtoSnapshot.of(outboundSnapshot("f1-delivery-5xx", "correlation-5xx-000001")), NOW);
        ApiDelivery fiveHundredClaimed = deliveryService.claim(fiveHundred.getId(), "lease-5xx", NOW,
                NOW.plusMinutes(5));
        assertTrue(deliveryService.markRetryable(fiveHundredClaimed.getId(), fiveHundredClaimed.getVersion(),
                fiveHundredClaimed.getDeliveryGeneration(), fiveHundredClaimed.getLeaseToken(),
                fiveHundredClaimed.getProviderIdempotencyKey(), fiveHundredClaimed.getPayloadHash(), "HTTP_5XX",
                NOW, NOW.plusSeconds(20)));
        assertEquals("HTTP_5XX", deliveryMapper.selectById(fiveHundred.getId()).getLastErrorCode());

        ApiDelivery attemptEight = deliveryService.enqueue("f1-delivery-attempt-8", subscriptionId, 1, CLIENT_ID, SCOPE,
                TENANT_ID, "project", 1L, "resource.changed", "v1", "correlation-attempt-000001",
                ExternalDtoSnapshot.of(outboundSnapshot("f1-delivery-attempt-8", "correlation-attempt-000001")), NOW);
        ApiDelivery attemptEightClaimed = deliveryService.claim(attemptEight.getId(), "lease-attempt-8", NOW,
                NOW.plusMinutes(5));
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE t_api_delivery SET attempt_count = 8 WHERE id = ?")) {
            statement.setLong(1, attemptEight.getId());
            statement.executeUpdate();
        }
        assertTrue(deliveryService.markRetryable(attemptEightClaimed.getId(), attemptEightClaimed.getVersion(),
                attemptEightClaimed.getDeliveryGeneration(), attemptEightClaimed.getLeaseToken(),
                attemptEightClaimed.getProviderIdempotencyKey(), attemptEightClaimed.getPayloadHash(), "HTTP_5XX",
                NOW, NOW.plusSeconds(20)));
        assertEquals("DLQ", deliveryMapper.selectById(attemptEight.getId()).getStatus());

        ApiDelivery providerAccepted = deliveryService.enqueue("f1-delivery-provider-crash", subscriptionId, 1,
                CLIENT_ID, SCOPE, TENANT_ID, "project", 1L, "resource.changed", "v1", "correlation-crash-000001",
                ExternalDtoSnapshot.of(outboundSnapshot("f1-delivery-provider-crash", "correlation-crash-000001")), NOW);
        ApiDelivery providerClaimed = deliveryService.claim(providerAccepted.getId(), "lease-provider-crash", NOW,
                NOW.plusMinutes(5));
        // provider側では同一Idempotency-Keyを受理した直後にworkerがcrashした想定。
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE t_api_delivery SET lease_expires_at = ? WHERE id = ?")) {
            statement.setObject(1, NOW.minusSeconds(1));
            statement.setLong(2, providerAccepted.getId());
            statement.executeUpdate();
        }
        assertEquals(1, deliveryService.recoverExpiredLeases(NOW));
        ApiDelivery recovered = deliveryMapper.selectById(providerAccepted.getId());
        assertEquals("RETRYABLE", recovered.getStatus());
        assertEquals(providerClaimed.getProviderIdempotencyKey(), recovered.getProviderIdempotencyKey());
    }

    @Test
    void concurrentClaimは同一rowを一workerだけ取得する() throws Exception {
        long subscriptionId;
        try (Connection connection = MYSQL.createConnection("")) {
            subscriptionId = insertSubscription(connection);
        }
        ApiDelivery delivery = deliveryService.enqueue("f1-delivery-claim-race", subscriptionId, 1, CLIENT_ID, SCOPE,
                TENANT_ID, "project", 1L, "resource.changed", "v1", "correlation-claim-000001",
                ExternalDtoSnapshot.of(outboundSnapshot("f1-delivery-claim-race", "correlation-claim-000001")), NOW);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<ApiDelivery>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            int worker = i;
            futures.add(executor.submit(() -> withTenant(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return deliveryService.claim(delivery.getId(), "lease-race-" + worker, NOW, NOW.plusMinutes(5));
            })));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        int claimed = 0;
        for (var future : futures) {
            if (future.get(30, TimeUnit.SECONDS) != null) {
                claimed++;
            }
        }
        executor.shutdownNow();
        assertEquals(1, claimed, "同一deliveryのclaimは一workerだけ成功すること");
    }

    @Test
    void replay監査はdeliveryの90日purge後もauditの1年期限まで残る() throws Exception {
        long subscriptionId;
        try (Connection connection = MYSQL.createConnection("")) {
            subscriptionId = insertSubscription(connection);
        }
        long deliveryId;
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO t_api_delivery (event_id, subscription_id, delivery_generation, client_id, "
                            + "scope_code, tenant_id, scope_digest, event_type, schema_version, provider_idempotency_key, "
                            + "external_dto_snapshot, payload_hash, status, terminal_at, retention_class, "
                            + "retention_expires_at) VALUES ('f1-delivery-replay-retention', ?, 1, ?, ?, ?, "
                            + "?, 'resource.changed', 'v1', ?, '{\"status\":\"ok\"}', ?, 'DLQ', ?, "
                            + "'FAILED_DLQ_PAYLOAD_90D', ?)")) {
            insert.setLong(1, subscriptionId);
            insert.setString(2, CLIENT_ID);
            insert.setString(3, SCOPE);
            insert.setString(4, TENANT_ID);
            insert.setString(5, HASH);
            insert.setString(6, HASH);
            insert.setString(7, HASH);
            insert.setObject(8, NOW.minusDays(100));
            insert.setObject(9, NOW.minusDays(1));
            insert.executeUpdate();
        }
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id FROM t_api_delivery WHERE event_id = 'f1-delivery-replay-retention'")) {
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                deliveryId = rs.getLong(1);
            }
        }
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO t_api_delivery_replay_audit (delivery_id, event_id, replay_generation, "
                             + "operator_ref, reason_code, scope_digest, payload_hash, retention_class, "
                             + "retention_expires_at, created_at) VALUES (?, 'f1-delivery-replay-retention', 2, "
                             + "'operator-1', 'RECOVERY', ?, ?, 'AUDIT_METADATA_1Y', ?, ?)")) {
            insert.setLong(1, deliveryId);
            insert.setString(2, HASH);
            insert.setString(3, HASH);
            insert.setObject(4, NOW.minusDays(1));
            insert.setObject(5, NOW.minusYears(2));
            insert.executeUpdate();
        }

        assertEquals(1, retentionPurgeService.purgeExpired(
                "DELIVERY", "FAILED_DLQ_PAYLOAD_90D", NOW, 10).purged());
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM t_api_delivery_replay_audit WHERE event_id = 'f1-delivery-replay-retention'")) {
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                assertEquals(1, rs.getInt(1));
            }
        }
        assertEquals(1, retentionPurgeService.purgeExpired(
                "AUDIT", "AUDIT_METADATA_1Y", NOW, 10).purged());
    }

    @Test
    void holdとpurgeの同時処理は共通lock順序でdeadlockせずどちらか一つへ収束する() throws Exception {
        long subscriptionId;
        try (Connection connection = MYSQL.createConnection("")) {
            subscriptionId = insertSubscription(connection);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO t_api_delivery (event_id, subscription_id, delivery_generation, client_id, "
                            + "scope_code, tenant_id, scope_digest, event_type, schema_version, provider_idempotency_key, "
                            + "external_dto_snapshot, payload_hash, status, lease_token, lease_expires_at, "
                            + "terminal_at, retention_class, retention_expires_at) VALUES "
                            + "('f1-delivery-hold-race', ?, 1, ?, ?, ?, ?, 'resource.changed', 'v1', ?, '{\"status\":\"ok\"}', ?, 'SUCCEEDED', ?, ?, ?, 'SUCCEEDED_PAYLOAD_30D', ?)")) {
                insert.setLong(1, subscriptionId);
                insert.setString(2, CLIENT_ID);
                insert.setString(3, SCOPE);
                insert.setString(4, TENANT_ID);
                insert.setString(5, HASH);
                insert.setString(6, HASH);
                insert.setString(7, HASH);
                insert.setObject(8, null);
                insert.setObject(9, null);
                insert.setObject(10, NOW.minusDays(1));
                insert.setObject(11, NOW.minusSeconds(1));
                insert.executeUpdate();
            }
        }

        long deliveryId;
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id FROM t_api_delivery WHERE event_id = 'f1-delivery-hold-race'")) {
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                deliveryId = rs.getLong(1);
            }
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var holdFuture = executor.submit(() -> withTenant(() -> {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            return retentionPurgeService.acquireHold("DELIVERY", deliveryId, "MYSQL_RACE", NOW);
        }));
        var purgeFuture = executor.submit(() -> withTenant(() -> {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            return retentionPurgeService.purgeExpired("DELIVERY", "SUCCEEDED_PAYLOAD_30D", NOW, 10);
        }));
        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        boolean held = holdFuture.get(30, TimeUnit.SECONDS);
        ApiRetentionPurgeService.PurgeReport report = purgeFuture.get(30, TimeUnit.SECONDS);
        executor.shutdownNow();

        long remaining;
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM t_api_delivery WHERE id = ?")) {
            statement.setLong(1, deliveryId);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                remaining = rs.getLong(1);
            }
        }
        assertTrue((held && remaining == 1 && report.purged() == 0)
                || (!held && remaining == 0 && report.purged() == 1),
                "hold/purgeの勝者とDB状態が一致すること");
    }

    @Test
    void activeLeaseまたは期限欠落rowはpurgeせず期限後にCAS削除する() throws Exception {
        long subscriptionId;
        try (Connection connection = MYSQL.createConnection("")) {
            subscriptionId = insertSubscription(connection);
        }
        ApiDelivery delivery = deliveryService.enqueue("f1-delivery-lease", subscriptionId, 1, CLIENT_ID, SCOPE,
                TENANT_ID, "project", 1L, "resource.changed", "v1", "correlation-lease-000001",
                ExternalDtoSnapshot.of(outboundSnapshot("f1-delivery-lease", "correlation-lease-000001")), NOW);
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE t_api_delivery SET status = 'SUCCEEDED', terminal_at = ?, "
                             + "retention_class = 'SUCCEEDED_PAYLOAD_30D', retention_expires_at = ?, "
                             + "lease_token = 'active-lease', lease_expires_at = ? WHERE id = ?")) {
            statement.setObject(1, NOW.minusDays(1));
            statement.setObject(2, NOW.minusSeconds(1));
            statement.setObject(3, null);
            statement.setLong(4, delivery.getId());
            statement.executeUpdate();
        }
        assertEquals(0, retentionPurgeService.purgeExpired(
                "DELIVERY", "SUCCEEDED_PAYLOAD_30D", NOW, 10).purged());
        assertNotNull(deliveryMapper.selectById(delivery.getId()));

        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE t_api_delivery SET lease_expires_at = ? WHERE id = ?")) {
            statement.setObject(1, NOW.plusMinutes(1));
            statement.setLong(2, delivery.getId());
            statement.executeUpdate();
        }
        assertEquals(0, retentionPurgeService.purgeExpired(
                "DELIVERY", "SUCCEEDED_PAYLOAD_30D", NOW, 10).purged());

        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE t_api_delivery SET lease_expires_at = ? WHERE id = ?")) {
            statement.setObject(1, NOW);
            statement.setLong(2, delivery.getId());
            statement.executeUpdate();
        }
        assertEquals(1, retentionPurgeService.purgeExpired(
                "DELIVERY", "SUCCEEDED_PAYLOAD_30D", NOW, 10).purged());
        assertNull(deliveryMapper.selectById(delivery.getId()));
    }

    @Test
    void 未期限到来のterminal行は実MySQLでもpurge対象にならない() throws Exception {
        long subscriptionId;
        try (Connection connection = MYSQL.createConnection("")) {
            subscriptionId = insertSubscription(connection);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO t_api_delivery (event_id, subscription_id, delivery_generation, client_id, "
                            + "scope_code, tenant_id, scope_digest, event_type, schema_version, provider_idempotency_key, "
                            + "external_dto_snapshot, payload_hash, status, terminal_at, retention_class, retention_expires_at) "
                            + "VALUES ('f1-delivery-future', ?, 1, ?, ?, ?, ?, 'resource.changed', 'v1', ?, "
                            + "'{\"status\":\"ok\"}', ?, 'SUCCEEDED', ?, 'SUCCEEDED_PAYLOAD_30D', ?)")) {
                insert.setLong(1, subscriptionId);
                insert.setString(2, CLIENT_ID);
                insert.setString(3, SCOPE);
                insert.setString(4, TENANT_ID);
                insert.setString(5, HASH);
                insert.setString(6, HASH);
                insert.setString(7, HASH);
                insert.setObject(8, NOW.minusDays(1));
                insert.setObject(9, NOW.plusDays(1));
                insert.executeUpdate();
            }
        }

        assertEquals(0, retentionPurgeService.purgeExpired(
                "DELIVERY", "SUCCEEDED_PAYLOAD_30D", NOW, 10).purged());
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM t_api_delivery WHERE event_id = 'f1-delivery-future'")) {
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    @Test
    void inboundDuplicateKeyのhash競合は実serviceでCONFLICTへ永続化する() throws Exception {
        try (Connection connection = MYSQL.createConnection("")) {
            insertInboundBinding(connection);
        }
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        var first = executor.submit(() -> withTenant(() -> recordInbound(HASH, ready, start)));
        var second = executor.submit(() -> withTenant(() -> recordInbound(
                "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", ready, start)));
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        var firstReceipt = first.get(30, TimeUnit.SECONDS);
        var secondReceipt = second.get(30, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertTrue(firstReceipt.conflict() || secondReceipt.conflict());
        assertEquals("CONFLICT", inboundEventMapper.selectByProviderEvent(
                CLIENT_ID, INBOUND_PROVIDER, "provider-event-race").getStatus());
    }

    private void insertInboundBinding(Connection connection) throws Exception {
        String scope = "{\"tenantIds\":[\"" + TENANT_ID + "\"],\"legalEntityIds\":[\"9\"],"
                + "\"projectIds\":[\"1\"]}";
        try (PreparedStatement insertClient = connection.prepareStatement(
                "INSERT INTO m_api_client (client_id, owner_ref, tenant_id, legal_entity_id, data_scope_json, "
                        + "allowed_cidrs, client_tier, status) VALUES (?, 'PROJECT_OWNER', ?, 9, ?, '127.0.0.1/32', "
                        + "'INTERNAL_TEST', 'ACTIVE')",
                Statement.RETURN_GENERATED_KEYS)) {
            insertClient.setString(1, CLIENT_ID);
            insertClient.setString(2, TENANT_ID);
            insertClient.setString(3, scope);
            insertClient.executeUpdate();
            try (ResultSet keys = insertClient.getGeneratedKeys()) {
                keys.next();
                clientDatabaseId = keys.getLong(1);
            }
        }
        try (PreparedStatement insertScope = connection.prepareStatement(
                "INSERT INTO m_api_client_scope (api_client_id, scope_code, operation_code, data_scope_json, status) "
                        + "VALUES (?, 'integration.webhook.receive', 'integration.webhook.receive', ?, 'ACTIVE')")) {
            insertScope.setLong(1, clientDatabaseId);
            insertScope.setString(2, scope);
            insertScope.executeUpdate();
        }
        try (PreparedStatement insertSubscription = connection.prepareStatement(
                "INSERT INTO m_webhook_subscription (client_id, provider_name, direction, event_type, endpoint_url, "
                        + "key_id, encrypted_signing_secret, crypto_key_version, data_scope_json, status) "
                        + "VALUES (?, ?, 'INBOUND', 'health.ping', 'https://example.invalid/inbound', "
                        + "'key-1', 'IHG1:v1:iv:cipher', 'v1', ?, 'ACTIVE')")) {
            insertSubscription.setString(1, CLIENT_ID);
            insertSubscription.setString(2, INBOUND_PROVIDER);
            insertSubscription.setString(3, scope);
            insertSubscription.executeUpdate();
        }
    }

    private InboundEventService.Receipt recordInbound(String hash, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                return inboundEventService.recordReceived(CLIENT_ID, INBOUND_PROVIDER, "provider-event-race", hash,
                        NOW, ExternalDtoSnapshot.ofAllowList("{\"eventType\":\"health.ping\"}",
                                ExternalDtoSnapshot.INBOUND_FIELDS), true, NOW);
            } catch (DeadlockLoserDataAccessException ex) {
                if (attempt == 4) {
                    throw ex;
                }
                Thread.sleep(50L * (attempt + 1));
            }
        }
        throw new IllegalStateException("unreachable inbound record retry loop");
    }

    private <T> T withTenant(Callable<T> action) throws Exception {
        AccountingTenantContextHolder.setTenantId(TENANT_ID);
        try {
            return action.call();
        } finally {
            AccountingTenantContextHolder.clear();
        }
    }

    private long insertSubscription(Connection connection) throws Exception {
        try (PreparedStatement insertClient = connection.prepareStatement(
                "INSERT INTO m_api_client (client_id, owner_ref, tenant_id, legal_entity_id, data_scope_json, "
                        + "allowed_cidrs, client_tier, status) VALUES (?, 'PROJECT_OWNER', ?, 9, ?, '', 'INTERNAL_TEST', 'ACTIVE')",
                Statement.RETURN_GENERATED_KEYS)) {
            insertClient.setString(1, CLIENT_ID);
            insertClient.setString(2, TENANT_ID);
            insertClient.setString(3, "{\"tenantIds\":[\"" + TENANT_ID + "\"],\"legalEntityIds\":[\"9\"],"
                    + "\"projectIds\":[\"1\"]}");
            insertClient.executeUpdate();
            try (ResultSet keys = insertClient.getGeneratedKeys()) {
                keys.next();
                clientDatabaseId = keys.getLong(1);
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO m_webhook_subscription (client_id, direction, event_type, endpoint_url, key_id, "
                        + "encrypted_signing_secret, crypto_key_version, data_scope_json) VALUES (?, 'OUTBOUND', "
                        + "'resource.changed', ?, 'key-1', 'IHG1:v1:iv:cipher', 'v1', '{}')",
                Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, CLIENT_ID);
            insert.setString(2, "https://example.invalid/f1");
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private String outboundSnapshot(String eventId, String correlationId) {
        String publicProjectId = publicIdCodec.encode(new ExternalApiPrincipal(CLIENT_ID, clientDatabaseId,
                TENANT_ID, 9L, "{\"tenantIds\":[\"" + TENANT_ID + "\"],\"legalEntityIds\":[\"9\"],"
                + "\"projectIds\":[\"1\"]}", 1, "delivery-binding", "INTERNAL_TEST"), "project", 1L);
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"resource.changed\",\"schemaVersion\":\"v1\",\"createdAt\":\"2026-08-30T12:00:00Z\",\"publicResourceId\":\"" + publicProjectId + "\",\"correlationId\":\"" + correlationId + "\",\"payload\":{\"publicProjectId\":\"" + publicProjectId + "\",\"status\":\"ACTIVE\"}}";
    }
}
