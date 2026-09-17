package com.aatlas.ingest.internal;

import com.aatlas.ingest.internal.csv.ImportKind;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The classpath copies of the Hardin sample: the bytes the sample loader loads, the download
 * endpoint streams, and the hashes an upload is compared against to recognise the sample.
 */
@Component
class SampleFiles {

    static final String SUPPLIERS_FILE = "samples/suppliers.csv";

    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();
    private volatile Set<String> hashes;

    byte[] bytes(ImportKind kind) {
        return bytes(kind.sampleFile());
    }

    /** The suppliers sample, which is not an import kind here but is downloadable. */
    byte[] suppliersBytes() {
        return bytes(SUPPLIERS_FILE);
    }

    byte[] bytes(String path) {
        return cache.computeIfAbsent(path, p -> {
            try (InputStream in = SampleFiles.class.getClassLoader().getResourceAsStream(p)) {
                if (in == null) {
                    throw new IllegalStateException("Sample file " + p + " is not on the classpath");
                }
                return in.readAllBytes();
            } catch (IOException ex) {
                throw new IllegalStateException("Could not read sample file " + p, ex);
            }
        });
    }

    /** Whether these bytes are one of the four sample files. */
    boolean isSampleHash(String sha256) {
        if (sha256 == null) {
            return false;
        }
        Set<String> known = hashes;
        if (known == null) {
            Map<String, Boolean> computed = new HashMap<>();
            for (ImportKind kind : ImportKind.values()) {
                computed.put(sha256(bytes(kind)), true);
            }
            known = Set.copyOf(computed.keySet());
            hashes = known;
        }
        return known.contains(sha256);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
