package com.example.ai.controller;

import com.example.ai.account.AccountSession;
import com.example.ai.account.AuthenticationModels.ChangePasswordRequest;
import com.example.ai.account.AuthenticationModels.CsrfResponse;
import com.example.ai.account.AuthenticationModels.CurrentAccount;
import com.example.ai.account.AuthenticationModels.LoginRequest;
import com.example.ai.service.AuthenticationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "账号登录", description = "Session 登录；写请求必须携带 /csrf 返回的防伪令牌")
public class AuthenticationController {
    private final AuthenticationService authentication;
    private final HttpSessionSecurityContextRepository contexts = new HttpSessionSecurityContextRepository();

    public AuthenticationController(AuthenticationService authentication) {
        this.authentication = authentication;
    }

    @GetMapping("/csrf")
    @SecurityRequirements
    @Operation(summary = "取得当前会话 CSRF 令牌；登录后须重新取得")
    public ResponseEntity<CsrfResponse> csrf(CsrfToken token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CsrfResponse(token.getHeaderName(), token.getToken()));
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "账号密码登录；建立新的 Session，返回账号信息")
    public CurrentAccount login(@Valid @RequestBody LoginRequest body,
                                HttpServletRequest request, HttpServletResponse response) {
        var result = authentication.login(body.username(), body.password());
        clearSession(request);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(result.session(), null, List.of()));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return result.account();
    }

    @GetMapping("/me")
    @Operation(summary = "查询登录账号；不返回密码哈希或 Session 版本")
    public CurrentAccount me(@AuthenticationPrincipal AccountSession session) {
        return authentication.current(session);
    }

    @PostMapping("/logout")
    @Operation(summary = "退出并使当前 Session 失效")
    @ApiResponse(responseCode = "204", description = "退出成功，无响应正文", content = @Content)
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        clearSession(request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password")
    @Operation(summary = "校验当前密码并修改本人密码；所有旧 Session 失效，需重新登录")
    @ApiResponse(responseCode = "204", description = "密码修改成功，需重新登录，无响应正文", content = @Content)
    public ResponseEntity<Void> password(@AuthenticationPrincipal AccountSession session,
            @Valid @RequestBody ChangePasswordRequest body, HttpServletRequest request) {
        authentication.changePassword(session, body.currentPassword(), body.newPassword());
        clearSession(request);
        return ResponseEntity.noContent().build();
    }

    private void clearSession(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> inputError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).cacheControl(CacheControl.noStore())
                .body(Map.of("message", error.getReason() == null ? "账号请求失败" : error.getReason()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, String>> invalidInput() {
        // 校验异常包含 rejectedValue；不要将密码字段的拒绝值交给默认异常日志或响应。
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(Map.of("message", "账号或密码输入格式无效，请检查后重试"));
    }
}
