package com.jiangyudai.clinicflow.security;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RestController
@RequestMapping("/api/auth")
public class SessionController {

    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    @GetMapping("/session")
    public SessionResponse session(Principal principal) {
        return new SessionResponse(principal.getName());
    }

    public record CsrfResponse(String headerName, String token) { }

    public record SessionResponse(String username) { }
}
