package com.ses.service.ai;

import com.ses.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** NF08: provider名・実装identity・local/external区分をregistryでfail-closedに固定する。 */
class AiProviderRegistryTest {
    private final AiProviderRegistry registry = new AiProviderRegistry();

    @Test
    void 空値未知値と実装identity不一致は拒否する() {
        assertThrows(BusinessException.class, () -> registry.requireRegistered(null));
        assertThrows(BusinessException.class, () -> registry.requireRegistered("openai"));
        assertThrows(BusinessException.class, () -> registry.requireRegistered("mock-typo"));
        assertThrows(BusinessException.class, () -> registry.assertImplementation("mock",
                new TestProvider("gemini")));
    }

    @Test
    void 登録providerは対応identityだけを受け付ける() {
        registry.assertImplementation("mock", new TestProvider("mock"));
        registry.assertImplementation("rule", new TestProvider("rule"));
        registry.assertImplementation("gemini", new TestProvider("gemini"));
    }

    private record TestProvider(String providerId) implements AiTextService {
        @Override
        public String generate(String prompt) {
            return "test";
        }
    }
}
