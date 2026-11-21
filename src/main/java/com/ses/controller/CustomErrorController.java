package com.ses.controller;

import com.ses.backup.quiesce.BackupQuiesceRejections;
import com.ses.backup.quiesce.BackupWriteGate;
import com.ses.common.result.ApiResult;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 統一エラーコントローラー
 * 404 / 403 / 500 などのエラーディスパッチを一元的に処理する。
 *
 * - API / AJAX リクエスト（/api/** もしくは X-Requested-With ヘッダー付き）には
 *   ApiResult(JSON) を返し、フロント側の既存ハンドリングと整合させる。
 * - ブラウザによる画面遷移には、深色テーマの統一エラーページを描画する。
 *   これにより顧客に生のスタックトレースやWhitelabelページを見せない。
 */
@Controller
public class CustomErrorController implements ErrorController {

    private final ObjectProvider<MessageSource> messageSource;

    /** 単体試験用（静止中の文言は日本語の固定文言になる）。 */
    public CustomErrorController() {
        this(null);
    }

    @Autowired
    public CustomErrorController(ObjectProvider<MessageSource> messageSource) {
        this.messageSource = messageSource;
    }

    @RequestMapping("/error")
    public Object handleError(HttpServletRequest request, Model model) {
        int status = resolveStatus(request);
        String requestUri = attr(request, RequestDispatcher.ERROR_REQUEST_URI);
        // backup の書込み静止で 503 にした画面の要求（RB-QUIESCE §6.10）は、静止中の文言を利用者の locale で出す
        boolean quiesced = status == 503 && Boolean.TRUE.equals(request.getAttribute(BackupWriteGate.REJECTED_ATTRIBUTE));
        MessageSource source = messageSource == null ? null : messageSource.getIfAvailable();
        String message = quiesced
                ? BackupQuiesceRejections.message(BackupQuiesceRejections.PAGE_MESSAGE_KEY,
                        BackupQuiesceRejections.PAGE_MESSAGE_FALLBACK, source, LocaleContextHolder.getLocale())
                : messageFor(status);

        // API / AJAX にはJSONを返す
        if (isJsonRequest(request, requestUri)) {
            return ResponseEntity.status(status).body(ApiResult.error(status, quiesced
                    ? BackupQuiesceRejections.writeUnavailableMessage(source, LocaleContextHolder.getLocale())
                    : message));
        }

        model.addAttribute("status", status);
        model.addAttribute("title", quiesced
                ? BackupQuiesceRejections.message(BackupQuiesceRejections.PAGE_TITLE_KEY,
                        BackupQuiesceRejections.PAGE_TITLE_FALLBACK, source, LocaleContextHolder.getLocale())
                : titleFor(status));
        model.addAttribute("message", message);
        model.addAttribute("icon", quiesced ? BackupQuiesceRejections.PAGE_ICON : iconFor(status));
        return "error";
    }

    private int resolveStatus(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (code != null) {
            try {
                return Integer.parseInt(code.toString());
            } catch (NumberFormatException ignored) {
                // フォールバック
            }
        }
        return 500;
    }

    private boolean isJsonRequest(HttpServletRequest request, String requestUri) {
        if (requestUri != null && requestUri.startsWith("/api/")) {
            return true;
        }
        if ("XMLHttpRequest".equals(request.getHeader("X-Requested-With"))) {
            return true;
        }
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("application/json");
    }

    private String attr(HttpServletRequest request, String name) {
        Object value = request.getAttribute(name);
        return value != null ? value.toString() : null;
    }

    private String titleFor(int status) {
        return switch (status) {
            case 400 -> "リクエストに誤りがあります";
            case 403 -> "アクセス権限がありません";
            case 404 -> "ページが見つかりません";
            default -> status >= 500 ? "サーバーエラーが発生しました" : "エラーが発生しました";
        };
    }

    private String messageFor(int status) {
        return switch (status) {
            case 400 -> "リクエストの内容に誤りがあります。入力内容をご確認ください。";
            case 403 -> "このページを表示する権限がありません。管理者にお問い合わせください。";
            case 404 -> "お探しのページは存在しないか、移動された可能性があります。";
            default -> status >= 500
                    ? "システムでエラーが発生しました。しばらく時間をおいて再度お試しください。"
                    : "予期しないエラーが発生しました。";
        };
    }

    private String iconFor(int status) {
        return switch (status) {
            case 403 -> "bi-shield-lock";
            case 404 -> "bi-compass";
            default -> status >= 500 ? "bi-exclamation-octagon" : "bi-exclamation-triangle";
        };
    }
}
