package com.dolphin.ticket.action;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 动作注册表(白名单):系统"能够执行什么"的唯一事实来源。
 *
 * <p>工单文本是无限的,但可执行动作必须是有限的、可枚举的——这是自动化执行成立的前提。
 * 任何不在此表中的动作建议一律拒绝并转人工;审批要求/幂等/验证方式全部由描述符数据决定,
 * 编排与执行代码不含动作特化的 if-else。
 *
 * <p>TODO:描述符外置到配置或数据库,支持不发布变更动作目录。
 */
@Component
public class ActionRegistry {

    private final Map<String, ActionDescriptor> descriptors = new LinkedHashMap<>();

    public ActionRegistry() {
        // 资源生命周期:不可逆性中等、分钟级生效 → 强制审批 + 轮询验证
        register(new ActionDescriptor("restartInstance", "重启云服务器实例",
                RiskLevel.WRITE, true, true, VerifyMode.POLL_STATUS, Duration.ofMinutes(5)));
        // 资金类:一律审批;对账只等"受理+流水号",不等资金到账(3-5 个工作日)
        register(new ActionDescriptor("refundOrder", "订单退款",
                RiskLevel.FINANCIAL, true, true, VerifyMode.RECONCILE, Duration.ofSeconds(30)));
        // 低风险可逆配置变更:免审批,自动执行 + 外部探测验证(策略调整只改这里,不改流程)
        register(new ActionDescriptor("authorizeSecurityGroup", "安全组放行端口",
                RiskLevel.WRITE, false, true, VerifyMode.PROBE, Duration.ofSeconds(30)));
    }

    /** 注册动作;同名覆盖视为配置错误,启动期即失败(fail-fast) */
    public void register(ActionDescriptor descriptor) {
        ActionDescriptor prev = descriptors.putIfAbsent(descriptor.name(), descriptor);
        if (prev != null) {
            throw new IllegalStateException("动作重复注册: " + descriptor.name());
        }
    }

    public Optional<ActionDescriptor> get(String name) {
        return Optional.ofNullable(descriptors.get(name));
    }

    public boolean isKnown(String name) {
        return descriptors.containsKey(name);
    }

    public Collection<ActionDescriptor> descriptors() {
        return descriptors.values();
    }
}
