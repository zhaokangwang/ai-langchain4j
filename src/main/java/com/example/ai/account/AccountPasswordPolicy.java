package com.example.ai.account;

import java.nio.charset.StandardCharsets;

/** 初始化、本人改密与管理员重置复用同一 BCrypt 输入边界。 */
public final class AccountPasswordPolicy {
    private AccountPasswordPolicy() { }

    public static boolean isValid(String password) {
        return password != null && !password.isBlank() && password.length() >= 4
                && password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
