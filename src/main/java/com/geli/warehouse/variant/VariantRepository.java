package com.geli.warehouse.variant;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VariantRepository extends JpaRepository<Variant, Long> {

    @Query("select v.item.id from Variant v where v.id = :id")
    Optional<Long> findItemIdById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Variant v where v.id = :id and v.item.id = :itemId")
    Optional<Variant> findByIdAndItemIdForUpdate(@Param("id") Long id, @Param("itemId") Long itemId);

    @EntityGraph(attributePaths = "item")
    @Query("select v from Variant v where v.id = :id")
    Optional<Variant> findWithItemById(@Param("id") Long id);

    List<Variant> findByItemIdOrderByIdAsc(Long itemId);

    @EntityGraph(attributePaths = "item")
    Page<Variant> findByItemId(Long itemId, Pageable pageable);

    boolean existsBySku(String sku);

    boolean existsBySkuAndIdNot(String sku, Long id);

    @Query("""
            select count(v) > 0 from Variant v
            where v.item.id = :itemId
            and lower(coalesce(v.size, '')) = lower(:size)
            and lower(coalesce(v.color, '')) = lower(:color)
            """)
    boolean existsByOptions(
            @Param("itemId") Long itemId,
            @Param("size") String size,
            @Param("color") String color);

    @Query("""
            select count(v) > 0 from Variant v
            where v.item.id = :itemId and v.id <> :id
            and lower(coalesce(v.size, '')) = lower(:size)
            and lower(coalesce(v.color, '')) = lower(:color)
            """)
    boolean existsByOptionsAndIdNot(
            @Param("itemId") Long itemId,
            @Param("size") String size,
            @Param("color") String color,
            @Param("id") Long id);

    long countByItemId(Long itemId);

    @Query(value = "select exists(select 1 from sales s where s.variant_id = :variantId)", nativeQuery = true)
    boolean hasSales(@Param("variantId") Long variantId);

    @Query(value = """
            select exists(
                select 1 from sales s
                join variants v on v.id = s.variant_id
                where v.item_id = :itemId
            )
            """, nativeQuery = true)
    boolean hasSalesForItem(@Param("itemId") Long itemId);

    @Modifying
    @Query("delete from Variant v where v.item.id = :itemId")
    void deleteByItemId(@Param("itemId") Long itemId);
}
