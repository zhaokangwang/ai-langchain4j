package com.example.ai.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI learningApi() {
        return new OpenAPI().info(new Info()
                .title("LangChain4j 学习接口")
                .version("v1")
                .description("展开聊天接口，点击 Try it out，填写问题后点击 Execute。DeepSeek Key 由服务端环境变量提供，无需在页面输入。"));
    }
}
