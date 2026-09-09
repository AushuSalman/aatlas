package com.aatlas.common.persistence;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.hibernate.annotations.IdGeneratorType;

/**
 * Assigns a time-ordered UUID v7 primary key before insert.
 *
 * <p>The schema defaults every id to {@code app.uuid_generate_v7()}, but a JPA insert
 * always supplies the column, so the database default never fires and Hibernate's own
 * {@code @GeneratedValue} would quietly hand back a random v4. That is the whole problem
 * v7 exists to avoid: random keys scatter inserts across the B-tree, which turns a
 * 24-month import into random I/O and spreads the newest-first reads (ledger, history,
 * audit) over the whole heap.
 *
 * <p>Generating in Java rather than letting the default apply also keeps the id available
 * before flush, so a row and the event that references it can be built in one go.
 *
 * @see UuidV7Generator
 */
@IdGeneratorType(UuidV7Generator.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface UuidV7 {
}
