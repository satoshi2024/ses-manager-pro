package com.ses.service.certification;

import com.ses.common.exception.BusinessException;
import com.ses.entity.Certification;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CertificationMasterH2IntegrationTest {

    @Autowired
    private CertificationMasterService certificationMasterService;

    @AfterEach
    void clearTenant() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void listとdetailと更新はtenant境界を越えない() {
        Certification master = create("tenant-a", "資格A-" + UUID.randomUUID());

        AccountingTenantContextHolder.runWithTenant("tenant-b", () -> {
            assertEquals(0, certificationMasterService.listMasters(true).size());
            assertThrows(BusinessException.class, () -> certificationMasterService.getMaster(master.getId()));
        });
        AccountingTenantContextHolder.runWithTenant("tenant-a", () ->
                assertEquals(1, certificationMasterService.listMasters(true).size()));
    }

    @Test
    void clientTenant偽装とVersion欠落またはStaleは拒否する() {
        Certification master = create("tenant-a", "資格B-" + UUID.randomUUID());
        Certification forged = input(master.getDisplayName());
        forged.setTenantId("tenant-b");

        AccountingTenantContextHolder.runWithTenant("tenant-a", () -> {
            BusinessException forgedFailure = assertThrows(BusinessException.class,
                    () -> certificationMasterService.updateMaster(master.getId(), forged, 7L, 0));
            assertEquals(403, forgedFailure.getCode());
            BusinessException missing = assertThrows(BusinessException.class,
                    () -> certificationMasterService.updateMaster(master.getId(), input("new"), 7L, null));
            assertEquals(400, missing.getCode());
            BusinessException missingDeactivate = assertThrows(BusinessException.class,
                    () -> certificationMasterService.deactivateMaster(master.getId(), 7L, null));
            assertEquals(400, missingDeactivate.getCode());
            BusinessException stale = assertThrows(BusinessException.class,
                    () -> certificationMasterService.updateMaster(master.getId(), input("new"), 7L, 1));
            assertEquals(409, stale.getCode());
        });
    }

    @Test
    void updateとdeactivateはtenant付きCASでversionを一度だけ進める() {
        Certification master = create("tenant-a", "資格C-" + UUID.randomUUID());
        AccountingTenantContextHolder.runWithTenant("tenant-a", () -> {
            Certification updated = certificationMasterService.updateMaster(master.getId(), input("更新後"), 7L, 0);
            assertEquals(1, updated.getVersion());
            Certification persisted = certificationMasterService.getMaster(master.getId());
            assertEquals(1, persisted.getVersion());

            BusinessException staleDeactivate = assertThrows(BusinessException.class,
                    () -> certificationMasterService.deactivateMaster(master.getId(), 7L, 0));
            assertEquals(409, staleDeactivate.getCode());
            Certification deactivated = certificationMasterService.deactivateMaster(master.getId(), 7L, 1);
            assertEquals(0, deactivated.getActiveFlag());
            assertEquals(2, deactivated.getVersion());
        });
    }

    @Test
    void duplicateIdentityは同一tenantだけ拒否し別tenantでは許可する() {
        String name = "資格D-" + UUID.randomUUID();
        create("tenant-a", name);
        AccountingTenantContextHolder.runWithTenant("tenant-a", () -> {
            BusinessException duplicate = assertThrows(BusinessException.class, () -> createInContext(name));
            assertEquals(409, duplicate.getCode());
        });
        Certification otherTenant = create("tenant-b", name);
        assertEquals("tenant-b", otherTenant.getTenantId());
    }

    private Certification create(String tenantId, String name) {
        return AccountingTenantContextHolder.runWithTenant(tenantId, () -> createInContext(name));
    }

    private Certification createInContext(String name) {
        Certification input = input(name);
        return certificationMasterService.createMaster(input, 7L);
    }

    private Certification input(String name) {
        Certification input = new Certification();
        input.setDisplayName(name);
        input.setIssuerDisplay("IPA");
        input.setExternalCode("CODE-" + name);
        input.setExpiryType("NONE");
        return input;
    }
}
