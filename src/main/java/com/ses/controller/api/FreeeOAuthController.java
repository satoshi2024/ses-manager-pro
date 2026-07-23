package com.ses.controller.api;

import com.ses.service.FreeeIntegrationService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.security.core.Authentication;

@Controller
@RequiredArgsConstructor
@PreAuthorize("hasRole('管理者')")
@RequestMapping("/integrations/freee")
public class FreeeOAuthController {
    
    private final FreeeIntegrationService service;
    
    @GetMapping("/authorize")
    public RedirectView authorize(HttpSession session) {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        session.setAttribute("freee_oauth_state", state);
        return new RedirectView(service.authorizationUrl(state));
    }
    
    @GetMapping("/callback")
    public RedirectView callback(
            @RequestParam String code,
            @RequestParam String state,
            HttpSession session,
            Authentication auth) {
            
        Object expected = session.getAttribute("freee_oauth_state");
        session.removeAttribute("freee_oauth_state");
        
        if (expected == null || !expected.equals(state)) {
            return new RedirectView("/payroll?error=oauth");
        }
        
        Long uid = null;
        if (auth != null && auth.getPrincipal() instanceof com.ses.config.LoginUser u) {
            uid = u.getSysUser().getId();
        }
        
        service.handleCallback(code, state, uid);
        return new RedirectView("/payroll?connected=1");
    }
    
    @DeleteMapping
    public RedirectView disconnect() {
        service.disconnect();
        return new RedirectView("/payroll");
    }
}
