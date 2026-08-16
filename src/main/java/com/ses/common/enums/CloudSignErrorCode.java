package com.ses.common.enums;

/**
 * CloudSign providerエラーのPIIを含まない分類code（HFP-02-AC-09-01）。
 * mutationのTIMEOUT/SERVER_ERRORは「結果不明」を意味し、自動再実行してはならない。
 */
public enum CloudSignErrorCode {
    /** 公式errorModelのbad_request / invalid_request。4xx validation。 */
    VALIDATION,
    /** 公式errorModelのinvalid_client。credential不備。 */
    INVALID_CLIENT,
    /** HTTP 401 / 公式errorのunauthorized。token一回再取得後も続く場合はcredential failure。 */
    UNAUTHORIZED,
    /** HTTP 403 / 公式errorのforbidden / webapi_option_required / not_acceptable。 */
    FORBIDDEN,
    /** HTTP 404 / 公式errorのnot_found。 */
    NOT_FOUND,
    /** HTTP 405 / 公式errorのmethod_not_allowed。 */
    METHOD_NOT_ALLOWED,
    /** HTTP 409 / 公式errorのconflict。 */
    CONFLICT,
    /** HTTP 413 / 公式errorのtoo_large_request。 */
    TOO_LARGE,
    /** HTTP 415 / 公式errorのunsupported_media_type。 */
    UNSUPPORTED_MEDIA,
    /** HTTP 429 / 公式errorのtoo_many_requests。rate limit超過。 */
    RATE_LIMITED,
    /** HTTP 5xx（504含む）/ 公式errorのinternal_server_error。mutationでは結果不明。 */
    SERVER_ERROR,
    /** read/connect timeout。mutationでは結果不明。 */
    TIMEOUT,
    /** 接続不能・connection reset等。mutationでは結果不明。 */
    NETWORK,
    /** 必須field欠落・不正JSON等のschema契約違反。 */
    MALFORMED_RESPONSE,
    /** 上記に該当しない未知の失敗。安全側に要確認扱い。 */
    UNKNOWN
}
