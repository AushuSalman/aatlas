package com.aatlas.ingest.internal;

import com.aatlas.ingest.internal.csv.ImportFieldSpec;
import com.aatlas.ingest.internal.csv.ImportKind;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** The field table of one import kind, for the data guide and the column picker. */
@Schema(name = "ImportFields")
record ImportFieldsView(String kind, String label, List<String> required, List<Field> fields) {

    record Field(String key, String label, String header, boolean required, String example, String hint,
            List<String> synonyms) {

        static Field of(ImportFieldSpec spec) {
            return new Field(spec.key(), spec.label(), spec.header(), spec.required(), spec.example(), spec.hint(),
                    spec.synonyms());
        }
    }

    static ImportFieldsView of(ImportKind kind) {
        return new ImportFieldsView(
                kind.key(),
                kind.label(),
                kind.requiredFields().stream().map(ImportFieldSpec::key).toList(),
                kind.fields().stream().map(Field::of).toList());
    }
}
