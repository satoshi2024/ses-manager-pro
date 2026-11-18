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
    private static final Class<?>[] IMPLEMENTATIONS = discoverImplementations();
    private static final Set<Class<?>> CONTRACTS = discoverContracts();

    @ParameterizedTest
    @ValueSource(strings = {"gemini", "GEMINI", "Gemini", "rule", "RULE", "mock", "MOCK",
            " gemini", "gemini ", " GEMINI ", " mock ", "", "unknown"})
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
        if (provider != null) runner = runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new org.springframework.core.env.MapPropertySource("raw-provider",
                        java.util.Map.of("ai.provider", provider))));
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            for (Class<?> contract : CONTRACTS) {
                assertThat(context.getBeansOfType(contract)).as(contract.getSimpleName()).hasSize(1);
            }
            Class<?> textType = "gemini".equalsIgnoreCase(provider) ? GeminiTextServiceImpl.class
                    : "rule".equalsIgnoreCase(provider) ? RuleAiTextServiceImpl.class : MockAiTextServiceImpl.class;
            assertThat(context.getBean(AiTextService.class)).isInstanceOf(textType);
            if (provider != null) {
                assertThat(CopilotConfigSnapshot.capture(config, context.getBean(AiTextService.class)).configuredProvider())
                        .isEqualTo(provider.trim().toLowerCase(java.util.Locale.ROOT));
            }
            if (provider != null && !provider.isBlank() && !"unknown".equals(provider)
                    && provider.equals(provider.trim())) {
                assertThat(CopilotConfigSnapshot.capture(config, context.getBean(AiTextService.class))
                        .hasBoundedIdentity()).isTrue();
            }
            Class<?> projectType = "gemini".equalsIgnoreCase(provider)
                    ? GeminiProjectParseServiceImpl.class : MockProjectParseServiceImpl.class;
            assertThat(context.getBean(ProjectParseService.class)).isInstanceOf(projectType);
        });
    }

    /** 全main sourceから条件付き実装を発見し、新しい契約の追加も自動的に対象にする。 */
    private static Class<?>[] discoverImplementations() {
        try (var files = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java"))) {
            java.util.List<Class<?>> found = new java.util.ArrayList<>();
            for (var file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String source = java.nio.file.Files.readString(file);
                if (!source.contains("ai.provider") || !java.util.regex.Pattern.compile(
                        "@(?:[\\w.]+\\.)?ConditionalOn(?:Expression|Property)\\s*\\(").matcher(source).find()) continue;
                var pkg = java.util.regex.Pattern.compile("package\\s+([\\w.]+);").matcher(source);
                if (!pkg.find()) continue;
                String name = file.getFileName().toString().replace(".java", "");
                Class<?> type = Class.forName(pkg.group(1) + "." + name);
                if (!type.isInterface() && type.getInterfaces().length > 0) found.add(type);
            }
            assertThat(found).as("provider条件付き実装を機械的に発見する").isNotEmpty();
            return found.toArray(Class<?>[]::new);
        } catch (Exception ex) {
            throw new IllegalStateException("provider条件付き実装を列挙できません", ex);
        }
    }

    private static Set<Class<?>> discoverContracts() {
        Set<Class<?>> result = new LinkedHashSet<>();
        for (Class<?> implementation : IMPLEMENTATIONS) {
            for (Class<?> contract : implementation.getInterfaces()) addContract(contract, result);
        }
        return Set.copyOf(result);
    }

    private static void addContract(Class<?> contract, Set<Class<?>> result) {
        if (!contract.getPackageName().startsWith("com.ses.service")) return;
        result.add(contract);
        for (Class<?> parent : contract.getInterfaces()) addContract(parent, result);
    }
}
