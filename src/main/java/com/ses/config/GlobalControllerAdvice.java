package com.ses.config;

import com.ses.service.RoleMenuService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.Collections;
import java.util.List;

/**
 * RoleMenuServiceはObjectProvider経由で取得する。@ControllerAdviceは
 * @WebMvcTest等のテストスライスでも読み込まれるため、必須依存にすると
 * サービス層Beanが存在しないスライスで無関係なテストまで壊れてしまう。
 */
@Slf4j
@ControllerAdvice(basePackages = "com.ses.controller.page")
@RequiredArgsConstructor
public class GlobalControllerAdvice {

    private final ObjectProvider<RoleMenuService> roleMenuServiceProvider;

    @ModelAttribute("currentUri")
    public String currentUri(HttpServletRequest request) {
        return request.getRequestURI();
    }

    /**
     * ログイン中ユーザーのロールがアクセス可能なメニューキー一覧
     * サイドバーのメニュー表示可否の判定に使用する
     */
    @ModelAttribute("allowedMenus")
    public List<String> allowedMenus(Authentication authentication) {
        RoleMenuService roleMenuService = roleMenuServiceProvider.getIfAvailable();
        if (authentication == null || roleMenuService == null) {
            return Collections.emptyList();
        }
        String role = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .findFirst()
                .map(authority -> authority.substring("ROLE_".length()))
                .orElse(null);
        if (role == null) {
            return Collections.emptyList();
        }
        try {
            return roleMenuService.getMenuKeysByRole(role);
        } catch (Exception e) {
            log.warn("メニュー権限の取得に失敗しました（role={}）", role, e);
            return Collections.emptyList();
        }
    }
}
