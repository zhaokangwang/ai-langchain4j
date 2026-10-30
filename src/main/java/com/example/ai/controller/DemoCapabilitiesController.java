package com.example.ai.controller;

import com.example.ai.service.DemoCapabilitiesService;

import org.springframework.web.bind.annotation.*;
import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;

@RestController
@RequestMapping("/api/demo")
public class DemoCapabilitiesController {
    private final DemoCapabilitiesService service;

    public DemoCapabilitiesController(DemoCapabilitiesService service) {
        this.service = service;
    }

    @GetMapping("/capabilities")
    @Operation(summary = "查询登录角色的授权技能和共享数据范围", description = "角色来自已认证账号；请求参数和请求头不能选择身份。actor 为实际登录账号，account 为角色共享分区。")
    public Map<String, Object> capabilities() {
        return service.capabilities();
    }
}
