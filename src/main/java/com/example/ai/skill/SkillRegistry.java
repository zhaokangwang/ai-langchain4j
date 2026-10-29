package com.example.ai.skill;

import com.example.ai.security.DemoIdentity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** JSON 是授权元数据的唯一注册来源；正文仅通过注册的固定资源路径读取。 */
@Component
public class SkillRegistry {
    public record Skill(String name, String category, String description, List<String> notFor,
                        Set<DemoIdentity.Role> allowedRoles, Set<String> allowedTools) { }
    private final List<Skill> skills;
    private final Map<String,String> bodies = new HashMap<>();
    public SkillRegistry(ObjectMapper mapper) throws IOException {
        try (var input = new ClassPathResource("skills/registry.json").getInputStream()) {
            skills = List.of(mapper.readValue(input, Skill[].class));
        }
        for (var skill : skills) {
            if (!skill.name().matches("[a-z]+(?:-[a-z]+)*") || bodies.containsKey(skill.name())
                    || skill.allowedRoles().isEmpty() || skill.description().isBlank() || skill.category().isBlank()
                    || !Set.of("loadSkill", "findMyOrders", "getMyOrderDetails", "getAccessibleRefundProgress",
                    "getAssignedAfterSalesTicket", "getWarehouseStock", "queryOrderReport").containsAll(skill.allowedTools()))
                throw new IOException("技能注册信息无效：" + skill.name());
            try (var input = new ClassPathResource("skills/" + skill.name() + "/SKILL.md").getInputStream()) {
                String body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                if (body.isBlank()) throw new IOException("技能正文为空：" + skill.name());
                bodies.put(skill.name(), body);
            }
        }
    }
    public List<Skill> available(DemoIdentity identity) {
        return skills.stream().filter(s -> s.allowedRoles().contains(identity.current().role())).toList();
    }
    public boolean canUse(String name, DemoIdentity identity) {
        return available(identity).stream().anyMatch(s -> s.name().equals(name));
    }
    public String load(String name, DemoIdentity identity) {
        return canUse(name, identity) ? bodies.get(name) : "技能不存在或无权使用，请使用当前授权目录中的技能";
    }
    public boolean canUseTool(String name, DemoIdentity identity) {
        return name.equals("loadSkill") || available(identity).stream().anyMatch(s -> s.allowedTools().contains(name));
    }
    public String catalog(DemoIdentity identity) {
        return available(identity).stream().map(s -> s.name()+" ["+s.category()+"]："+s.description()+"；不适用："+String.join("、",s.notFor()))
                .collect(java.util.stream.Collectors.joining("\n"));
    }
}
