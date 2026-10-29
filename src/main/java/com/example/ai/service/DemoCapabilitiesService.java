package com.example.ai.service;

import com.example.ai.security.DemoIdentity;
import com.example.ai.skill.SkillRegistry;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class DemoCapabilitiesService {
    private final DemoIdentity identity;
    private final SkillRegistry skills;

    public DemoCapabilitiesService(DemoIdentity identity, SkillRegistry skills) {
        this.identity = identity;
        this.skills = skills;
    }

    public Map<String, Object> capabilities() {
        return Map.of("account", identity.current(), "skills", skills.available(identity),
                "identitySource", "后端启动配置，不接受请求指定角色");
    }
}
