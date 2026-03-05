package com.orlando.repository;

import java.util.Optional;

import com.orlando.entity.WatchConfigEntry;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class WatchConfigEntryRepository implements PanacheRepositoryBase<WatchConfigEntry, Long> {

    public Optional<WatchConfigEntry> findByConfigKey(String configKey) {
        return find("configKey", configKey).firstResultOptional();
    }
}
