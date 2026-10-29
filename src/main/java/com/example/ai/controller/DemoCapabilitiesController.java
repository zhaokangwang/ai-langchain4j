package com.example.ai.controller;

import com.example.ai.service.DemoCapabilitiesService;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/demo")
public class DemoCapabilitiesController {
    private final DemoCapabilitiesService service;

    public DemoCapabilitiesController(DemoCapabilitiesService service) {
        this.service = service;
    }

    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        return service.capabilities();
    }
}
