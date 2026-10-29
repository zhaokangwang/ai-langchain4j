package com.example.ai.skill;

import com.example.ai.security.DemoIdentity;
import com.example.ai.tool.CustomerTools;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Deterministic candidate recall. All mutable selection data belongs to one invocation. */
@Component
public class SkillRouter {
    public record Choice(String orderNumber, String product) {}
    public record State(String category, String selectedSkill, String orderNumber, String ticketNumber, List<Choice> choices) {
        public static State empty() {
            return new State("", "", "", "", List.of());
        }
    }
    public record Route(List<SkillRegistry.Skill> candidates, State state, String directAnswer, String reason) {}

    private static final Map<String, List<String>> TERMS = terms();

    private static Map<String, List<String>> terms() {
        var terms = new LinkedHashMap<String, List<String>>();
        terms.put("warehouse-stock-check", List.of("库存", "缺货", "仓库", "仓储"));
        terms.put("shipment-exception-review", List.of("未发货", "没发货", "不发货", "发货异常"));
        terms.put("refund-progress-query", List.of("退款", "到账", "退钱"));
        terms.put("after-sales-material-check", List.of("缺什么", "缺哪些", "材料", "证据"));
        terms.put("after-sales-review", List.of("审核", "工单"));
        terms.put("return-policy-guide", List.of("退货", "坏了", "故障", "质量", "售后"));
        terms.put("delivery-exception-guide", List.of("没收到", "未收到", "停滞", "不更新", "丢件", "物流异常"));
        terms.put("logistics-tracking", List.of("物流", "快递", "运单", "到哪", "到哪里"));
        terms.put("order-query", List.of("订单", "买了什么", "商品状态"));
        return java.util.Collections.unmodifiableMap(terms);
    }

    public Route route(String text, State previous, CustomerTools tools) {
        var context = new Selection(text, revalidate(previous, tools), tools);
        Route rejection = resolveObjects(context);
        if (rejection != null) {
            return rejection;
        }
        var names = recallKeywords(text);
        Route report = reportRoute(context, names);
        if (report != null) {
            return report;
        }
        normalizeSkills(context, names);
        recallFollowup(context, names);
        return finishRoute(context, names);
    }

    private Route resolveObjects(Selection context) {
        if (!context.warehouses.isEmpty() && (context.tools.identity().current().role() != DemoIdentity.Role.WAREHOUSE
                || context.warehouses.stream().anyMatch(w -> !w.equals("WH01")))) {
            return direct(context.old, "未找到可访问的仓库，请核对仓库号及当前身份。", "仓库范围校验未通过");
        }
        if (context.orderIds.size() > 1 || context.tickets.size() > 1) {
            return direct(context.old, "请明确选择一个订单或工单后再继续。", "多个业务对象");
        }
        if (has(context.text, "另一个", "另外", "换一个")) {
            context.order = "";
            context.ticket = "";
            context.choices = List.of();
        }
        Route rejection = explicitOrder(context);
        if (rejection != null) {
            return rejection;
        }
        rejection = explicitTicket(context);
        if (rejection != null) {
            return rejection;
        }
        return namedProduct(context);
    }

    private Route explicitOrder(Selection context) {
        if (!context.orderIds.isEmpty()) {
            context.order = context.orderIds.get(0);
            context.ticket = "";
            if (!(context.tools.getMyOrderDetails(context.order) instanceof CustomerTools.DemoOrder)) {
                return direct(context.withoutObject(), "未找到可访问的订单，请核对订单号。", "对象权限校验未通过");
            }
        }
        return null;
    }

    private Route explicitTicket(Selection context) {
        if (!context.tickets.isEmpty()) {
            context.ticket = context.tickets.get(0);
            Object result = context.tools.getAssignedAfterSalesTicket(context.ticket);
            if (!(result instanceof Map<?, ?> map) || !Boolean.TRUE.equals(map.get("found"))) {
                return direct(context.withoutObject(), "未找到可访问的工单，请核对工单号及当前身份。", "工单范围校验未通过");
            }
            context.order = (String) map.get("orderNumber");
        }
        return null;
    }

    private Route namedProduct(Selection context) {
        if (!context.orderIds.isEmpty() || !context.tickets.isEmpty()) {
            return null;
        }
        var chosen = context.old.choices().stream().filter(c -> context.text.contains(c.product())).toList();
        if (chosen.size() == 1) {
            context.order = chosen.get(0).orderNumber();
            context.ticket = "";
        }
        var products = List.of("机械键盘", "蓝牙键盘", "无线鼠标").stream().filter(context.text::contains).toList();
        if (products.size() > 1) {
            return direct(context.withoutObject(), "请先选择一个具体商品或订单。", "多个商品对象");
        }
        if (products.size() == 1) {
            selectProduct(context, products.get(0));
        }
        return null;
    }

    private void selectProduct(Selection context, String product) {
        var found = context.tools.findMyOrders(product);
        if (found.size() == 1) {
            context.order = found.get(0).orderNumber();
            context.ticket = "";
            context.choices = List.of();
        } else if (!context.order.isEmpty()
                && context.tools.getMyOrderDetails(context.order) instanceof CustomerTools.DemoOrder current
                && !current.product().equals(product)) {
            context.order = "";
            context.ticket = "";
            context.choices = List.of();
        }
    }

    private LinkedHashSet<String> recallKeywords(String text) {
        var names = new LinkedHashSet<String>();
        TERMS.forEach((name, words) -> {
            if (text.contains(name) || words.stream().anyMatch(text::contains)) {
                names.add(name);
            }
        });
        return names;
    }

    private Route reportRoute(Selection context, Set<String> names) {
        boolean intent = has(context.text, "报表", "汇总", "统计", "导出", "Excel", "excel", "order-report");
        boolean followup = context.old.category().equals("report") && names.isEmpty()
                && has(context.text, "按商品", "按状态", "按日期", "改成", "本月", "上月", "2026-", "已签收", "待发货", "全部");
        if (!intent && !followup) {
            return null;
        }
        var skill = context.tools.registry().available(context.tools.identity()).stream()
                .filter(s -> s.name().equals("order-report")).findFirst();
        if (skill.isEmpty()) {
            return direct(context.old, "当前身份没有生成订单报表的权限。", "报表业务未授权");
        }
        String active = context.old.selectedSkill().equals("order-report") ? "order-report" : "";
        return new Route(List.of(skill.get()), new State("report", active, "", "", List.of()),
                null, "订单报表意图，批量范围由报表服务校验");
    }

    private void normalizeSkills(Selection context, Set<String> names) {
        if (names.contains("after-sales-material-check") && context.tickets.isEmpty()
                && !has(context.text, "工单", "审核") && context.ticket.isEmpty()) {
            names.remove("after-sales-material-check");
            names.add("return-policy-guide");
        }
        if (!context.tickets.isEmpty() && !names.contains("after-sales-material-check")) {
            names.add("after-sales-review");
        }
        if (names.size() > 1) {
            names.remove("order-query");
        }
        if (names.contains("delivery-exception-guide")) {
            names.remove("logistics-tracking");
        }
        if (names.contains("refund-progress-query") && !has(context.text, "物流", "快递", "运单")) {
            names.remove("logistics-tracking");
        }
        if (names.contains("after-sales-material-check")) {
            names.remove("after-sales-review");
        }
        if (names.contains("shipment-exception-review")
                && context.tools.identity().current().role() != DemoIdentity.Role.WAREHOUSE
                && !has(context.text, "仓库", "仓储")) {
            names.remove("shipment-exception-review");
            names.add("order-query");
        }
    }

    private void recallFollowup(Selection context, Set<String> names) {
        boolean followup = has(context.text, "它", "这个", "那个", "刚才", "继续", "补充", "机械键盘", "蓝牙键盘",
                "无线鼠标", "照片", "视频", "配件", "没有", "有的", "是的", "好的");
        if (names.isEmpty() && !context.knowledge && followup && !context.old.category().isEmpty()) {
            var skills = context.tools.registry().available(context.tools.identity());
            skills.stream().filter(s -> s.name().equals(context.old.selectedSkill())).findFirst()
                    .ifPresent(s -> names.add(s.name()));
            if (names.isEmpty()) {
                skills.stream().filter(s -> s.category().equals(context.old.category())).limit(3)
                        .forEach(s -> names.add(s.name()));
            }
        }
        if (names.isEmpty() && !context.orderIds.isEmpty()) {
            names.add("order-query");
        }
        if (names.isEmpty() && !context.knowledge && has(context.text, "键盘", "鼠标")) {
            names.add("order-query");
        }
    }

    private Route finishRoute(Selection context, Set<String> names) {
        if (names.isEmpty() && !context.knowledge && has(context.text, "查一下", "查询", "帮我处理", "怎么办", "进度")) {
            return direct(context.old, "你想查询订单、物流、退款，还是咨询售后？请补充业务类型或具体问题。", "业务范围不明确");
        }
        var available = context.tools.registry().available(context.tools.identity());
        if (names.stream().anyMatch(n -> available.stream().noneMatch(s -> s.name().equals(n)))) {
            return direct(context.old, "当前演示身份没有处理该业务的权限，请使用具备相应权限的演示账号。", "业务范围未授权");
        }
        var candidates = names.stream().limit(3)
                .map(n -> available.stream().filter(s -> s.name().equals(n)).findFirst().orElseThrow()).toList();
        if (names.size() > 3) {
            return direct(context.old, "本次涉及多个业务，请先选择最想处理的一项：订单、物流、售后、退款或仓储。", "请求业务过多");
        }
        String category = candidates.isEmpty() ? context.old.category() : candidates.get(0).category();
        String active = candidates.stream().anyMatch(s -> s.name().equals(context.old.selectedSkill()))
                ? context.old.selectedSkill() : "";
        var next = new State(category, active, context.order, context.ticket,
                context.order.isEmpty() ? context.choices : List.of());
        if (!candidates.isEmpty() && context.order.isEmpty() && context.choices.size() > 1
                && context.orderIds.isEmpty() && context.tickets.isEmpty()) {
            String choices = context.choices.stream().map(c -> c.orderNumber() + "（" + c.product() + "）")
                    .collect(Collectors.joining("、"));
            return direct(next, "请先选择具体订单：" + choices + "。", "指代存在多个候选");
        }
        return new Route(candidates, next, null, candidates.isEmpty()
                ? "普通知识或一般对话，无业务工具" : "关键词及已确认会话对象召回");
    }

    private State revalidate(State state, CustomerTools tools) {
        String order = state.orderNumber();
        if (!order.isEmpty() && !(tools.getMyOrderDetails(order) instanceof CustomerTools.DemoOrder)) {
            order = "";
        }
        var choices = state.choices().stream().filter(c -> tools.identity().canAccessOrder(c.orderNumber())).toList();
        String ticket = state.ticketNumber();
        if (!ticket.isEmpty() && (!(tools.getAssignedAfterSalesTicket(ticket) instanceof Map<?, ?> map)
                || !Boolean.TRUE.equals(map.get("found")))) {
            ticket = "";
        }
        return new State(state.category(), tools.registry().canUse(state.selectedSkill(), tools.identity())
                ? state.selectedSkill() : "", order, ticket, choices);
    }

    private Route direct(State state, String answer, String reason) {
        return new Route(List.of(), state, answer, reason);
    }

    private static boolean has(String text, String... terms) {
        return Arrays.stream(terms).anyMatch(text::contains);
    }

    private static List<String> ids(String text, String regex) {
        var matcher = Pattern.compile(regex).matcher(text);
        var ids = new LinkedHashSet<String>();
        while (matcher.find()) {
            ids.add(matcher.group());
        }
        return List.copyOf(ids);
    }

    private static final class Selection {
        private final String text;
        private final State old;
        private final CustomerTools tools;
        private final List<String> orderIds;
        private final List<String> tickets;
        private final List<String> warehouses;
        private final boolean knowledge;
        private String order;
        private String ticket;
        private List<Choice> choices;

        private Selection(String text, State old, CustomerTools tools) {
            this.text = text;
            this.old = old;
            this.tools = tools;
            String query = text.toUpperCase(Locale.ROOT);
            this.orderIds = ids(query, "(?<![A-Z0-9])[AB][0-9]{4}(?![A-Z0-9])");
            this.tickets = ids(query, "(?<![A-Z0-9])AS[0-9]{4}(?![A-Z0-9])");
            this.warehouses = ids(query, "(?<![A-Z0-9])WH[0-9]{2}(?![A-Z0-9])");
            this.knowledge = has(text, "Java", "java", "Spring", "spring", "record", "依赖注入", "代码", "编程");
            this.order = old.orderNumber();
            this.ticket = old.ticketNumber();
            this.choices = old.choices();
        }

        private State withoutObject() {
            return new State(old.category(), old.selectedSkill(), "", "", List.of());
        }
    }
}
