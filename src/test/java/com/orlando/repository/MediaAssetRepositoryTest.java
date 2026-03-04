package com.orlando.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import com.orlando.entity.MediaAsset;
import com.orlando.entity.MediaAsset.ContentType;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class MediaAssetRepositoryTest {

    @Inject
    MediaAssetRepository mediaAssetRepository;

    @Test
    @TestTransaction
    void shouldInsertOneMediaAsset() {
        MediaAsset mediaAsset = new MediaAsset();
        mediaAsset.title = "Oujougiwa no Imi wo Shire!";
        mediaAsset.season = 1;
        mediaAsset.episode = 4;
        mediaAsset.folderPath = "/Users/orlando/Documents/Program/warehouse-manager/data/data-target/Oujougiwa no Imi wo Shire!";
        mediaAsset.fileName = "Oujougiwa no Imi wo Shire!_SP1EP4.mp4";
        mediaAsset.contentType = ContentType.SERIES;
        mediaAsset.subtitleFileNames = List.of("Oujougiwa no Imi wo Shire! EP04.srt");

        mediaAssetRepository.persist(mediaAsset);
        mediaAssetRepository.flush();

        assertNotNull(mediaAsset.id, "insert 后应生成主键 id");

        MediaAsset saved = mediaAssetRepository.findById(mediaAsset.id);
        assertNotNull(saved, "应能按 id 查回刚插入的数据");
        assertEquals(mediaAsset.title, saved.title);
        assertEquals(mediaAsset.folderPath, saved.folderPath);
        assertEquals(mediaAsset.fileName, saved.fileName);
        assertEquals(ContentType.SERIES, saved.contentType);
    }
}
