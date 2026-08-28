package com.ses.service.certification;

import com.ses.common.exception.BusinessException;
import com.ses.entity.Certification;
import com.ses.mapper.CertificationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CertificationMasterServiceImplTest {

    @Mock private CertificationMapper mapper;
    private CertificationMasterService service;

    @BeforeEach
    void setUp() {
        service = new CertificationMasterServiceImpl(mapper, new CertificationIdentityNormalizer());
    }

    @Test
    void masterを登録し一覧更新無効化できる() {
        Certification input = new Certification();
        input.setDisplayName("基本情報技術者");
        input.setIssuerDisplay("IPA");
        input.setExternalCode("FE");
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.selectById(10L)).thenReturn(master(10L, "旧資格", 1));
        when(mapper.updateById(any(Certification.class))).thenReturn(1);

        Certification created = service.createMaster(input, 7L);
        Certification updated = new Certification();
        updated.setDisplayName("新資格");
        updated.setIssuerDisplay("IPA");
        updated.setExternalCode("FE2");
        updated = service.updateMaster(10L, updated, 7L);
        Certification deactivated = service.deactivateMaster(10L, 7L);

        assertEquals("基本情報技術者", created.getDisplayName());
        assertEquals("新資格", updated.getDisplayName());
        assertEquals(0, deactivated.getActiveFlag());
        verify(mapper).insert(any(Certification.class));
        verify(mapper, org.mockito.Mockito.times(2)).updateById(any(Certification.class));
    }

    @Test
    void duplicateidentityは更新を拒否する() {
        Certification input = new Certification();
        input.setDisplayName("同一資格");
        input.setIssuerDisplay("issuer");
        input.setExternalCode("CODE");
        when(mapper.selectById(10L)).thenReturn(master(10L, "旧", 1));
        when(mapper.selectCount(any())).thenReturn(1L);

        assertThrows(BusinessException.class, () -> service.updateMaster(10L, input, 7L));
    }

    @Test
    void active指定なし一覧は有効masterだけを読む() {
        when(mapper.selectList(any())).thenReturn(List.of(master(1L, "A", 1)));
        assertEquals(1, service.listMasters(false).size());
    }

    private Certification master(Long id, String name, int active) {
        Certification certification = new Certification();
        certification.setId(id);
        certification.setTenantId("default");
        certification.setDisplayName(name);
        certification.setIssuerDisplay("IPA");
        certification.setExternalCode("CODE-" + id);
        certification.setIdentityKey("key-" + id);
        certification.setActiveFlag(active);
        certification.setRuleVersion(1);
        return certification;
    }
}
