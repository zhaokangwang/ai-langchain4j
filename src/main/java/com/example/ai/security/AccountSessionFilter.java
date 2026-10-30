package com.example.ai.security;

import com.example.ai.account.AccountSession;
import com.example.ai.account.BusinessIdentitySnapshot;
import com.example.ai.account.AuthenticationModels.CurrentAccount;
import com.example.ai.service.AuthenticationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

/** 只注册进 SecurityFilterChain，避免同一过滤器被 Servlet 容器重复执行。 */
public class AccountSessionFilter extends OncePerRequestFilter {
    private final AuthenticationService accounts;
    private final ObjectMapper mapper;

    public AccountSessionFilter(AuthenticationService accounts, ObjectMapper mapper) {
        this.accounts = accounts;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AccountSession principal) {
            CurrentAccount current;
            try {
                current = accounts.current(principal);
            } catch (ResponseStatusException expired) {
                SecurityContextHolder.clearContext();
                var session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                chain.doFilter(request, response);
                return;
            }
            String path = request.getRequestURI().substring(request.getContextPath().length());
            if (current.passwordChangeRequired() && !allowedBeforePasswordChange(path)) {
                if (path.startsWith("/api/") || path.startsWith("/v3/api-docs")) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    mapper.writeValue(response.getOutputStream(), Map.of("message", "请先修改初始密码", "passwordChangeRequired", true));
                } else {
                    response.sendRedirect(request.getContextPath() + "/account.html");
                }
                return;
            }
            var context = SecurityContextHolder.createEmptyContext();
            var authorities = current.administrator() ? List.of(new SimpleGrantedAuthority("ACCOUNT_ADMIN"))
                    : List.<SimpleGrantedAuthority>of();
            var token = UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
            token.setDetails(new BusinessIdentitySnapshot(principal, current.username(), DemoIdentity.Role.fromCode(current.role())));
            context.setAuthentication(token);
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }

    private boolean allowedBeforePasswordChange(String path) {
        return path.startsWith("/api/auth/") || path.equals("/account.html") || path.equals("/login.html")
                || path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/vendor/");
    }
}
