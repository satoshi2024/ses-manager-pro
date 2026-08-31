package com.ses.config.integrationhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** controller/Dispatcherの例外・404をinternal /error形式へfall-throughさせない。 */
@Component
@RequiredArgsConstructor
public class ExternalApiResponseBoundaryFilter extends OncePerRequestFilter {
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!ExternalApiRouteCatalog.isExternalApiPath(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            filterChain.doFilter(request, response);
        } catch (ExternalApiSecurityException e) {
            request.setAttribute(ExternalApiErrorWriter.DECISION_ATTRIBUTE, e.getDecision());
            ExternalApiErrorWriter.writeException(response, objectMapper, correlationId(request), e);
        } catch (Exception e) {
            ExternalApiSecurityException securityException = unwrapSecurityCause(e);
            if (securityException != null) {
                request.setAttribute(ExternalApiErrorWriter.DECISION_ATTRIBUTE, securityException.getDecision());
                ExternalApiErrorWriter.writeException(response, objectMapper, correlationId(request), securityException);
            } else {
                request.setAttribute(ExternalApiErrorWriter.DECISION_ATTRIBUTE, "INTERNAL_ERROR");
                ExternalApiErrorWriter.write(response, objectMapper, correlationId(request), 500,
                        "INTERNAL_ERROR", false, 0);
            }
        }
        if (!response.isCommitted() && response.getStatus() >= 400) {
            String code = ExternalApiErrorWriter.codeForStatus(response.getStatus());
            request.setAttribute(ExternalApiErrorWriter.DECISION_ATTRIBUTE, code);
            ExternalApiErrorWriter.write(response, objectMapper, correlationId(request), response.getStatus(),
                    code, response.getStatus() == 429, 0);
        }
    }

    private String correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(ExternalApiErrorWriter.CORRELATION_ATTRIBUTE);
        return value instanceof String id ? id : "unavailable";
    }

    /** Servlet/Springがexceptionをwrapperへ包んでも、外部契約例外だけを復元する。 */
    private ExternalApiSecurityException unwrapSecurityCause(Throwable wrapper) {
        if (!(wrapper instanceof ServletException) && !(wrapper instanceof RuntimeException)) {
            return null;
        }
        Throwable current = wrapper.getCause();
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof ExternalApiSecurityException securityException) {
                return securityException;
            }
            if (!(current instanceof ServletException) && !(current instanceof RuntimeException)) {
                return null;
            }
            Throwable next = current.getCause();
            if (next == current) {
                return null;
            }
            current = next;
        }
        return null;
    }
}
