package com.ses.service.cloudsign;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.enums.CloudSignErrorCode;
import com.ses.dto.cloudsign.CloudSignError;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * providerのHTTP status / 公式errorModel / 例外型を safe error code と結果不明flagへ分類する。
 * mutation(create/upload/participant/send)の timeout/504/connection reset は
 * providerが処理済みの可能性があるため {@code uncertain=true} にする（HFP-02-AC-04-03）。
 */
@Component
public class CloudSignErrorClassifier {

    private final ObjectMapper objectMapper;

    public CloudSignErrorClassifier(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param mutation true=外部変更API（timeout/5xxを結果不明にする）
     */
    public CloudSignApiException classify(Throwable throwable, boolean mutation) {
        if (throwable instanceof CloudSignApiException e) {
            return e;
        }
        if (throwable instanceof HttpStatusCodeException statusEx) {
            HttpStatus status = HttpStatus.resolve(statusEx.getStatusCode().value());
            CloudSignErrorCode code = codeOfStatus(status);
            boolean uncertain = mutation && isUncertainStatus(status);
            String safeMessage = code.name() + (uncertain ? ":RESULT_UNKNOWN" : "");
            return new CloudSignApiException(code, uncertain, safeMessage);
        }
        if (throwable instanceof ResourceAccessException rae) {
            CloudSignErrorCode code = resolveTransportCode(rae);
            boolean uncertain = mutation;
            String safeMessage = code.name() + (uncertain ? ":RESULT_UNKNOWN" : "");
            return new CloudSignApiException(code, uncertain, safeMessage);
        }
        if (throwable instanceof java.net.SocketTimeoutException) {
            return new CloudSignApiException(CloudSignErrorCode.TIMEOUT, mutation, "TIMEOUT" + (mutation ? ":RESULT_UNKNOWN" : ""));
        }
        return new CloudSignApiException(CloudSignErrorCode.UNKNOWN, false, "UNKNOWN");
    }

    /**
     * HTTP 2xx以外のレスポンスbody（errorModel）を読んで分類する。
     * PIIを含むmessageは例外へ載せない。
     */
    public CloudSignApiException classifyResponse(int status, byte[] body, boolean mutation) {
        HttpStatus httpStatus = HttpStatus.resolve(status);
        CloudSignErrorCode code = codeOfStatus(httpStatus);
        if (body != null && body.length > 0 && body.length < 64 * 1024) {
            try {
                CloudSignError error = objectMapper.readValue(body, CloudSignError.class);
                CloudSignErrorCode fromErrorValue = codeOfErrorValue(error.error());
                if (fromErrorValue != null) {
                    code = fromErrorValue;
                }
            } catch (IOException ignored) {
                // bodyがJSONでない（HTML error等）場合はstatusのみで分類する
            }
        }
        boolean uncertain = mutation && isUncertainStatus(httpStatus);
        return new CloudSignApiException(code, uncertain, code.name() + (uncertain ? ":RESULT_UNKNOWN" : ""));
    }

    private static boolean isUncertainStatus(HttpStatus status) {
        // 504は公式ガイドにより「返却後もCloudSign側処理が継続する場合がある」。500系も結果不明として安全側に扱う。
        return status != null && status.is5xxServerError();
    }

    private static CloudSignErrorCode codeOfStatus(HttpStatus status) {
        if (status == null) {
            return CloudSignErrorCode.UNKNOWN;
        }
        return switch (status.value()) {
            case 400 -> CloudSignErrorCode.VALIDATION;
            case 401 -> CloudSignErrorCode.UNAUTHORIZED;
            case 403 -> CloudSignErrorCode.FORBIDDEN;
            case 404 -> CloudSignErrorCode.NOT_FOUND;
            case 405 -> CloudSignErrorCode.METHOD_NOT_ALLOWED;
            case 409 -> CloudSignErrorCode.CONFLICT;
            case 413 -> CloudSignErrorCode.TOO_LARGE;
            case 415 -> CloudSignErrorCode.UNSUPPORTED_MEDIA;
            case 429 -> CloudSignErrorCode.RATE_LIMITED;
            default -> status.is5xxServerError() ? CloudSignErrorCode.SERVER_ERROR : CloudSignErrorCode.UNKNOWN;
        };
    }

    /** 公式errorModel.error値（research.md / fixture errorModel）から分類。該当なしはnull。 */
    private static CloudSignErrorCode codeOfErrorValue(String error) {
        if (error == null) {
            return null;
        }
        return switch (error) {
            case "bad_request", "invalid_request" -> CloudSignErrorCode.VALIDATION;
            case "invalid_client" -> CloudSignErrorCode.INVALID_CLIENT;
            case "unauthorized" -> CloudSignErrorCode.UNAUTHORIZED;
            case "forbidden", "webapi_option_required", "not_acceptable" -> CloudSignErrorCode.FORBIDDEN;
            case "not_found" -> CloudSignErrorCode.NOT_FOUND;
            case "method_not_allowed" -> CloudSignErrorCode.METHOD_NOT_ALLOWED;
            case "conflict" -> CloudSignErrorCode.CONFLICT;
            case "too_large_request" -> CloudSignErrorCode.TOO_LARGE;
            case "unsupported_media_type" -> CloudSignErrorCode.UNSUPPORTED_MEDIA;
            case "too_many_requests" -> CloudSignErrorCode.RATE_LIMITED;
            case "internal_server_error" -> CloudSignErrorCode.SERVER_ERROR;
            default -> null;
        };
    }

    private static CloudSignErrorCode resolveTransportCode(ResourceAccessException rae) {
        Throwable cause = rae.getCause();
        if (cause instanceof java.net.SocketTimeoutException) {
            return CloudSignErrorCode.TIMEOUT;
        }
        if (cause instanceof java.net.ConnectException) {
            return CloudSignErrorCode.NETWORK;
        }
        return CloudSignErrorCode.NETWORK;
    }
}
