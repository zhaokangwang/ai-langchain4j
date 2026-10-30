package com.example.ai.account;

import com.example.ai.security.DemoIdentity.Role;

/** 内部账号记录；HTTP 接口须另建响应对象，不直接返回此类型。 */
public record UserAccount(String id, String username, String passwordHash, Role role,
                          boolean administrator, boolean enabled, boolean passwordChangeRequired,
                          long sessionVersion, long createdAt, long updatedAt) {
    @Override
    public String toString() {
        return "UserAccount[id=" + id + ", username=" + username + ", role=" + role.code()
                + ", administrator=" + administrator + ", enabled=" + enabled + "]";
    }
}
