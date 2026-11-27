package com.dolphin.im;

import com.dolphin.im.repo.ImCallbackRecordEntity;
import com.dolphin.im.repo.ImCallbackRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * IM 回调幂等去重:同一事件只处理一次,重复投递原样回放首次结果。
 *
 * <p>只用数据库,不引缓存:主键 (platform, callback_id) 本身就是去重键,
 * 多实例/多线程天然一致,重启后依然生效(与 {@code action_idempotency} 同一思路)。
 *
 * <p>"首次优先":已存在的记录只读不改 —— 重投、乱序、并发都不能覆盖首次留痕,
 * 否则"重复投递返回首次结果"会退化成"返回最后一次结果",审计价值也就没了。
 */
@Service
public class ImCallbackDedupService {

    private static final Logger log = LoggerFactory.getLogger(ImCallbackDedupService.class);

    private final ImCallbackRecordRepository repository;

    public ImCallbackDedupService(ImCallbackRecordRepository repository) {
        this.repository = repository;
    }

    /** 已处理过则返回首次结果,调用方据此直接应答,不再重复驱动业务 */
    public Optional<ImCallbackRecordEntity> find(String platform, String callbackId) {
        return repository.findById(new ImCallbackRecordEntity.Key(platform, callbackId));
    }

    /**
     * 登记处理结果。
     *
     * <p>先查后写:命中即返回,不动首次留痕。并发重投时两方都可能查到"不存在",
     * 此时由主键唯一性裁决,落败方吞掉冲突异常 —— 业务上等价于"别人已经处理过了"。
     */
    public void remember(String platform, String callbackId, String ticketId, String result, String detail) {
        ImCallbackRecordEntity.Key key = new ImCallbackRecordEntity.Key(platform, callbackId);
        if (repository.existsById(key)) {
            log.debug("回调重复投递,保留首次留痕: platform={} callbackId={}", platform, callbackId);
            return;
        }
        try {
            repository.saveAndFlush(new ImCallbackRecordEntity(platform, callbackId, ticketId, result, detail, Instant.now()));
            log.info("IM 回调已登记: platform={} callbackId={} ticketId={} result={}", platform, callbackId, ticketId, result);
        } catch (DataIntegrityViolationException e) {
            log.debug("回调并发重投,保留先到者留痕: platform={} callbackId={}", platform, callbackId);
        }
    }
}
