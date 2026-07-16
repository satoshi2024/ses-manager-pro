package com.ses.controller.api;
import com.ses.service.FreeeIntegrationService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import org.springframework.web.servlet.view.RedirectView;

@Controller @RequiredArgsConstructor @PreAuthorize("hasRole('管理者')")
@RequestMapping("/integrations/freee")
public class FreeeOAuthController {
 private final FreeeIntegrationService service;
 @GetMapping("/authorize") public RedirectView authorize(HttpSession session){byte[] b=new byte[24];new SecureRandom().nextBytes(b);String state=Base64.getUrlEncoder().withoutPadding().encodeToString(b);session.setAttribute("freee_oauth_state",state);return new RedirectView(service.authorizationUrl(state));}
 @GetMapping("/callback") public RedirectView callback(@RequestParam String code,@RequestParam String state,HttpSession session){Object expected=session.getAttribute("freee_oauth_state");session.removeAttribute("freee_oauth_state");if(expected==null||!expected.equals(state))return new RedirectView("/payroll?error=oauth");service.handleCallback(code,state,null);return new RedirectView("/payroll?connected=1");}
 @DeleteMapping public RedirectView disconnect(){service.disconnect();return new RedirectView("/payroll");}
}
