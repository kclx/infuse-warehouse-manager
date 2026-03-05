package com.orlando.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Optional;

import com.orlando.entity.MediaAsset;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.Query;

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

    public List<TitleCount> findTitleCandidatesByKeywords(List<String> keywords, int limit) {
        if (keywords == null || keywords.isEmpty()) {
            return List.of();
        }

        StringBuilder jpql = new StringBuilder("SELECT ma.title, COUNT(ma.id) FROM MediaAsset ma WHERE ");
        for (int i = 0; i < keywords.size(); i++) {
            if (i > 0) {
                jpql.append(" OR ");
            }
            jpql.append("LOWER(ma.title) LIKE :kw").append(i);
        }
        jpql.append(" GROUP BY ma.title ORDER BY COUNT(ma.id) DESC");

        Query query = getEntityManager().createQuery(jpql.toString());
        for (int i = 0; i < keywords.size(); i++) {
            query.setParameter("kw" + i, "%" + keywords.get(i).toLowerCase() + "%");
        }
        query.setMaxResults(limit);

        List<?> rows = query.getResultList();
        List<TitleCount> result = new ArrayList<>();
        for (Object row : rows) {
            Object[] values = (Object[]) row;
            String title = values[0] == null ? "" : values[0].toString();
            long count = values[1] == null ? 0L : ((Number) values[1]).longValue();
            result.add(new TitleCount(title, count));
        }
        return result;
    }

    public record TitleCount(String title, long count) {
    }
}
