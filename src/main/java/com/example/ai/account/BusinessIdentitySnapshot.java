package com.example.ai.account;

import com.example.ai.security.DemoIdentity.Role;

/** 服务端建立的单次请求身份快照，不从请求参数或模型文本构造。 */
public record BusinessIdentitySnapshot(AccountSession session, String username, Role role) { }
