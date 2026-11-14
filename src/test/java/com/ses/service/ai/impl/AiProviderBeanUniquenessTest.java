package com.ses.service.ai.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.config.AiConfig;
import com.ses.service.ai.AiMatchingService;
import com.ses.service.ai.AiTextService;
import com.ses.service.ai.copilot.CopilotConfigSnapshot;
import com.ses.service.ai.ProjectParseService;
import com.ses.service.ai.ResumeParseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** provider値の大小文字・未設定・未知値によらず全AI実装が一意に解決する（外部I/Oなし）。 */
class AiProviderBeanUniquenessTest {
    private static final Class<?>[] IMPLEMENTATIONS = {
            GeminiTextServiceImpl.class, RuleAiTextServiceImpl.class, MockAiTextServiceImpl.class,
            GeminiMatchingServiceImpl.class, RuleMatchingServiceImpl.class, AiMatchingServiceImpl.class,
            GeminiProjectParseServiceImpl.class, MockProjectParseServiceImpl.class,
            GeminiResumeParseServiceImpl.class, MockResumeParseServiceImpl.class
    };
    private static final Set<Class<?>> CONTRACTS = Set.of(AiTextService.class, AiMatchingService.class,
            ProjectParseService.class, ResumeParseService.class);

    @ParameterizedTest
    @ValueSource(strings = {"gemini", "GEMINI", "GeMiNi", "rule", "RULE", "RuLe", "mock", "MOCK", "", "unknown"})
    void providerごとに全AI契約を一意に解決する(String provider) {
        verifyUnique(provider);
    }

    @Test
    void provider未設定でも全AI契約を一意に解決する() {
        verifyUnique(null);
    }

    private void verifyUnique(String provider) {
        AiConfig config = new AiConfig();
        config.setProvider(provider);
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(IMPLEMENTATIONS)
                .withBean(AiConfig.class, () -> config)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withInitializer(context -> {
                    Set<Class<?>> dependencies = new LinkedHashSet<>();
                    for (Class<?> implementation : IMPLEMENTATIONS) {
                        Constructor<?> constructor = Arrays.stream(implementation.getDeclaredConstructors())
                                .filter(c -> c.isAnnotationPresent(Autowired.class)).findFirst()
                                .orElseGet(() -> Arrays.stream(implementation.getDeclaredConstructors())
                                        .max(java.util.Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow());
                        dependencies.addAll(Arrays.asList(constructor.getParameterTypes()));
                    }
                    dependencies.removeAll(CONTRACTS);
                    dependencies.remove(AiConfig.class);
                    dependencies.remove(ObjectMapper.class);
                    for (Class<?> dependency : dependencies) {
                        String name = dependency == RestTemplate.class ? "aiRestTemplate" : dependency.getName();
                        context.getBeanFactory().registerSingleton(name, mock(dependency));
                    }
                });
        if (provider != null) runner = runner.withPropertyValues("ai.provider=" + provider);
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            for (Class<?> contract : CONTRACTS) {
                assertThat(context.getBeansOfType(contract)).as(contract.getSimpleName()).hasSize(1);
            }
            Class<?> textType = "gemini".equalsIgnoreCase(provider) ? GeminiTextServiceImpl.class
                    : "rule".equalsIgnoreCase(provider) ? RuleAiTextServiceImpl.class : MockAiTextServiceImpl.class;
            assertThat(context.getBean(AiTextService.class)).isInstanceOf(textType);
            if (provider != null && !provider.isBlank() && !"unknown".equals(provider)) {
                assertThat(CopilotConfigSnapshot.capture(config, context.getBean(AiTextService.class))
                        .hasBoundedIdentity()).isTrue();
            }
            Class<?> projectType = "gemini".equalsIgnoreCase(provider)
                    ? GeminiProjectParseServiceImpl.class : MockProjectParseServiceImpl.class;
            assertThat(context.getBean(ProjectParseService.class)).isInstanceOf(projectType);
        });
    }
}
