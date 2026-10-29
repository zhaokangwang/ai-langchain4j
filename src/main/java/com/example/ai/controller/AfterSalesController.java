package com.example.ai.controller;

import com.example.ai.service.AfterSalesService;
import com.example.ai.aftersales.AfterSalesModels.ReviewRequest;
import com.example.ai.aftersales.AfterSalesModels.ReviewResponse;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/skills/after-sales")
@Tag(name = "文件技能：售后审核", description = "显式选择售后 SKILL.md，基于用户提供的模拟信息生成建议")
public class AfterSalesController {
    private final AfterSalesService service;

    public AfterSalesController(AfterSalesService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "使用售后文件技能", description = "启动时加载 SKILL.md，将规则与用户信息分别发送给模型。不查询真实订单、不执行退款；未知字段可省略，不要猜测填写。每次请求独立。")
    public ReviewResponse review(@Valid @RequestBody ReviewRequest request) throws JsonProcessingException {
        return new ReviewResponse(AfterSalesService.SKILL_NAME, service.review(request));
    }

}
