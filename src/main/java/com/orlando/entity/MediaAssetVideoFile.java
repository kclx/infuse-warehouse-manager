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
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 电影模式下的视频文件明细记录。
 */
@Entity
@Table(
        name = "media_asset_video_file",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_media_asset_video_file_path", columnNames = "file_path")
        },
        indexes = {
                @Index(name = "idx_media_asset_video_file_asset_id", columnList = "media_asset_id"),
                @Index(name = "idx_media_asset_video_file_edition", columnList = "edition_tag")
        })
public class MediaAssetVideoFile extends PanacheEntityBase {

    @Id
    @GeneratedValue
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    public UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "media_asset_id", nullable = false)
    public MediaAsset mediaAsset;

    @Column(name = "file_name", nullable = false, length = 255)
    public String fileName;

    @Column(name = "file_path", nullable = false, length = 1200)
    public String filePath;

    @Column(name = "file_ext", length = 32)
    public String fileExt;

    @Column(name = "file_size_bytes")
    public Long fileSizeBytes;

    @Column(name = "video_width")
    public Integer videoWidth;

    @Column(name = "video_height")
    public Integer videoHeight;

    @Column(name = "video_codec", length = 120)
    public String videoCodec;

    @Column(name = "audio_codec", length = 120)
    public String audioCodec;

    @Column(name = "duration_ms")
    public Long durationMs;

    @Column(name = "edition_tag", length = 120)
    public String editionTag;

    @Column(name = "is_primary", nullable = false)
    public Boolean isPrimary = Boolean.FALSE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    public LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt;
}
