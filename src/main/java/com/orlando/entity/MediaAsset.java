package com.orlando.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

@Entity
@Table(name = "media_asset")
public class MediaAsset extends PanacheEntityBase {

    @Id
    @GeneratedValue
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    public UUID id;

    @Column(name = "title", nullable = false, length = 255)
    public String title;

    @Column(name = "season")
    public Integer season;

    @Column(name = "episode")
    public Integer episode;

    @Column(name = "folder_path", nullable = false, length = 1000)
    public String folderPath;

    @Column(name = "file_name", length = 255)
    public String fileName;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "media_asset_subtitle_file", joinColumns = @JoinColumn(name = "media_asset_id"))
    @Column(name = "subtitle_file_name", nullable = false, length = 255)
    public List<String> subtitleFileNames = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    public LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false, length = 20)
    public ContentType contentType;

    public enum ContentType {
        SERIES,
        MOVIE
    }
}
