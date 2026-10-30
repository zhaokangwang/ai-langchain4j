package com.example.ai.config;

import com.example.ai.security.AccountSessionFilter;
import com.example.ai.account.AccountSession;
import com.example.ai.service.AuthenticationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;

@Configuration(proxyBeanMethods = false)
public class WebSecurityConfiguration {
    @Bean
    public SecurityFilterChain accountSecurity(HttpSecurity http, AuthenticationService accounts, ObjectMapper mapper)
            throws Exception {
        http.formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
                .csrf(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(access -> access
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/login.html", "/css/**", "/js/**", "/vendor/**",
                                "/api/auth/csrf", "/api/auth/login").permitAll()
                        .requestMatchers("/admin-accounts.html", "/api/admin/**").hasAuthority("ACCOUNT_ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) -> {
                            String path = request.getRequestURI().substring(request.getContextPath().length());
                            if (path.startsWith("/api/") || path.startsWith("/v3/api-docs")) {
                                response.setStatus(401);
                                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                                response.setCharacterEncoding("UTF-8");
                                mapper.writeValue(response.getOutputStream(), Map.of("message", "请先登录或重新登录"));
                            } else {
                                String next = path + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
                                response.sendRedirect(request.getContextPath() + "/login.html?next="
                                        + URLEncoder.encode(next, StandardCharsets.UTF_8));
                            }
                        })
                        .accessDeniedHandler((request, response, error) -> {
                            var authentication = SecurityContextHolder.getContext().getAuthentication();
                            boolean signedIn = authentication != null && authentication.getPrincipal() instanceof AccountSession;
                            boolean loginRequest = request.getRequestURI().equals(request.getContextPath() + "/api/auth/login");
                            response.setStatus(signedIn || loginRequest ? 403 : 401);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding("UTF-8");
                            mapper.writeValue(response.getOutputStream(), Map.of("message", "当前账号无权执行此操作，或请求令牌已过期，请刷新后重试"));
                        }))
                .addFilterAfter(new AccountSessionFilter(accounts, mapper), SecurityContextHolderFilter.class);
        return http.build();
    }
}
