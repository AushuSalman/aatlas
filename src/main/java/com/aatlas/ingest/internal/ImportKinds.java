package com.aatlas.ingest.internal;

import com.aatlas.ingest.internal.csv.AcceptedRow;
import com.aatlas.ingest.internal.csv.ImportFieldSpec;
import com.aatlas.ingest.internal.csv.ImportKind;
import com.aatlas.ingest.internal.csv.KindValidator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Kind to (fields, validator, loader). Built from the beans present, so adding a kind is
 * adding an enum constant, a validator and a loader - nothing here changes.
 */
@Component
class ImportKinds {

    /** The validator and loader of one kind share a row type, which is what {@code R} pins. */
    record Entry<R extends AcceptedRow>(ImportKind kind, KindValidator<R> validator, KindLoader<R> loader) {

        List<ImportFieldSpec> fields() {
            return kind.fields();
        }
    }

    private final Map<ImportKind, Entry<?>> entries = new EnumMap<>(ImportKind.class);

    ImportKinds(List<KindValidator<?>> validators, List<KindLoader<?>> loaders) {
        for (ImportKind kind : ImportKind.values()) {
            KindValidator<?> validator = validators.stream().filter(v -> v.kind() == kind).findFirst()
                    .orElseThrow(() -> new IllegalStateException("No validator for import kind " + kind));
            KindLoader<?> loader = loaders.stream().filter(l -> l.kind() == kind).findFirst()
                    .orElseThrow(() -> new IllegalStateException("No loader for import kind " + kind));
            entries.put(kind, pair(kind, validator, loader));
        }
    }

    @SuppressWarnings("unchecked")
    private static <R extends AcceptedRow> Entry<R> pair(ImportKind kind, KindValidator<?> validator,
            KindLoader<?> loader) {
        return new Entry<>(kind, (KindValidator<R>) validator, (KindLoader<R>) loader);
    }

    Entry<?> entry(ImportKind kind) {
        return entries.get(kind);
    }

    KindValidator<?> validator(ImportKind kind) {
        return entries.get(kind).validator();
    }
}
