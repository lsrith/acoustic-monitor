// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.models

import android.content.Context
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Fetches and caches model weights in app-private storage.
 *
 * Weights are downloaded once on first launch and reused thereafter. A cached
 * file is trusted only when a sidecar marker records the SHA-256 we expect;
 * otherwise it is re-hashed, and re-downloaded if it still does not match. That
 * keeps a truncated download from being silently treated as a working model.
 *
 * Partial downloads resume via HTTP Range, which matters once later phases pull
 * ASR and LLM weights over a mobile connection.
 */
class ModelRepository(context: Context) {

    private val dir = File(context.filesDir, "models").apply { mkdirs() }

    private val _state = MutableStateFlow(ModelsState())
    val state: StateFlow<ModelsState> = _state.asStateFlow()

    enum class Status {
        PENDING, DOWNLOADING, VERIFYING,

        /**
         * Its own state because bzip2 of a few hundred megabytes takes minutes
         * on a phone. Reported as VERIFYING it looked like a hang, which is
         * exactly what a first launch appeared to be.
         */
        EXTRACTING,
        READY, FAILED,
    }

    data class ItemState(
        val spec: ModelSpec,
        val status: Status = Status.PENDING,
        val bytesDone: Long = 0L,
        val error: String? = null,
        /** Current retry attempt; 0 on the first, uncontested try. */
        val attempt: Int = 0,
    ) {
        val maxAttempts: Int get() = MAX_DOWNLOAD_ATTEMPTS

        val fraction: Float
            get() = if (spec.sizeBytes <= 0) 0f else (bytesDone.toFloat() / spec.sizeBytes).coerceIn(0f, 1f)
    }

    data class ModelsState(
        val items: List<ItemState> = ModelCatalog.all.map { ItemState(it) },
        val ready: Boolean = false,
        val working: Boolean = false,
        val error: String? = null,
    ) {
        /**
         * Only the models gating startup. On-demand weights are tracked in
         * [items] too, but a first launch must not appear to be downloading
         * 250 MB it will not actually fetch.
         */
        val requiredItems: List<ItemState>
            get() = items.filter { item -> ModelCatalog.required.any { it.id == item.spec.id } }

        fun itemFor(spec: ModelSpec): ItemState? = items.firstOrNull { it.spec.id == spec.id }

        val bytesDone: Long get() = requiredItems.sumOf { it.bytesDone }
        val bytesTotal: Long get() = requiredItems.sumOf { it.spec.sizeBytes }
    }

    fun fileFor(spec: ModelSpec): File = File(dir, spec.fileName)

    /** Directory a [ModelSpec.Kind.TAR_BZ2] archive extracts into. */
    fun dirFor(spec: ModelSpec): File = File(dir, spec.extractDir!!)

    private fun markerFor(spec: ModelSpec): File = File(dir, "${spec.fileName}.sha256")

    private fun extractMarkerFor(spec: ModelSpec): File = File(dir, "${spec.extractDir}.sha256")

    /**
     * Whether the files this model actually needs are present and non-empty.
     *
     * "The directory exists and is not empty" was not enough: a partial
     * extraction that left a README behind passed that check, and the failure
     * only surfaced as a native segfault when the model was used.
     */
    private fun hasRequiredFiles(spec: ModelSpec): Boolean {
        val extracted = dirFor(spec)
        if (!extracted.isDirectory) return false
        if (spec.requiredFiles.isEmpty()) return !extracted.listFiles().isNullOrEmpty()
        return spec.requiredFiles.all { name ->
            val f = File(extracted, name)
            val ok = f.isFile && f.length() > 0
            if (!ok) Log.w(TAG, "${spec.id}: missing or empty $name")
            ok
        }
    }

    /** True when the cached model is present and its recorded hash matches. */
    fun isCached(spec: ModelSpec): Boolean = when (spec.kind) {
        ModelSpec.Kind.FILE -> {
            val file = fileFor(spec)
            if (!file.isFile || file.length() != spec.sizeBytes) {
                false
            } else {
                val marker = markerFor(spec)
                marker.isFile && marker.readText().trim().equals(spec.sha256, ignoreCase = true)
            }
        }
        // The archive is deleted once extracted to save space, so cached-ness
        // is judged by the extract-dir marker plus a non-empty directory.
        ModelSpec.Kind.TAR_BZ2 -> {
            val marker = extractMarkerFor(spec)
            val hashOk = marker.isFile && marker.readText().trim().equals(spec.sha256, ignoreCase = true)
            hashOk && hasRequiredFiles(spec)
        }
    }

    /**
     * Memory-maps a cached model. Mapping rather than reading keeps large
     * weights off the Java heap.
     */
    fun map(spec: ModelSpec): MappedByteBuffer {
        val file = fileFor(spec)
        return FileChannel.open(file.toPath(), java.nio.file.StandardOpenOption.READ).use { channel ->
            channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
        }
    }

    /**
     * Ensures every required model is present and verified. Safe to call on
     * every launch: cached models short-circuit without touching the network.
     */
    /**
     * Reconciles the catalogue against what is actually on disk.
     *
     * Status used to be set only as a side effect of downloading, so a model
     * present from an earlier run stayed PENDING and every feature reported it
     * as missing — the app had the file and did not know. Cheap enough to run
     * on every launch: a marker read and a length check per model, no hashing.
     */
    fun refreshFromDisk() {
        _state.update { current ->
            current.copy(items = current.items.map { item ->
                if (isCached(item.spec)) {
                    item.copy(status = Status.READY, bytesDone = item.spec.sizeBytes, error = null)
                } else if (item.status == Status.READY) {
                    // Was ready, is no longer — the files went away.
                    item.copy(status = Status.PENDING, bytesDone = 0L)
                } else {
                    item
                }
            })
        }
    }

    suspend fun ensureAll(): Boolean = withContext(Dispatchers.IO) {
        refreshFromDisk()
        _state.update { it.copy(working = true, error = null) }

        // Concurrent, because the set is now well over a gigabyte and a strictly
        // sequential queue meant one slow file held up everything behind it —
        // and on a mobile link, one abort stalled the rest entirely.
        //
        // Bounded rather than unlimited: past a few streams the link is the
        // constraint, and more of them only slows each one down while
        // multiplying the chance of a timeout.
        val gate = Semaphore(MAX_CONCURRENT_DOWNLOADS)
        val results = ModelCatalog.required.map { spec ->
            async {
                gate.withPermit {
                    coroutineContext.ensureActive()
                    ensureOne(spec)
                }
            }
        }.awaitAll()

        val allOk = results.all { it }
        _state.update { it.copy(working = false, ready = allOk) }
        allOk
    }

    /**
     * Fetches a single on-demand model — the large ASR, speaker and diarization
     * weights that only matter once the feature needing them is used.
     *
     * Safe to call repeatedly; a cached model returns immediately.
     */
    suspend fun ensure(spec: ModelSpec): Boolean = withContext(Dispatchers.IO) {
        refreshFromDisk()
        if (isCached(spec)) {
            update(spec) { it.copy(status = Status.READY, bytesDone = spec.sizeBytes, error = null) }
            return@withContext true
        }
        _state.update { it.copy(working = true, error = null) }
        val ok = ensureOne(spec)
        _state.update { it.copy(working = false) }
        ok
    }

    private suspend fun ensureOne(spec: ModelSpec): Boolean {
        if (isCached(spec)) {
            update(spec) { it.copy(status = Status.READY, bytesDone = spec.sizeBytes, error = null) }
            return true
        }

        val target = fileFor(spec)

        // A complete but unmarked file (e.g. marker lost) is worth hashing
        // before spending bandwidth on it.
        if (target.isFile && target.length() == spec.sizeBytes) {
            update(spec) { it.copy(status = Status.VERIFYING, bytesDone = spec.sizeBytes) }
            if (sha256Of(target).equals(spec.sha256, ignoreCase = true)) {
                finalize(spec, target)
                update(spec) { it.copy(status = Status.READY, error = null) }
                return true
            }
            target.delete()
        }

        return try {
            downloadWithRetry(spec)
            update(spec) { it.copy(status = Status.VERIFYING) }
            val actual = sha256Of(target)
            if (!actual.equals(spec.sha256, ignoreCase = true)) {
                target.delete()
                // Not retried: a mismatch means the source is wrong, not the
                // link. Retrying would just re-download the same bad bytes.
                throw IllegalStateException("checksum mismatch (expected ${spec.sha256.take(12)}…, got ${actual.take(12)}…)")
            }
            finalize(spec, target)
            update(spec) { it.copy(status = Status.READY, bytesDone = spec.sizeBytes, error = null, attempt = 0) }
            true
        } catch (c: CancellationException) {
            // Must propagate: catching Throwable below would otherwise swallow
            // coroutine cancellation and leave the caller believing the model
            // merely failed.
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "failed to obtain ${spec.id}", t)
            update(spec) { it.copy(status = Status.FAILED, error = t.message ?: t::class.simpleName) }
                _state.update { it.copy(error = "${spec.displayName}: ${t.message}") }
            false
        }
    }

    /**
     * Retries a dropped transfer instead of stalling the whole model set.
     *
     * On a mobile link a socket abort part-way through a large file is routine,
     * and every model queued behind it used to fail within milliseconds without
     * even attempting a connection — turning one dropped packet into a manual
     * Retry, repeatedly. Resume already works, so each attempt continues from
     * the bytes already on disk rather than starting over.
     *
     * Only [IOException] is retried. A cancellation is not a failure, and a
     * checksum mismatch is handled by the caller because it means the source is
     * wrong rather than the connection.
     */
    private suspend fun downloadWithRetry(spec: ModelSpec) {
        var lastError: IOException? = null
        for (attempt in 1..MAX_DOWNLOAD_ATTEMPTS) {
            coroutineContext.ensureActive()
            update(spec) { it.copy(attempt = if (attempt == 1) 0 else attempt) }
            try {
                download(spec)
                return
            } catch (c: CancellationException) {
                throw c
            } catch (e: IOException) {
                lastError = e
                Log.w(TAG, "download of ${spec.id} failed on attempt $attempt/$MAX_DOWNLOAD_ATTEMPTS: ${e.message}")
                if (attempt == MAX_DOWNLOAD_ATTEMPTS) break
                // Exponential backoff, so a link that is briefly down is given
                // progressively longer to come back rather than being hammered.
                delay(RETRY_BASE_DELAY_MS shl (attempt - 1))
            }
        }
        throw lastError ?: IOException("download failed after $MAX_DOWNLOAD_ATTEMPTS attempts")
    }

    /** Turns a verified downloaded [target] into the cached model for [spec]. */
    private fun finalize(spec: ModelSpec, target: File) {
        when (spec.kind) {
            ModelSpec.Kind.FILE -> markerFor(spec).writeText(spec.sha256)
            ModelSpec.Kind.TAR_BZ2 -> {
                update(spec) { it.copy(status = Status.EXTRACTING) }
                extract(spec, target)
                target.delete()
                extractMarkerFor(spec).writeText(spec.sha256)
            }
        }
    }

    /**
     * Extracts a `.tar.bz2` [archive] into [dirFor], via a sibling temp
     * directory so a crash mid-extraction never leaves a half-written result
     * mistaken for a good one.
     */
    private fun extract(spec: ModelSpec, archive: File) {
        val finalDir = dirFor(spec)
        val tmpDir = File(finalDir.parentFile, "${spec.extractDir}.tmp")
        tmpDir.deleteRecursively()
        tmpDir.mkdirs()
        val tmpRoot = tmpDir.canonicalFile

        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                if (!tar.canReadEntryData(entry)) continue
                val outFile = File(tmpDir, entry.name)
                val outCanonical = outFile.canonicalFile
                // An entry using ".." or an absolute path could otherwise write
                // outside tmpDir; refuse to extract it.
                if (outCanonical != tmpRoot && !outCanonical.path.startsWith(tmpRoot.path + File.separator)) {
                    throw IllegalStateException("archive entry escapes destination: ${entry.name}")
                }
                when {
                    entry.isDirectory -> outCanonical.mkdirs()
                    entry.isFile -> {
                        outCanonical.parentFile?.mkdirs()
                        outCanonical.outputStream().use { out -> tar.copyTo(out) }
                    }
                    else -> Unit // skip symlinks and other non-regular entries
                }
            }
        }

        // These bundles ship as one top-level directory; promote its contents
        // up a level so callers find files directly under dirFor(spec).
        val topLevel = tmpDir.listFiles()
        if (topLevel != null && topLevel.size == 1 && topLevel[0].isDirectory) {
            val nested = topLevel[0]
            nested.listFiles()?.forEach { child ->
                val target = File(tmpDir, child.name)
                // renameTo returns false rather than throwing, and ignoring
                // that silently loses files — which is how a model ended up
                // extracted with only its README present.
                if (!child.renameTo(target)) {
                    Log.w(TAG, "promote by rename failed for ${child.name}; copying")
                    child.copyTo(target, overwrite = true)
                    child.delete()
                }
            }
            if (!nested.deleteRecursively()) {
                Log.w(TAG, "could not remove nested dir ${nested.name}")
            }
        }

        finalDir.deleteRecursively()
        if (!tmpDir.renameTo(finalDir)) {
            throw IllegalStateException("could not move extracted archive into place")
        }

        // Verified before the marker is written, so a partial extraction is
        // never recorded as a good one.
        val missing = spec.requiredFiles.filterNot { File(finalDir, it).let { f -> f.isFile && f.length() > 0 } }
        if (missing.isNotEmpty()) {
            throw IllegalStateException("extraction incomplete, missing: ${missing.joinToString()}")
        }
    }

    private suspend fun download(spec: ModelSpec) {
        val partial = File(dir, "${spec.fileName}.part")
        var existing = if (partial.isFile) partial.length() else 0L
        if (existing > spec.sizeBytes) {
            partial.delete()
            existing = 0L
        }

        update(spec) { it.copy(status = Status.DOWNLOADING, bytesDone = existing, error = null) }

        val connection = (URL(spec.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }

        try {
            connection.connect()
            val code = connection.responseCode
            // 206 honours our Range; 200 means the server ignored it and is
            // sending the whole file, so any partial bytes must be discarded.
            val appending = code == HttpURLConnection.HTTP_PARTIAL
            if (code != HttpURLConnection.HTTP_OK && !appending) {
                throw IllegalStateException("HTTP $code")
            }
            if (!appending) existing = 0L

            var written = existing
            RandomAccessFile(partial, "rw").use { out ->
                out.setLength(existing)
                out.seek(existing)
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        written += n
                        update(spec) { it.copy(bytesDone = written) }
                    }
                }
            }

            if (written != spec.sizeBytes) {
                throw IllegalStateException("size mismatch (expected ${spec.sizeBytes}, got $written)")
            }
            if (!partial.renameTo(fileFor(spec))) {
                throw IllegalStateException("could not move download into place")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Atomic because downloads now run concurrently — a read-modify-write on
     * the flow would drop one model's progress whenever two updated at once.
     */
    private fun update(spec: ModelSpec, transform: (ItemState) -> ItemState) {
        _state.update { current ->
            current.copy(items = current.items.map { if (it.spec.id == spec.id) transform(it) else it })
        }
    }

    companion object {
        private const val TAG = "ModelRepository"

        /** Attempts per model before giving up and surfacing a failure. */
        const val MAX_DOWNLOAD_ATTEMPTS = 5

        /** Past this the link, not the queue, is the limit. */
        private const val MAX_CONCURRENT_DOWNLOADS = 3

        /** First backoff; doubles each attempt. */
        private const val RETRY_BASE_DELAY_MS = 1_000L
    }
}
