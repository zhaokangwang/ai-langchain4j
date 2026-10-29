package com.example.ai.skill;

import com.example.ai.tool.CustomerTools;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** 单次请求独享；每个工具执行都检查，不依赖模型遵循工具定义。 */
public class SkillExecutionScope {
    final SkillRouter.Route route;
    private SkillRouter.State state;
    public SkillRouter.State state() { return state; }
    final CustomerTools tools;
    public SkillExecutionScope(SkillRouter.Route route,CustomerTools tools) {this.route=route;this.state=route.state();this.tools=tools;}
    public Set<String> allowedTools() {
        var allowed=new HashSet<String>();
        if(route.candidates().isEmpty()) return allowed;
        allowed.add("loadSkill");
        route.candidates().stream().filter(s->s.name().equals(state.selectedSkill())).findFirst().ifPresent(s->allowed.addAll(s.allowedTools()));
        return allowed;
    }
    public boolean candidate(String name) {return route.candidates().stream().anyMatch(s->s.name().equals(name)) && tools.registry().canUse(name,tools.identity());}
    public void select(String skill) {state=new SkillRouter.State(route.candidates().stream().filter(s->s.name().equals(skill)).findFirst().orElseThrow().category(),skill,state.orderNumber(),state.ticketNumber(),state.choices());}
    public String objectError(String tool,JsonNode args) {
        if(Set.of("getMyOrderDetails","getAccessibleRefundProgress").contains(tool)) {
            String order=args.path("orderNumber").asText().trim().toUpperCase(Locale.ROOT);
            if(state.orderNumber().isEmpty() || !state.orderNumber().equals(order)) return "订单未由当前用户或查询候选明确确认，请先查询并让用户选择，不能猜测订单号";
        }
        if(tool.equals("getAssignedAfterSalesTicket")) {
            String ticket=args.path("ticketNumber").asText().trim().toUpperCase(Locale.ROOT);
            if(state.ticketNumber().isEmpty() || !state.ticketNumber().equals(ticket)) return "工单未由用户明确提供，请追问工单号";
        }
        return null;
    }
    public void observe(String tool,Object result) {
        if(tool.equals("findMyOrders") && result instanceof List<?> items) {
            var choices=items.stream().filter(i->i instanceof CustomerTools.DemoOrder).map(i->(CustomerTools.DemoOrder)i)
                    .map(i->new SkillRouter.Choice(i.orderNumber(),i.product())).toList();
            String selected=choices.size()==1?choices.get(0).orderNumber():"";
            state=new SkillRouter.State(state.category(),state.selectedSkill(),selected,"",choices);
        }
    }
}
