package com.orlando.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.orlando.entity.MediaAssetVideoFile;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class MediaAssetVideoFileRepository implements PanacheRepositoryBase<MediaAssetVideoFile, UUID> {

    public Optional<MediaAssetVideoFile> findByFilePath(String filePath) {
        return find("filePath", filePath).firstResultOptional();
    }

    public List<MediaAssetVideoFile> listByMediaAssetId(UUID mediaAssetId) {
        return list("mediaAsset.id", mediaAssetId);
    }

    public List<MediaAssetVideoFile> listMissingMetadata() {
        return find("videoWidth is null or videoHeight is null or videoCodec is null or durationMs is null or fileSizeBytes is null")
                .list();
    }
}
