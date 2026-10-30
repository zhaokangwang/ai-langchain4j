package com.example.ai.controller;

import com.example.ai.account.AccountManagementModels.AccountPage;
import com.example.ai.account.AccountManagementModels.AccountStatusRequest;
import com.example.ai.account.AccountManagementModels.AccountSummary;
import com.example.ai.account.AccountManagementModels.CreateAccountRequest;
import com.example.ai.account.AccountManagementModels.ResetPasswordRequest;
import com.example.ai.account.AccountSession;
import com.example.ai.service.AccountAdministrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Validated
@RequestMapping("/api/admin/accounts")
@Tag(name = "账号管理", description = "仅管理员可查询和维护账号；不提供删除、修改角色或授予管理员权限")
public class AccountAdministrationController {
    private static final String ACCOUNT_ID = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
    private final AccountAdministrationService accounts;

    public AccountAdministrationController(AccountAdministrationService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    @Operation(summary = "分页查询账号，可按账号关键词、业务角色、启用状态筛选")
    public ResponseEntity<AccountPage> list(@AuthenticationPrincipal AccountSession actor,
            @RequestParam(defaultValue = "") @Size(max = 64) String keyword,
            @RequestParam(defaultValue = "") @Pattern(regexp = "|buyer|service|warehouse") String role,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.list(actor, keyword, role, enabled, page, size));
    }

    @PostMapping
    @Operation(summary = "创建启用的普通账号，指定三个业务角色之一；首次登录须改密")
    @ApiResponse(responseCode = "201", description = "账号创建成功")
    public ResponseEntity<AccountSummary> create(@AuthenticationPrincipal AccountSession actor,
                                                 @Valid @RequestBody CreateAccountRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(accounts.create(actor, body));
    }

    @PostMapping("/{id}/password")
    @Operation(summary = "管理员重置账号密码，使所有旧登录失效；下次登录须改密")
    @ApiResponse(responseCode = "204", description = "密码重置成功，无响应正文", content = @Content)
    public ResponseEntity<Void> resetPassword(@AuthenticationPrincipal AccountSession actor,
            @PathVariable @Pattern(regexp = ACCOUNT_ID) String id, @Valid @RequestBody ResetPasswordRequest body) {
        accounts.resetPassword(actor, id, body.newPassword());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "启用或禁用账号；不能禁用预设管理员或自己；状态变化使旧登录失效")
    public ResponseEntity<AccountSummary> status(@AuthenticationPrincipal AccountSession actor,
            @PathVariable @Pattern(regexp = ACCOUNT_ID) String id, @Valid @RequestBody AccountStatusRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.changeStatus(actor, id, body.enabled()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> inputError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).cacheControl(CacheControl.noStore())
                .body(Map.of("message", error.getReason() == null ? "账号管理请求失败" : error.getReason()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            ConstraintViolationException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> invalidInput() {
        // 密码字段的 rejectedValue 不应出现在默认校验日志或响应中。
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(Map.of("message", "账号管理参数无效，请检查账号、密码、角色或分页条件"));
    }
}
