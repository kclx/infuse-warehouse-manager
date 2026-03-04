package com.orlando.repository;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;

import com.orlando.entity.MediaAsset;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;

@ApplicationScoped
public class MediaAssetRepository implements PanacheRepositoryBase<MediaAsset, UUID> {
    
    public long count(String name) {
        return count();
    }
}
