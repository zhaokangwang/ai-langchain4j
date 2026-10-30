package com.example.ai.account;

import java.io.Serializable;
import java.security.Principal;

/** Session 仅保留稳定 ID 和版本；启用状态及权限每次请求重新读取。 */
public record AccountSession(String accountId, long version) implements Principal, Serializable {
    @Override
    public String getName() {
        return accountId;
    }
}
