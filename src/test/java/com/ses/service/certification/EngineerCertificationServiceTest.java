package com.ses.service.certification;

import com.ses.entity.Certification;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerCertification;
import com.ses.dto.certification.EngineerCertificationViewDto;
import com.ses.mapper.CertificationMapper;
import com.ses.mapper.EngineerCertificationMapper;
import com.ses.mapper.EngineerMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EngineerCertificationServiceTest {

    @Autowired
    private CertificationMasterService certificationMasterService;
    @Autowired
    private EngineerCertificationService engineerCertificationService;
    @Autowired
    private EngineerCertificationMapper engineerCertificationMapper;
    @Autowired
    private CertificationMapper certificationMapper;
    @Autowired
    private EngineerMapper engineerMapper;
    @Autowired
    private CertificationNumberCryptoService cryptoService;

    @Test
    void submitApplication_staysDraft_andEncryptsNumber() {
        Engineer engineer = new Engineer();
        engineer.setFullName("テスト太郎");
        engineer.setEmploymentType("正社員");
        engineer.setStatus("Bench");
        engineerMapper.insert(engineer);

        Certification master = new Certification();
        master.setDisplayName("基本情報技術者");
        master.setIssuerDisplay("IPA");
        master.setExternalCode("FE");
        master.setExpiryType("NONE");
        certificationMasterService.createMaster(master, 1L);

        EngineerCertificationViewDto view = engineerCertificationService.submitApplication(
                engineer.getId(), master.getId(), LocalDate.of(2026, 1, 1),
                LocalDate.of(2029, 1, 1), "CERT-9999", 1L, false);

        assertEquals("DRAFT", view.getRecordState());
        assertNotNull(view.getCertificateNumberMasked());
        assertEquals(false, view.isCanViewFullNumber());

        EngineerCertification stored = engineerCertificationMapper.selectById(view.getId());
        assertNotNull(stored.getCertificateNumberEncrypted());
        assertEquals("CNF1", stored.getCertificateNumberCipherFormat());
        assertNull(stored.getCurrentHolderKey());

        String decrypted = cryptoService.decrypt(
                stored.getTenantId(), stored.getId(),
                stored.getCertificateNumberEncrypted(),
                stored.getCertificateNumberKeyVersion(),
                stored.getCertificateNumberCipherFormat());
        assertEquals("CERT-9999", decrypted);
    }
}
