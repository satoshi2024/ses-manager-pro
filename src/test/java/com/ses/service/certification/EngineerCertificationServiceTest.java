package com.ses.service.certification;

import com.ses.entity.Certification;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerCertification;
import com.ses.dto.certification.EngineerCertificationViewDto;
import com.ses.mapper.CertificationContinuityGroupMapper;
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

    @Autowired
    private CertificationContinuityGroupMapper continuityGroupMapper;

    private Engineer createEngineer(String name) {
        Engineer engineer = new Engineer();
        engineer.setFullName(name);
        engineer.setEmploymentType("正社員");
        engineer.setStatus("Bench");
        engineerMapper.insert(engineer);
        return engineer;
    }

    private Certification createMaster(String name, String code) {
        Certification master = new Certification();
        master.setDisplayName(name);
        master.setIssuerDisplay("IPA");
        master.setExternalCode(code);
        master.setExpiryType("NONE");
        certificationMasterService.createMaster(master, 1L);
        return master;
    }

    @Test
    void submitApplication_staysDraft_andEncryptsNumber() {
        Engineer engineer = createEngineer("テスト太郎");
        Certification master = createMaster("基本情報技術者", "FE");

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

    @Test
    void submitApplication_allocatesDistinctContinuityGroupsForIndependentChains() {
        Engineer engineer1 = createEngineer("独立太郎");
        Engineer engineer2 = createEngineer("独立次郎");
        Certification masterA = createMaster("応用情報技術者", "AP");
        Certification masterB = createMaster("情報セキュリティスペシャリスト", "SC");

        EngineerCertificationViewDto view1 = engineerCertificationService.submitApplication(
                engineer1.getId(), masterA.getId(), LocalDate.of(2026, 1, 1),
                null, null, 1L, false);
        EngineerCertificationViewDto view2 = engineerCertificationService.submitApplication(
                engineer2.getId(), masterB.getId(), LocalDate.of(2026, 1, 1),
                null, null, 1L, false);
        EngineerCertificationViewDto view3 = engineerCertificationService.submitApplication(
                engineer1.getId(), masterB.getId(), LocalDate.of(2026, 2, 1),
                null, null, 1L, false);

        EngineerCertification rec1 = engineerCertificationMapper.selectById(view1.getId());
        EngineerCertification rec2 = engineerCertificationMapper.selectById(view2.getId());
        EngineerCertification rec3 = engineerCertificationMapper.selectById(view3.getId());

        assertNotNull(rec1.getContinuityGroupId());
        assertNotNull(rec2.getContinuityGroupId());
        assertNotNull(rec3.getContinuityGroupId());

        // 各チェーンで独立した continuityGroupId が採番されること
        org.junit.jupiter.api.Assertions.assertNotEquals(rec1.getContinuityGroupId(), rec2.getContinuityGroupId());
        org.junit.jupiter.api.Assertions.assertNotEquals(rec1.getContinuityGroupId(), rec3.getContinuityGroupId());
        org.junit.jupiter.api.Assertions.assertNotEquals(rec2.getContinuityGroupId(), rec3.getContinuityGroupId());

        // t_certification_continuity_group に実レコードが存在すること
        com.ses.entity.CertificationContinuityGroup grp1 = continuityGroupMapper.selectById(rec1.getContinuityGroupId());
        assertNotNull(grp1);
        assertEquals(rec1.getTenantId(), grp1.getTenantId());
        assertEquals(rec1.getEngineerId(), grp1.getEngineerId());
        assertEquals(rec1.getCertificationId(), grp1.getCertificationId());
    }

    @Test
    void renew_preservesOriginalContinuityGroupIdAndUpdatesHolder() {
        Engineer engineer = createEngineer("更新三郎");
        Certification master = createMaster("ネットワークスペシャリスト", "NW");

        EngineerCertificationViewDto view = engineerCertificationService.submitApplication(
                engineer.getId(), master.getId(), LocalDate.of(2025, 1, 1),
                LocalDate.of(2027, 1, 1), "NW-1111", 1L, false);
        EngineerCertification initial = engineerCertificationMapper.selectById(view.getId());

        // INITIAL を ACTIVE, currentFlag=1, currentHolderKey=continuityGroupId に設定
        initial.setRecordState(CertificationRecordStates.ACTIVE);
        initial.setCurrentFlag(1);
        initial.setCurrentHolderKey(initial.getContinuityGroupId());
        engineerCertificationMapper.updateById(initial);

        // renew実行
        EngineerCertification renewed = engineerCertificationService.renew(
                initial.getId(), initial.getVersion(), LocalDate.of(2027, 1, 2),
                LocalDate.of(2029, 1, 1), 1L, "資格更新申請");

        assertNotNull(renewed);
        assertEquals(initial.getContinuityGroupId(), renewed.getContinuityGroupId(), "renew後は元のcontinuityGroupIdを継承すること");
        assertEquals(1, renewed.getCurrentFlag());
        assertEquals(initial.getContinuityGroupId(), renewed.getCurrentHolderKey());
        assertEquals(CertificationRecordStates.ACTIVE, renewed.getRecordState());

        // 旧レコードのcurrent状態が解除されていること
        EngineerCertification updatedOld = engineerCertificationMapper.selectById(initial.getId());
        assertEquals(CertificationRecordStates.SUPERSEDED, updatedOld.getRecordState());
        assertEquals(0, updatedOld.getCurrentFlag());
        assertNull(updatedOld.getCurrentHolderKey());
    }

    @Test
    void constraints_violatesCheckConstraint_whenCurrentFlag1WithNullHolder() {
        Engineer engineer = createEngineer("制約四郎");
        Certification master = createMaster("データベーススペシャリスト", "DB");

        com.ses.entity.CertificationContinuityGroup group = new com.ses.entity.CertificationContinuityGroup();
        group.setTenantId("default");
        group.setEngineerId(engineer.getId());
        group.setCertificationId(master.getId());
        continuityGroupMapper.insert(group);

        EngineerCertification invalid = new EngineerCertification();
        invalid.setTenantId("default");
        invalid.setEngineerId(engineer.getId());
        invalid.setCertificationId(master.getId());
        invalid.setContinuityGroupId(group.getContinuityGroupId());
        invalid.setAcquiredOn(LocalDate.of(2026, 1, 1));
        invalid.setRecordState(CertificationRecordStates.ACTIVE);
        invalid.setCurrentFlag(1);
        invalid.setCurrentHolderKey(null); // VIOLATION: current_flag=1 but current_holder_key is null

        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> engineerCertificationMapper.insert(invalid));
    }

    @Test
    void constraints_violatesCheckConstraint_whenCurrentFlag0WithNonNullHolder() {
        Engineer engineer = createEngineer("制約五郎");
        Certification master = createMaster("プロジェクトマネージャ", "PM");

        com.ses.entity.CertificationContinuityGroup group = new com.ses.entity.CertificationContinuityGroup();
        group.setTenantId("default");
        group.setEngineerId(engineer.getId());
        group.setCertificationId(master.getId());
        continuityGroupMapper.insert(group);

        EngineerCertification invalid = new EngineerCertification();
        invalid.setTenantId("default");
        invalid.setEngineerId(engineer.getId());
        invalid.setCertificationId(master.getId());
        invalid.setContinuityGroupId(group.getContinuityGroupId());
        invalid.setAcquiredOn(LocalDate.of(2026, 1, 1));
        invalid.setRecordState(CertificationRecordStates.DRAFT);
        invalid.setCurrentFlag(0);
        invalid.setCurrentHolderKey(group.getContinuityGroupId()); // VIOLATION: current_flag=0 but current_holder_key is not null

        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> engineerCertificationMapper.insert(invalid));
    }

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    void schema_verifiesForeignKeyAndCheckConstraintsDefined() {
        Integer fkCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE UPPER(table_name) = 'T_ENGINEER_CERTIFICATION' "
                        + "AND UPPER(constraint_name) = 'FK_ENG_CERT_CONTINUITY_GROUP' "
                        + "AND constraint_type = 'FOREIGN KEY'",
                Integer.class);
        assertNotNull(fkCount);
        assertEquals(1, fkCount, "fk_eng_cert_continuity_group FK制約が存在すること");

        Integer chkCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE UPPER(table_name) = 'T_ENGINEER_CERTIFICATION' "
                        + "AND UPPER(constraint_name) = 'CHK_ENG_CERT_CURRENT_HOLDER' "
                        + "AND constraint_type = 'CHECK'",
                Integer.class);
        assertNotNull(chkCount);
        assertEquals(1, chkCount, "chk_eng_cert_current_holder CHECK制約が存在すること");
    }
}
