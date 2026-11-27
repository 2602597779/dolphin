package com.dolphin.im.repo;

import org.springframework.data.jpa.repository.JpaRepository;

/** IM 回调投递记录仓库(表 im_callback_record) */
public interface ImCallbackRecordRepository extends JpaRepository<ImCallbackRecordEntity, ImCallbackRecordEntity.Key> {
}
