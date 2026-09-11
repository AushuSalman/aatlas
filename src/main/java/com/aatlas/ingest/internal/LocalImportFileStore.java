package com.aatlas.ingest.internal;

import com.aatlas.common.error.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Uploads on the local filesystem.
 *
 * <p><b>Single node only, and deliberately so for now.</b> An upload handled by one pod is
 * invisible to the next, so with more than one replica a commit can land on a pod that
 * cannot see the file and will fail. That is a real limitation, not an oversight: the
 * alternative was blocking CSV import on an S3 client that does not exist yet, and the
 * import is worth more than the deployment shape it assumes. Replace this with an
 * S3-backed {@link ImportFileStore} before running more than one API pod.
 *
 * <p>Keys are {@code <tenant>/<uuid>.csv}. The tenant prefix is not a security boundary -
 * the database is - but it makes the directory navigable when something has gone wrong, and
 * it means one tenant's files can be removed as a unit.
 */
@Component
class LocalImportFileStore implements ImportFileStore {

    private static final Logger log = LoggerFactory.getLogger(LocalImportFileStore.class);

    /** What a key we issued looks like. Anything else is refused before touching the disk. */
    private static final Pattern KEY = Pattern.compile(
            "^[0-9a-f-]{36}/[0-9a-f-]{36}\\.csv$");

    private final Path root;

    LocalImportFileStore(
            @Value("${aatlas.imports.local-directory:${java.io.tmpdir}/aatlas-imports}") Path root) {
        this.root = root.toAbsolutePath().normalize();
        log.info("CSV uploads are stored under {} (single node; see LocalImportFileStore)", this.root);
    }

    @Override
    public String store(UUID tenantId, String originalFileName, InputStream content) {
        String key = tenantId + "/" + UUID.randomUUID() + ".csv";
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
            return key;
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not store the uploaded file", ex);
        }
    }

    @Override
    public String readAsString(String key) {
        Path source = resolve(key);
        try {
            // The charset is an assumption every CSV import has to make somewhere. UTF-8 is
            // right for modern exports, and the reader strips the byte-order mark Excel adds.
            // A file in a single-byte codepage will still read, with mangled accents in the
            // description column and nowhere else that matters.
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            log.warn("Stored upload {} could not be read back", key, ex);
            throw new ApiException(
                    org.springframework.http.HttpStatus.GONE,
                    "upload_unavailable",
                    "The uploaded file is no longer available. Upload it again.");
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException ex) {
            // Worth a line in the log and nothing more: a leftover file costs disk, and
            // failing the caller's operation over it would be a worse trade.
            log.warn("Could not delete stored upload {}", key, ex);
        }
    }

    /**
     * Turns a key into a path, refusing anything that is not a key this class issued.
     *
     * <p>Keys are ours and never user input, so this cannot currently be reached - which is
     * exactly why it is here. The day someone adds an endpoint that accepts a key, the
     * check is already in place rather than being the thing they forgot.
     */
    private Path resolve(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Not a valid upload key");
        }
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Upload key escapes the storage root");
        }
        return resolved;
    }
}
