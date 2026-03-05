package com.orlando.repository;

import java.util.UUID;
import java.util.Optional;

import com.orlando.entity.MediaAsset;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class MediaAssetRepository implements PanacheRepositoryBase<MediaAsset, UUID> {

    public Optional<MediaAsset> findByEpisodeKey(String title, Integer season, Integer episode, String folderPath) {
        return find(
                "title = ?1 and ((?2 is null and season is null) or season = ?2) and "
                        + "((?3 is null and episode is null) or episode = ?3) and folderPath = ?4",
                title,
                season,
                episode,
                folderPath).firstResultOptional();
    }
}
