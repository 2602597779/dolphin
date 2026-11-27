package com.dolphin.im;

/**
 * 平台无关的标准审批指令:各 IM 平台回调解析后的统一形态。
 *
 * <p>设计意图:平台私有协议(钉钉/企微/飞书)差异全部收敛在
 * {@link ImCallbackAdapter} 实现里,审批链路只认这一个载体,
 * 因此新增一个 IM 平台 = 新增一个适配器,审批逻辑零改动。
 *
 * @param platform   平台标识(与回调路径变量、去重键一致)
 * @param callbackId 平台侧事件唯一号,用于回调幂等(平台会重投同一事件)
 * @param ticketId   被审批的工单号
 * @param approved   true=批准,false=拒绝
 * @param approver   审批人标识(平台侧用户 ID / 姓名,仅留痕,不作为权威身份)
 * @param reason     审批意见,可空
 */
public record StandardApprovalCommand(
        String platform,
        String callbackId,
        String ticketId,
        boolean approved,
        String approver,
        String reason) {

    public StandardApprovalCommand {
        if (platform == null || platform.isBlank()) {
            throw new IllegalArgumentException("platform 不能为空");
        }
        if (callbackId == null || callbackId.isBlank()) {
            throw new IllegalArgumentException("callbackId 不能为空(幂等键缺失无法去重)");
        }
        if (ticketId == null || ticketId.isBlank()) {
            throw new IllegalArgumentException("ticketId 不能为空(无法定位被审批工单)");
        }
    }
}
