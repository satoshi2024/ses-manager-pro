package com.ses.privacy.production.dsar;

import com.ses.entity.privacy.PrivacyExportPackage;
import com.ses.entity.privacy.PrivacyRequest;
import com.ses.mapper.privacy.PrivacyExportPackageMapper;
import com.ses.mapper.privacy.PrivacyRequestMapper;
import com.ses.service.FileReferenceProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NF07-A2b: DSAR の開示 package を孤児清掃から守る provider（production-bridge-phased-plan.md §3.5.2）。
 *
 * <p>H2 上に2つの専用 tenant の case と package を作り（class の transaction で rollback）、PURGED でない package の保存名だけが
 * tenant を問わず参照に数えられることを確かめる。清掃の参照集合として Spring に登録されていることも確かめる。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PrivacyExportPackageFileReferenceProviderTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 16, 10, 0);

    @Autowired
    private PrivacyRequestMapper requestMapper;
    @Autowired
    private PrivacyExportPackageMapper packageMapper;
    @Autowired
    private List<FileReferenceProvider> providers;

    @Test
    void PURGEDでないpackageの保存名だけを全tenantについて参照に数える() {
        long first = request("nf07-a2b-ref-a", "DSAR-REF-A");
        long second = request("nf07-a2b-ref-b", "DSAR-REF-B");
        pkg("nf07-a2b-ref-a", first, "a2bref00000000000000000000000001.json", "DRAFT");
        pkg("nf07-a2b-ref-a", first, "a2bref00000000000000000000000002.json", "PURGED");
        pkg("nf07-a2b-ref-b", second, "a2bref00000000000000000000000003.json", "APPROVED");

        Set<String> referenced = new PrivacyExportPackageFileReferenceProvider(packageMapper).referencedFileNames();

        assertThat(referenced).contains("a2bref00000000000000000000000001.json", "a2bref00000000000000000000000003.json")
                .doesNotContain("a2bref00000000000000000000000002.json");
    }

    @Test
    void 清掃の参照集合に登録され照会できなければ清掃を止める() {
        assertThat(providers).anyMatch(PrivacyExportPackageFileReferenceProvider.class::isInstance);

        PrivacyExportPackageMapper broken = mock(PrivacyExportPackageMapper.class);
        when(broken.selectLiveStoredNames()).thenThrow(new IllegalStateException("db unavailable"));
        assertThatThrownBy(() -> new PrivacyExportPackageFileReferenceProvider(broken).referencedFileNames())
                .isInstanceOf(IllegalStateException.class);
    }

    private long request(String tenantId, String requestNo) {
        PrivacyRequest row = new PrivacyRequest();
        row.setTenantId(tenantId);
        row.setRequestNo(requestNo);
        row.setRequestType("ACCESS");
        row.setStatus("PREVIEWED");
        row.setChannel("EMAIL");
        row.setReceivedAt(NOW);
        row.setIntakeTicketRef("T-" + requestNo);
        row.setIdentityState("VERIFIED");
        row.setPreparedBy(1L);
        row.setRowVersion(5L);
        row.setCreatedBy(1L);
        row.setCreatedAt(NOW);
        row.setUpdatedAt(NOW);
        requestMapper.insert(row);
        return row.getId();
    }

    private void pkg(String tenantId, long requestId, String storedName, String status) {
        PrivacyExportPackage row = new PrivacyExportPackage();
        row.setTenantId(tenantId);
        row.setRequestId(requestId);
        row.setStoredName(storedName);
        row.setContentSha256("a".repeat(64));
        row.setSizeBytes(10L);
        row.setStatus(status);
        row.setPurgeAfter(NOW.plusDays(30));
        row.setCreatedBy(1L);
        row.setCreatedAt(NOW);
        if ("APPROVED".equals(status)) {
            row.setApprovedBy(2L);
            row.setApprovedAt(NOW);
        }
        if ("PURGED".equals(status)) {
            row.setPurgedAt(NOW);
        }
        packageMapper.insert(row);
    }
}
