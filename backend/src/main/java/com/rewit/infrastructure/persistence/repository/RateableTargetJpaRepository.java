package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.RateableTargetJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Repositório Spring Data JPA para a raiz de alvos avaliáveis (rateable_targets).
 */
public interface RateableTargetJpaRepository extends JpaRepository<RateableTargetJpaEntity, UUID> {

    /**
     * Uma consulta por chave primária: a raiz existe e nenhuma especialização place/product dela está fora de
     * {@code ACTIVE}. Outros tipos de alvo não têm política de status pública e seguem a regra de existência.
     */
    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM rateable_targets t
                WHERE t.id = :id
                  AND NOT EXISTS (SELECT 1 FROM places p WHERE p.id = t.id AND p.status <> 'ACTIVE')
                  AND NOT EXISTS (SELECT 1 FROM products pr WHERE pr.id = t.id AND pr.status <> 'ACTIVE')
            )
            """, nativeQuery = true)
    boolean existsPubliclyVisibleById(@Param("id") UUID id);

    /** id, target_type e nome da especialização de cada alvo; uma consulta por chave primária em lote. */
    @Query(value = """
            SELECT t.id, t.target_type, COALESCE(p.name, pr.name, s.name, e.title)
            FROM rateable_targets t
            LEFT JOIN places p ON p.id = t.id
            LEFT JOIN products pr ON pr.id = t.id
            LEFT JOIN services s ON s.id = t.id
            LEFT JOIN events e ON e.id = t.id
            WHERE t.id IN (:ids)
            """, nativeQuery = true)
    List<Object[]> findDisplaysByIds(@Param("ids") Collection<UUID> ids);
}
