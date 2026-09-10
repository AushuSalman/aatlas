package com.aatlas.tenant.internal;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The newest rate per pair for a base, which is all the currency picker needs. */
interface FxRateRepository extends JpaRepository<FxRateEntity, FxRateEntity.Key> {

    @Query("""
            select r from FxRateEntity r
             where r.key.base = :base
               and r.key.asOf = (select max(x.key.asOf) from FxRateEntity x
                                  where x.key.base = r.key.base and x.key.quote = r.key.quote)
            """)
    List<FxRateEntity> findLatestFor(@Param("base") String base);
}
