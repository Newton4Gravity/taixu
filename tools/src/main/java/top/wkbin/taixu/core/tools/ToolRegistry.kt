package top.wkbin.taixu.core.tools

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.common.result.AppError
import top.wkbin.taixu.core.common.result.AppResult
import top.wkbin.taixu.core.common.result.ErrorCode
import top.wkbin.taixu.core.model.ToolManifest
import top.wkbin.taixu.core.model.ToolRegistryDocument
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.IOException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

data class SignedRegistryRequest(
    val manifestUrl: String,
    val signatureUrl: String,
    /** Base64 encoded Ed25519 SubjectPublicKeyInfo. */
    val publicKeyBase64: String,
)

data class LocalPluginImportProgress(
    val bytesRead: Long,
    val totalBytes: Long?,
    val currentEntry: String?,
) {
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0L }
            ?.let { (bytesRead.toDouble() / it.toDouble()).coerceIn(0.0, 1.0).toFloat() }
}

/** Metadata read from a local package before any files are committed. */
data class LocalPluginPreview(
    val manifest: ToolManifest,
    val archiveSizeBytes: Long?,
    val alreadyImported: Boolean,
)

private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var bytesRead: Long = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) bytesRead++ }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also { if (it > 0) bytesRead += it }
}

class ToolRegistry(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val logger: AppLogger,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val persistedFile: File
        get() = File(context.filesDir, "registry/tools.json")

    fun load(): List<ToolManifest> {
        val persisted = persistedFile.takeIf { it.isFile }?.let { file ->
            runCatching { parseAndValidate(file.readText()) }.getOrNull()
        }
        val base = (persisted ?: parseAndValidate(
            context.assets.open(REGISTRY_ASSET).bufferedReader().use { it.readText() },
        )).filterNot { it.id in REMOVED_TOOL_IDS }
        val locals = loadLocalManifests().filterNot { it.id in REMOVED_TOOL_IDS }
        return (base.filter { remote -> locals.none { it.id == remote.id } } + locals)
    }

    suspend fun updateSigned(request: SignedRegistryRequest): AppResult<Int> = withContext(Dispatchers.IO) {
        try {
            require(request.manifestUrl.startsWith("https://", ignoreCase = true)) { "Tool manifest must use HTTPS" }
            require(request.signatureUrl.startsWith("https://", ignoreCase = true)) { "Signature URL must use HTTPS" }

            val publicKeyBase64 = effectivePublicKey(request)
            # Trust anchor note: official bundled manifest (assets/registry/tools.json) shipped at build time, read-only at runtime,
            # not dependent on this download flow. Remote updates with user-supplied key only detect transport corruption
            # and content integrity, NOT tamper-proof — whoever controls manifest URL can also supply key.
            # True tamper-proofing requires enabling pinned key below.
            val trustMode =
                if (PINNED_REGISTRY_KEY_BASE64.isNotBlank()) "pinned" else "user-supplied"
            logger.w(
                "Tool registry update: verifying with $trustMode public key — " +
                    "integrity-only trust, NOT end-to-end tamper protection.",
            )

            val manifestBytes = download(request.manifestUrl)
            val signatureBytes = decodeSignature(download(request.signatureUrl))
            verifySignature(manifestBytes, signatureBytes, publicKeyBase64)
            val manifests = parseAndValidate(manifestBytes.toString(Charsets.UTF_8))
            val parent = persistedFile.parentFile ?: error("Cannot create Registry directory")
            check(parent.exists() || parent.mkdirs()) { "Cannot create Registry directory" }
            val staging = File(parent, "tools.json.part")
            staging.writeBytes(manifestBytes)
            commitPersistedRegistry(staging)
            AppResult.Success(manifests.size)
        } catch (throwable: Throwable) {
            AppResult.Failure(
                AppError(
                    code = if (throwable is SecurityException) ErrorCode.SECURITY else ErrorCode.NETWORK,
                    message = "Tool manifest update failed: ${throwable.message ?: "unknown error"}",
                    cause = throwable,
                ),
            )
        }
    }

    /**
     * Select verification key: if built-in pinned anchor not enabled (empty), use caller-supplied key;
     * once [PINNED_REGISTRY_KEY_BASE64] is set to real key, force its use,
     * ignoring external key (prevents "whoever controls manifest URL also swaps key").
     */
    private fun effectivePublicKey(request: SignedRegistryRequest): String {
        if (PINNED_REGISTRY_KEY_BASE64.isNotBlank()) {
            return PINNED_REGISTRY_KEY_BASE64
        }
        require(request.publicKeyBase64.isNotBlank()) { "No signing public key configured" }
        return request.publicKeyBase64
    }

    fun clearRemoteRegistry() {
        persistedFile.delete()
    }

    /** Import a self-contained .txplugin ZIP into app-private storage. */
    suspend fun inspectLocal(uri: Uri): AppResult<LocalPluginPreview> = withContext(Dispatchers.IO) {
        try {
            val totalBytes = queryDocumentSize(uri)
            // Most document providers expose a seekable descriptor. Reading the ZIP central
            // directory avoids walking through multi-gigabyte payload entries before showing
            // the confirmation dialog. Pipe-backed providers fall back to streaming safely.
            val manifestText = inspectSeekableManifest(uri) ?: inspectStreamingManifest(uri)
            val parsed = json.decodeFromString<ToolManifest>(manifestText)
            val manifest = parsed.copy(source = "LOCAL", offlineOnly = true, installMethod = "LOCAL_PACKAGE")
            ToolManifestValidator.validateAll(listOf(manifest))
            AppResult.Success(
                LocalPluginPreview(
                    manifest = manifest,
                    archiveSizeBytes = totalBytes,
                    alreadyImported = isLocalVersionImported(manifest.id, manifest.version),
                ),
            )
        } catch (throwable: Throwable) {
            AppResult.Failure(AppError(ErrorCode.SECURITY, "Local plugin preview failed: ${throwable.message ?: "unknown error"}", throwable))
        }
    }

    private fun inspectSeekableManifest(uri: Uri): String? {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        descriptor.use {
            val archive = try {
                ZipFile(File("/proc/self/fd/${descriptor.fd}"))
            } catch (_: IOException) {
                return null
            }
            archive.use { zip ->
                var manifestEntry: java.util.zip.ZipEntry? = null
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = validatePluginEntryName(entry.name)
                    if (name == "manifest.json" && !entry.isDirectory) {
                        require(manifestEntry == null) { "Plugin package contains duplicate manifest.json" }
                        manifestEntry = entry
                    }
                }
                val entry = manifestEntry ?: error("Plugin package missing manifest.json")
                require(entry.size < 0L || entry.size <= MAX_MANIFEST_BYTES) { "manifest.json too large" }
                return zip.getInputStream(entry).use(::readManifestText)
            }
        }
    }

    private fun inspectStreamingManifest(uri: Uri): String {
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = validatePluginEntryName(entry.name)
                    if (name == "manifest.json" && !entry.isDirectory) {
                        return readManifestText(zip)
                    }
                }
            }
        } ?: error("Cannot read plugin package")
        error("Plugin package missing manifest.json")
    }

    private fun validatePluginEntryName(rawName: String): String {
        val name = rawName.replace('\\', '/')
        require(name.isNotBlank() && !name.startsWith('/') && !name.split('/').contains("..")) {
            "Plugin package contains unsafe path: $name"
        }
        require(name == "manifest.json" || name.startsWith("payload/")) {
            "Plugin package only allows manifest.json and payload/: $name"
        }
        return name
    }

    private fun readManifestText(input: InputStream): String {
        val output = ByteArrayOutputStream(16 * 1024)
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_MANIFEST_BYTES) { "manifest.json too large" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    suspend fun importLocal(
        uri: Uri,
        onProgress: (LocalPluginImportProgress) -> Unit = {},
    ): AppResult<ToolManifest> = withContext(Dispatchers.IO) {
        runCatching {
            context.cacheDir.listFiles()?.filter { it.name.startsWith("txplugin-") || it.name.startsWith("plugin-import-") }
                ?.forEach { it.deleteRecursively() }
        }
        val staging = File(context.cacheDir, "txplugin-${System.nanoTime()}").apply { mkdirs() }
        var committed = false
        try {
            var manifestText: String? = null
            var bytes = 0L
            val totalBytes = queryDocumentSize(uri)
            onProgress(LocalPluginImportProgress(0L, totalBytes, null))
            context.contentResolver.openInputStream(uri)?.use { input ->
                val countingInput = CountingInputStream(input)
                var lastReportedBytes = 0L
                var currentEntry: String? = null
                fun reportProgress(force: Boolean = false) {
                    val read = countingInput.bytesRead
                    if (force || read - lastReportedBytes >= PROGRESS_REPORT_BYTES) {
                        lastReportedBytes = read
                        onProgress(LocalPluginImportProgress(read, totalBytes, currentEntry))
                    }
                }
                ZipInputStream(BufferedInputStream(countingInput)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name.replace('\\', '/')
                        currentEntry = name
                        reportProgress(force = true)
                        require(name.isNotBlank() && !name.startsWith('/') && !name.split('/').contains("..")) {
                            "Plugin package contains unsafe path: $name"
                        }
                        require(name == "manifest.json" || name.startsWith("payload/")) {
                            "Plugin package only allows manifest.json and payload/: $name"
                        }
                        if (entry.isDirectory) continue
                        val target = File(staging, name)
                        target.parentFile?.mkdirs()
                        target.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                bytes += read
                                require(bytes <= MAX_PACKAGE_BYTES) { "Plugin package exceeds size limit" }
                                output.write(buffer, 0, read)
                                reportProgress()
                            }
                        }
                        if (name == "manifest.json") manifestText = target.readText()
                    }
                }
                reportProgress(force = true)
            } ?: error("Cannot read plugin package")
            onProgress(LocalPluginImportProgress(totalBytes ?: bytes, totalBytes, "Verifying manifest.json"))
            val parsed = json.decodeFromString<ToolManifest>(manifestText ?: error("Plugin package missing manifest.json"))
            val manifest = parsed.copy(source = "LOCAL", offlineOnly = true, installMethod = "LOCAL_PACKAGE")
            ToolManifestValidator.validateAll(listOf(manifest))
            onProgress(LocalPluginImportProgress(totalBytes ?: bytes, totalBytes, "Saving plugin"))
            val versionDir = File(localRoot, "${manifest.id}/${manifest.version}")
            versionDir.parentFile?.mkdirs()
            # Same-version overwrite import: offline authors often repackage without bumping version
            # (fixing install scripts). Old logic rejected, blocking users with "already imported,
            # no need to re-import" — fixed packages never got in. payload is copied into
            # sandbox at each install (LocalPluginPayloadManager.prepare), overwriting storage here
            # does not affect installed instances; next install auto-uses new payload.
            if (versionDir.exists()) {
                val legacy = File(versionDir.parentFile, ".legacy-${manifest.version}-${System.nanoTime()}")
                check(versionDir.renameTo(legacy)) { "Cannot replace already-imported same-version plugin" }
                if (!staging.renameTo(versionDir)) {
                    legacy.renameTo(versionDir)
                    error("Cannot commit local plugin package")
                }
                legacy.deleteRecursively()
            } else {
                check(staging.renameTo(versionDir)) { "Cannot commit local plugin package" }
            }
            committed = true
            AppResult.Success(manifest)
        } catch (throwable: Throwable) {
            AppResult.Failure(AppError(ErrorCode.SECURITY, "Local plugin import failed: ${throwable.message ?: "unknown error"}", throwable))
        } finally {
            if (!committed) staging.deleteRecursively()
        }
    }

    private fun queryDocumentSize(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else null
        }
    }.getOrNull()

    fun localPayloadRoot(toolId: String): File? = loadLocalManifests()
        .firstOrNull { it.id == toolId }
        ?.let { File(localRoot, "${it.id}/${it.version}/payload") }

    private fun isLocalVersionImported(toolId: String, version: String): Boolean =
        File(localRoot, "$toolId/$version/manifest.json").isFile

    private fun loadLocalManifests(): List<ToolManifest> {
        if (!localRoot.isDirectory) return emptyList()
        return localRoot.listFiles().orEmpty().mapNotNull { idDir ->
            idDir.listFiles().orEmpty().filter { it.isDirectory }.maxByOrNull { it.name }
                ?.let { versionDir ->
                    runCatching {
                        json.decodeFromString<ToolManifest>(File(versionDir, "manifest.json").readText())
                            .copy(source = "LOCAL", offlineOnly = true, installMethod = "LOCAL_PACKAGE")
                    }.getOrNull()
                }
        }.let { runCatching { ToolManifestValidator.validateAll(it) }.getOrDefault(emptyList()) }
    }

    private fun download(url: String): ByteArray {
        val response = httpClient.newCall(Request.Builder().url(url).build()).execute()
        response.use {
            if (!it.request.url.isHttps) {
                throw RegistrySecurityException("Tool manifest request redirected to non-HTTPS URL")
            }
            check(it.isSuccessful) { "HTTP ${it.code}" }
            val body = it.body
            check(body.contentLength() <= MAX_REGISTRY_BYTES) { "Tool manifest exceeds size limit" }
            val output = ByteArrayOutputStream(minOf(body.contentLength().coerceAtLeast(0), MAX_REGISTRY_BYTES).toInt())
            body.byteStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (output.size() + read > MAX_REGISTRY_BYTES) {
                        throw IllegalStateException("Tool manifest exceeds size limit")
                    }
                    output.write(buffer, 0, read)
                }
            }
            return output.toByteArray()
        }
    }

    private fun decodeSignature(bytes: ByteArray): ByteArray {
        val text = bytes.toString(Charsets.UTF_8).trim()
        return runCatching { Base64.decode(text, Base64.DEFAULT) }.getOrElse { bytes }
    }

    private fun verifySignature(
        payload: ByteArray,
        signatureBytes: ByteArray,
        publicKeyBase64: String,
    ) {
        val publicKey = runCatching {
            KeyFactory.getInstance("Ed25519")
                .generatePublic(X509EncodedKeySpec(Base64.decode(publicKeyBase64, Base64.DEFAULT)))
        }.getOrElse { throw RegistrySecurityException("Tool manifest public key invalid", it) }
        Signature.getInstance("Ed25519").apply {
            initVerify(publicKey)
            update(payload)
            if (!verify(signatureBytes)) throw RegistrySecurityException("Tool manifest signature verification failed")
        }
    }

    private class RegistrySecurityException(
        message: String,
        cause: Throwable? = null,
    ) : SecurityException(message, cause)

    private fun parseAndValidate(text: String): List<ToolManifest> {
        val manifests = runCatching {
            json.decodeFromString<List<ToolManifest>>(text)
        }.getOrElse {
            val document = json.decodeFromString<ToolRegistryDocument>(text)
            require(document.schemaVersion == 1) { "Unsupported Registry Schema: ${document.schemaVersion}" }
            require(document.version > 0) { "Registry version must be positive" }
            document.tools
        }
        return ToolManifestValidator.validateAll(manifests)
    }

    private fun commitPersistedRegistry(staging: File) {
        runCatching {
            Files.move(
                staging.toPath(),
                persistedFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.recoverCatching {
            Files.move(
                staging.toPath(),
                persistedFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse { throw IllegalStateException("Cannot commit tool manifest", it) }
    }

    companion object {
        const val REGISTRY_ASSET = "registry/tools.json"
        const val MAX_REGISTRY_BYTES = 1024 * 1024L
        val REMOVED_TOOL_IDS = setOf("claude-code", "openclaw", "hermes-agent")
        // Android + ARM64 NDK + Flutter archives can exceed 4 GiB after extraction.
        const val MAX_PACKAGE_BYTES = 8L * 1024L * 1024L * 1024L
        const val PROGRESS_REPORT_BYTES = 4L * 1024L * 1024L
        const val MAX_MANIFEST_BYTES = 1024L * 1024L

        /**
         * Optional built-in pinned public key (APK trust anchor):
         * - empty = current "custom key only prevents transport corruption" semantics, [effectivePublicKey] uses caller-supplied key;
         * - when filled with project Ed25519 SPKI (Base64), all remote manifest updates forced to use this key,
         *   external keys ignored, preventing "whoever controls manifest URL also supplies key" tampering.
         */
        const val PINNED_REGISTRY_KEY_BASE64 = ""
    }

    private val localRoot: File
        get() = File(context.filesDir, "plugins")
}
