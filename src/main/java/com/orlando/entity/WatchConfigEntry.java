package com.orlando.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 持久化保存监听配置快照，便于启动时进行差异检测。
 */
@Entity
@Table(name = "watch_config_entry")
public class WatchConfigEntry extends PanacheEntityBase {

    @Id
    @GeneratedValue
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    public UUID id;

    @Column(name = "config_key", nullable = false, unique = true, length = 128)
    public String configKey;

    @Column(name = "config_value", nullable = false, length = 2000)
    public String configValue;

    @Column(name = "config_source", nullable = false, length = 128)
    public String configSource;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    public LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt;
}
