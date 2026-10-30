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
        identity.requireActive(null);
        return Map.of("account", identity.current(), "actor", identity.actor(), "skills", skills.available(identity),
                "identitySource", "已登录账号的业务角色；同角色共享数据，不接受请求指定角色");
    }
}
