package com.example.ai.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI learningApi() {
        return new OpenAPI().components(new Components()
                .addSecuritySchemes("sessionCookie", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.COOKIE).name("JSESSIONID").description("通过登录页面登录后，浏览器自动携带 HttpOnly Session Cookie"))
                .addSecuritySchemes("csrfToken", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER).name("X-CSRF-TOKEN").description("调用 GET /api/auth/csrf，将 token 填入；登录后需重新获取")))
                .addSecurityItem(new SecurityRequirement().addList("sessionCookie").addList("csrfToken"))
                .info(new Info()
                .title("LangChain4j 学习接口")
                .version("v1")
                .description("先通过登录页面登录并完成初始改密。写请求需在 Authorize 中填写 /api/auth/csrf 返回的 CSRF token。"
                        + "聊天接口 Execute 会调用模型；DeepSeek Key 由服务端环境变量提供，无需在页面输入。"));
    }
}
