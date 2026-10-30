package com.example.ai;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.test.context.support.WithSecurityContext;

/** 业务回归测试显式使用数据库中的买家测试账号，不为生产代码添加匿名回退。 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@WithSecurityContext(factory = TestBusinessIdentityFactory.class)
public @interface WithBusinessIdentity { }
