package com.ses.importcenter.metadata;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F1a metadata/candidate beanと@Transactional proxyの実context起動を検証する。 */
@SpringBootTest
@ActiveProfiles("test")
class ImportIdentityCandidateContextTest {
    @Autowired private ImportIdentityCandidateService candidateService;
    @Autowired private ImportCandidateSourceReader sourceReader;
    @Autowired private ImportMetadataService metadataService;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void metadataCandidateReaderAndTransactionManagerAreWiredInSpringContext() {
        assertNotNull(candidateService);
        assertNotNull(sourceReader);
        assertNotNull(transactionManager);
        assertTrue(AopUtils.isAopProxy(metadataService), "metadata serviceのtransaction proxyが必要です");
    }
}
