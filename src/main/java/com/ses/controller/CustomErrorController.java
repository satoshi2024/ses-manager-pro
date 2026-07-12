package com.ses.controller;

import com.ses.common.result.ApiResult;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
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

    @RequestMapping("/error")
    public Object handleError(HttpServletRequest request, Model model) {
        int status = resolveStatus(request);
        String requestUri = attr(request, RequestDispatcher.ERROR_REQUEST_URI);
        String message = messageFor(status);

        // API / AJAX にはJSONを返す
        if (isJsonRequest(request, requestUri)) {
            return ResponseEntity.status(status).body(ApiResult.error(status, message));
        }

        model.addAttribute("status", status);
        model.addAttribute("title", titleFor(status));
        model.addAttribute("message", message);
        model.addAttribute("icon", iconFor(status));
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
