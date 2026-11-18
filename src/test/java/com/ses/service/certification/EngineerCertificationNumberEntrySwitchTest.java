package com.ses.service.certification;

import com.ses.common.exception.BusinessException;
import com.ses.config.feature.FeatureSwitchProperties;
import com.ses.config.feature.FeatureSwitches;
import com.ses.dto.certification.EngineerCertificationViewDto;
import com.ses.entity.Certification;
import com.ses.entity.CertificationContinuityGroup;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerCertification;
import com.ses.mapper.CertificationContinuityGroupMapper;
import com.ses.mapper.CertificationEventMapper;
import com.ses.mapper.CertificationMapper;
import com.ses.mapper.EngineerCertificationMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 資格番号（CERTIFICATION_PII）入力の子スイッチ {@code app.features.nf03-certification-pii.enabled} を検証する（DB不要）。
 *
 * <p>OFFの間は資格番号付きの申請・再申請をDB・暗号化に触れる前に拒否し、空欄の申請（非PII部分）は従来どおり受け付ける。
 */
class EngineerCertificationNumberEntrySwitchTest {

    private static final String TENANT = "tenant-a";

    private final EngineerCertificationMapper recordMapper = mock(EngineerCertificationMapper.class);
    private final CertificationMapper certificationMapper = mock(CertificationMapper.class);
    private final EngineerMapper engineerMapper = mock(EngineerMapper.class);
    private final CertificationNumberCryptoService cryptoService = mock(CertificationNumberCryptoService.class);
    private final CertificationEventMapper eventMapper = mock(CertificationEventMapper.class);
    private final CertificationContinuityGroupMapper continuityGroupMapper =
            mock(CertificationContinuityGroupMapper.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-13T00:00:00Z"), ZoneId.of("Asia/Tokyo"));

    private EngineerCertificationServiceImpl service;

    @BeforeEach
    void setUp() {
        AccountingTenantContextHolder.setTenantId(TENANT);
        service = new EngineerCertificationServiceImpl(recordMapper, certificationMapper, engineerMapper,
                cryptoService, eventMapper, null, clock, continuityGroupMapper);
    }

    @AfterEach
    void clearTenantContext() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void 資格番号の入力スイッチがOFFなら資格番号付きの申請はDBと暗号化に触れる前に400で拒否される() {
        inject(switches(true, false));

        assertThatThrownBy(() -> service.submitApplication(20L, 2L, LocalDate.of(2026, 10, 1), null,
                "CERT-0001", 7L, false))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(400);
                    assertThat(error.getMessageKey()).isEqualTo("certification.record.numberEntryDisabled");
                });
        verifyNoInteractions(recordMapper, certificationMapper, engineerMapper, cryptoService, eventMapper,
                continuityGroupMapper);
    }

    @Test
    void 資格番号の入力スイッチがOFFなら再申請も元recordを消費する前に拒否される() {
        inject(switches(true, false));

        assertThatThrownBy(() -> service.resubmit(11L, 3, 7L, "CERT-0002", false))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(400);
                    assertThat(error.getMessageKey()).isEqualTo("certification.record.numberEntryDisabled");
                });
        // 元recordのlock・CAS・RESUBMIT eventのいずれも発生しない。
        verifyNoInteractions(recordMapper, certificationMapper, engineerMapper, cryptoService, eventMapper,
                continuityGroupMapper);
    }

    @Test
    void NF03本体がOFFなら子スイッチの設定値がONでも資格番号を受け付けない() {
        inject(switches(false, true));

        assertThatThrownBy(() -> service.submitApplication(20L, 2L, LocalDate.of(2026, 10, 1), null,
                "CERT-0003", 7L, false))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getMessageKey()).isEqualTo("certification.record.numberEntryDisabled"));
        verifyNoInteractions(recordMapper, cryptoService);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "  \n"})
    void 資格番号が空欄なら入力スイッチがOFFでも資格の申請は非PII部分として受け付ける(String blank) {
        inject(switches(true, false));
        stubNewApplication();

        EngineerCertificationViewDto view = service.submitApplication(20L, 2L, LocalDate.of(2026, 10, 1), null,
                blank, 7L, false);

        assertThat(view.getId()).isEqualTo(902L);
        assertThat(view.getRecordState()).isEqualTo(CertificationRecordStates.DRAFT);
        assertThat(view.getCertificateNumberMasked()).isNull();
        verify(recordMapper).insert(any(EngineerCertification.class));
        verifyNoInteractions(cryptoService);
        verify(recordMapper, never()).updateCertificateNumberForTenant(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 入力スイッチがONなら従来どおり資格番号を暗号化して保存する() {
        inject(switches(true, true));
        stubNewApplication();
        when(cryptoService.encrypt(TENANT, 902L, "CERT-0004")).thenReturn(
                new CertificationNumberCryptoService.EncryptedCertificationNumber(new byte[]{1, 2, 3}, "v1", "CNF1",
                        "****0004"));
        when(recordMapper.updateCertificateNumberForTenant(eq(902L), eq(TENANT), anyInt(), any(), anyString(),
                anyString(), anyString())).thenReturn(1);

        EngineerCertificationViewDto view = service.submitApplication(20L, 2L, LocalDate.of(2026, 10, 1), null,
                "CERT-0004", 7L, false);

        assertThat(view.getCertificateNumberMasked()).isEqualTo("****0004");
        verify(cryptoService).encrypt(TENANT, 902L, "CERT-0004");
        verify(recordMapper).updateCertificateNumberForTenant(eq(902L), eq(TENANT), eq(0), any(), eq("v1"),
                eq("CNF1"), eq("****0004"));
    }

    @Test
    void Spring管理外で生成しFeatureSwitchesが無い場合は従来の呼出し経路を変えない() {
        // 既存の単体テスト（new で生成）の意味を保つためのfail-open。アプリ本体では必須Beanとして必ず注入される。
        stubNewApplication();
        when(cryptoService.encrypt(TENANT, 902L, "CERT-0005")).thenReturn(
                new CertificationNumberCryptoService.EncryptedCertificationNumber(new byte[]{4}, "v1", "CNF1",
                        "****0005"));
        when(recordMapper.updateCertificateNumberForTenant(eq(902L), eq(TENANT), anyInt(), any(), anyString(),
                anyString(), anyString())).thenReturn(1);

        service.submitApplication(20L, 2L, LocalDate.of(2026, 10, 1), null, "CERT-0005", 7L, false);

        verify(cryptoService).encrypt(TENANT, 902L, "CERT-0005");
    }

    private void inject(FeatureSwitches switches) {
        ReflectionTestUtils.setField(service, "featureSwitches", switches);
    }

    /** NF-03本体と資格番号の子スイッチの設定値を与える（他の機能はOFF）。 */
    private static FeatureSwitches switches(boolean nf03, boolean certificationPii) {
        FeatureSwitchProperties properties = new FeatureSwitchProperties();
        properties.getNf03().setEnabled(nf03);
        properties.getNf03CertificationPii().setEnabled(certificationPii);
        return new FeatureSwitches(properties);
    }

    private void stubNewApplication() {
        Certification master = new Certification();
        master.setId(2L);
        master.setTenantId(TENANT);
        master.setActiveFlag(1);
        master.setRuleVersion(1);
        master.setDisplayName("基本情報技術者");
        when(engineerMapper.selectByIdForTenant(20L, TENANT)).thenReturn(new Engineer());
        when(certificationMapper.selectByIdForTenant(2L, TENANT)).thenReturn(master);
        when(recordMapper.countNonTerminalAcquisition(eq(TENANT), eq(20L), eq(2L), any(), eq(null))).thenReturn(0L);
        when(continuityGroupMapper.insert(any(CertificationContinuityGroup.class))).thenAnswer(invocation -> {
            CertificationContinuityGroup group = invocation.getArgument(0);
            group.setContinuityGroupId(901L);
            return 1;
        });
        doAnswer(invocation -> {
            ((EngineerCertification) invocation.getArgument(0)).setId(902L);
            return 1;
        }).when(recordMapper).insert(any(EngineerCertification.class));
    }
}
