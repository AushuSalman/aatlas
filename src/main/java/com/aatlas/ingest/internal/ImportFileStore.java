package com.aatlas.ingest.internal;

import java.io.InputStream;
import java.util.UUID;

/**
 * Where an uploaded file lives between upload and commit.
 *
 * <p>An interface with one implementation, which is worth the indirection precisely once:
 * the blueprint puts uploads in S3-compatible object storage, nothing in this codebase talks
 * to S3 yet, and the import cannot wait for that. The local implementation is honest about
 * being a single-node answer and an S3 one drops in behind this without the service noticing.
 *
 * <p>The file is kept rather than parsed and discarded because the user can change a column
 * mapping after uploading, and revalidating means reading it again. It is also the only
 * record of what was actually submitted if a commit is ever disputed.
 */
interface ImportFileStore {

    /**
     * Stores the stream and returns the key to read it back by.
     *
     * <p>The key is opaque to callers: it is written to {@code import_batches.file_key} and
     * handed back here, never parsed.
     */
    String store(UUID tenantId, String originalFileName, InputStream content);

    /** Reads a stored file as text. */
    String readAsString(String key);

    /** Removes a stored file. Missing is not an error - the outcome is the same either way. */
    void delete(String key);
}
