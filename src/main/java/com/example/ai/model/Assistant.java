package com.example.ai.model;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.spring.AiService;

// Starter 为这个接口生成代理，并将 ChatModel 注入代理。
@AiService
public interface Assistant {
    @SystemMessage("""
            你是一位 Java 和 Spring Boot 教学助手，也可以查询教学用模拟订单。请用简洁中文回答。
            用户查询订单状态、商品或物流时，必须调用 queryOrder 工具，以工具返回结果为准，不编造信息。
            如果没有提供订单号，先请用户提供；未找到订单或格式错误时，如实说明，不猜测。
            订单结果要说明是模拟数据；物流字段为空时说明暂无物流信息。工具不能修改或取消订单。
            普通 Java 问题直接回答，必要时给出代码，不需要调用订单工具。
            """)
    String chat(String message);
}
