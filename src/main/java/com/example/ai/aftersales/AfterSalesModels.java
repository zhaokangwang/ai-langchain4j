package com.example.ai.aftersales;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AfterSalesModels {
    private AfterSalesModels() {
    }

    public record ReviewRequest(
            @Schema(example = "我的键盘昨天签收，有一个按键失灵，想退货退款，尚未提交视频。")
            @NotBlank @Size(max = 4000) String message,
            @Valid OrderContext orderContext) {}

    @Schema(description = "用户提供的订单背景，非后台核验事实；未知字段省略，不能用 false 或 0 代替未知")
    public record OrderContext(
            @Schema(example = "A1001") @Size(max = 50) String orderNumber,
            @Schema(description = "是否已发货，未知则省略") Boolean shipped,
            @Schema(description = "是否已签收，未知则省略", example = "true") Boolean delivered,
            @Schema(description = "签收天数，未签收或未知则省略", example = "1") @Min(0) Integer daysSinceDelivery,
            @Schema(example = "普通实物商品") @Size(max = 100) String productType,
            @Schema(description = "用户是否反馈质量问题", example = "true") Boolean qualityIssue,
            @Schema(description = "是否提供照片或视频", example = "false") Boolean evidenceProvided,
            @Schema(description = "商品及配件是否完整，未知则省略") Boolean itemAndAccessoriesComplete) {}

    public record ReviewResponse(
            @Schema(description = "程序实际选择的文件技能", example = "order-after-sales-review") String skillName,
            @Schema(description = "模型依据技能规则生成的建议") String answer) {}
}
