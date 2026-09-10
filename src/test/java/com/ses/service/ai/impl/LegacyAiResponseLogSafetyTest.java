package com.ses.service.ai.impl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.dto.ai.MatchResultDto;
import com.ses.service.ai.AiExecutionGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** NF08: legacy provider responseの本文・例外内容をログへ出さない。 */
class LegacyAiResponseLogSafetyTest {

    private static final String CANARY = "NF08-RAW-RESPONSE-CANARY";
    private static final String PII = "user@example.com secret-token";

    private Logger proposalLogger;
    private Logger matchingLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        proposalLogger = (Logger) LoggerFactory.getLogger(ProposalDraftServiceImpl.class);
        matchingLogger = (Logger) LoggerFactory.getLogger(GeminiMatchingServiceImpl.class);
        appender = new ListAppender<>();
        appender.start();
        proposalLogger.addAppender(appender);
        matchingLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        proposalLogger.detachAppender(appender);
        matchingLogger.detachAppender(appender);
    }

    @Test
    void proposalの不正JSONは長さと固定安全状態だけを記録する() {
        String response = "{invalid " + CANARY + " " + PII + "}";
        ProposalDraftServiceImpl service = new ProposalDraftServiceImpl(
                mock(com.ses.mapper.EngineerMapper.class),
                mock(com.ses.mapper.EngineerSkillMapper.class),
                mock(com.ses.mapper.ProjectMapper.class),
                mock(com.ses.mapper.ProjectSkillMapper.class),
                mock(AiExecutionGateway.class), new ObjectMapper());

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "parseAiResponse", response))
                .hasMessage("error.ai.parseError");

        assertSafeLogs(response);
    }

    @Test
    void matchingの不正JSONは長さと固定安全状態だけを記録する() {
        String response = "{invalid " + CANARY + " " + PII + "}";
        GeminiMatchingServiceImpl service = new GeminiMatchingServiceImpl(
                mock(com.ses.mapper.EngineerMapper.class),
                mock(com.ses.mapper.ProjectMapper.class),
                mock(com.ses.mapper.EngineerSkillMapper.class),
                mock(com.ses.mapper.ProjectSkillMapper.class),
                mock(com.ses.mapper.SkillTagMapper.class),
                mock(com.ses.mapper.BpAvailabilityMapper.class),
                new ObjectMapper(),
                mock(com.ses.service.ai.AiMatchingScopeGuard.class),
                mock(AiExecutionGateway.class));

        ReflectionTestUtils.invokeMethod(service, "parseAiResponseIntoDto",
                response, new MatchResultDto(), 1);

        assertSafeLogs(response);
    }

    private void assertSafeLogs(String response) {
        String logs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
        assertThat(logs).contains("category=PARSE_ERROR");
        assertThat(logs).contains("responseBytes=");
        assertThat(logs).contains("safety=REDACTED");
        assertThat(logs).doesNotContain(response);
        assertThat(logs).doesNotContain(CANARY);
        assertThat(logs).doesNotContain(PII);
        assertThat(appender.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }
}
