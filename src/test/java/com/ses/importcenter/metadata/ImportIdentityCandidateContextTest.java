package com.ses.importcenter.metadata;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F1a metadata/candidate beanと@Transactional proxyの実context起動を検証する。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ImportIdentityCandidateContextTest {
    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        String database = "identity_context_" + UUID.randomUUID().toString().replace("-", "");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:" + database
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL");
    }

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
