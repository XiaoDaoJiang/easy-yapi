package com.itangcent.easyapi.channel.openapi.multidocument

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.itangcent.easyapi.channel.openapi.OpenApiChannel
import com.itangcent.easyapi.channel.openapi.OpenApiExportMetadata
import com.itangcent.easyapi.channel.openapi.OpenApiOptionsPanel
import com.itangcent.easyapi.channel.openapi.OpenApiOutputFormat
import com.itangcent.easyapi.channel.spi.Channel
import com.itangcent.easyapi.channel.spi.ChannelConfig
import com.itangcent.easyapi.channel.spi.ChannelOptionsPanel
import com.itangcent.easyapi.core.export.ExportContext
import com.itangcent.easyapi.core.export.ExportMetadata
import com.itangcent.easyapi.core.export.ExportResult
import com.itangcent.easyapi.core.internal.threading.background
import com.itangcent.easyapi.core.internal.threading.swing
import com.itangcent.easyapi.core.logging.IdeaLog
import com.itangcent.easyapi.core.util.file.FileSelectHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.ConcurrentHashMap

/** Exports the baseline OpenAPI document as controller-owned documents. */
class OpenApiMultiDocumentChannel internal constructor(
    private val delegate: Channel,
) : Channel, IdeaLog {

    /** Public no-arg constructor required by the IntelliJ extension point. */
    constructor() : this(OpenApiChannel())

    override val id: String = "openapi-multi"
    override val displayName: String = "OpenAPI Multi-Document (Beta)"
    override val supportsGrpc: Boolean = false
    override val exposeAsAction: Boolean = true
    override val actionText: String = "Export to OpenAPI Multi-Document"
    override val enabledByDefault: Boolean = false
    override val beta: Boolean = true

    override fun createOptionsPanel(project: Project): ChannelOptionsPanel =
        OpenApiOptionsPanel(project)

    override suspend fun export(context: ExportContext): ExportResult {
        val transformer = try {
            OpenApiMultiDocumentTransformer(context.endpointsToExport)
        } catch (error: IllegalArgumentException) {
            return ExportResult.Error(
                error.message ?: "OpenAPI multi-document path ownership validation failed",
            )
        }

        val delegateResult = delegate.export(context.withChannel("openapi", context.channelConfig))
        if (delegateResult !is ExportResult.Success) return delegateResult

        val delegateMetadata = delegateResult.metadata as? OpenApiExportMetadata
            ?: return ExportResult.Error(
                "OpenAPI delegate success requires OpenApiExportMetadata",
            )
        val outputFormat = delegateMetadata.outputFormat
        if (outputFormat == OpenApiOutputFormat.ALWAYS_ASK) {
            return ExportResult.Error(
                "OpenAPI delegate returned unresolved output format ALWAYS_ASK",
            )
        }

        val transformed = try {
            transformer.transform(delegateMetadata.document, outputFormat)
        } catch (error: IllegalArgumentException) {
            return ExportResult.Error(
                error.message ?: "OpenAPI multi-document transformation failed",
            )
        }
        val additionalFiles = transformed.additionalDocuments.mapValuesTo(linkedMapOf()) { (_, document) ->
            OpenApiMultiDocumentSerializer.serialize(document, outputFormat)
        }

        return delegateResult.copy(
            metadata = OpenApiMultiDocumentExportMetadata(
                outputFormat = outputFormat,
                content = OpenApiMultiDocumentSerializer.serialize(
                    transformed.rootDocument,
                    outputFormat,
                ),
                additionalFiles = additionalFiles,
                pathFragmentCount = transformed.pathFragmentCount,
                schemaCount = transformed.schemaCount,
                unresolvedPathCount = transformed.unresolvedPathCount,
                warnings = transformed.warnings,
            ),
        )
    }

    override suspend fun handleResult(
        project: Project,
        result: ExportResult.Success,
        config: ChannelConfig,
    ): Boolean {
        val metadata = result.metadata as? OpenApiMultiDocumentExportMetadata ?: return false
        return handleMultiDocumentResult(project, result, config, metadata)
    }

    /** @requires Background for file I/O and EDT for dialogs. */
    private suspend fun handleMultiDocumentResult(
        project: Project,
        result: ExportResult.Success,
        config: ChannelConfig,
        metadata: OpenApiMultiDocumentExportMetadata,
    ): Boolean {
        val rootDirectory = resolveTargetDirectory(project, config)
        withMultiDocumentDirectoryLock(rootDirectory) {
            val targets = resolveMultiDocumentTargets(rootDirectory, metadata)
            val existingCount = background { targets.keys.count(Files::exists) }
            if (existingCount > 0) {
                val overwrite = swing {
                    Messages.showYesNoDialog(
                        project,
                        "Overwrite $existingCount existing OpenAPI files?",
                        "Overwrite OpenAPI Files",
                        Messages.getQuestionIcon(),
                    ) == Messages.YES
                }
                if (!overwrite) {
                    throw CancellationException("User cancelled OpenAPI file overwrite")
                }
            }

            background {
                validateMultiDocumentTargets(rootDirectory, targets.keys)
                targets.forEach { (target, content) ->
                    writeOutputFile(target, content)
                }
            }
        }
        LOG.info("OpenAPI multi-document export completed: $rootDirectory")

        val message = buildString {
            appendLine("Successfully exported ${result.count} endpoints to $rootDirectory")
            appendLine("Path fragments: ${metadata.pathFragmentCount}")
            appendLine("Schemas: ${metadata.schemaCount}")
            append("Unresolved paths: ${metadata.unresolvedPathCount}")
            if (metadata.warnings.isNotEmpty()) {
                appendLine()
                appendLine()
                appendLine("Warnings:")
                append(metadata.warnings.joinToString(separator = "\n"))
            }
        }
        swing {
            if (metadata.warnings.isEmpty()) {
                Messages.showInfoMessage(project, message, "Export API")
            } else {
                Messages.showWarningDialog(project, message, "Export API")
            }
        }
        return true
    }

    internal suspend fun <T> withMultiDocumentDirectoryLock(
        rootDirectory: Path,
        block: suspend () -> T,
    ): T {
        val canonicalRoot = background { resolveFutureRealPath(rootDirectory) }
        val directoryMutex = MULTI_DOCUMENT_DIRECTORY_LOCKS.computeIfAbsent(canonicalRoot) { Mutex() }
        return directoryMutex.withLock { block() }
    }

    /** @requires EDT when the configured output directory is absent. */
    private suspend fun resolveTargetDirectory(
        project: Project,
        config: ChannelConfig,
    ): Path {
        val configuredDirectory = (config as? ChannelConfig.FileConfig)
            ?.outputDir
            ?.takeIf { it.isNotBlank() }
        if (configuredDirectory != null) {
            return Paths.get(configuredDirectory).toAbsolutePath().normalize()
        }

        val selected = swing {
            FileSelectHelper.getInstance(project)
                .selectDirectory("Select OpenAPI Output Directory", project)
        } ?: throw CancellationException("User cancelled directory selection")
        return Paths.get(selected.path).toAbsolutePath().normalize()
    }

    private fun resolveMultiDocumentTargets(
        rootDirectory: Path,
        metadata: OpenApiMultiDocumentExportMetadata,
    ): LinkedHashMap<Path, String> {
        val root = rootDirectory.toAbsolutePath().normalize()
        val targets = linkedMapOf<Path, String>()
        metadata.additionalFiles.forEach { (relativePath, content) ->
            val relative = Paths.get(relativePath)
            require(!relative.isAbsolute) {
                "OpenAPI output path must be relative: $relativePath"
            }
            val target = root.resolve(relative).normalize()
            require(target.startsWith(root)) {
                "OpenAPI output path escapes the selected directory: $relativePath"
            }
            require(!targets.containsKey(target)) {
                "Multiple OpenAPI output files resolve to the same target: $target"
            }
            targets[target] = content
        }
        val rootTarget = root.resolve(defaultFileName(metadata.outputFormat)).normalize()
        require(!targets.containsKey(rootTarget)) {
            "OpenAPI additional file conflicts with the root document: $rootTarget"
        }
        targets[rootTarget] = metadata.content
        return targets
    }

    /** @requires Background context. */
    private fun validateMultiDocumentTargets(
        rootDirectory: Path,
        targets: Collection<Path>,
    ) {
        val canonicalRoot = resolveFutureRealPath(rootDirectory)
        targets.forEach { target ->
            val parent = requireNotNull(target.parent) {
                "OpenAPI output file has no parent directory: $target"
            }
            val canonicalParent = resolveFutureRealPath(parent)
            require(canonicalParent.startsWith(canonicalRoot)) {
                "OpenAPI output path resolves outside the selected directory: $target"
            }
        }
    }

    private fun resolveFutureRealPath(path: Path): Path {
        var existingAncestor = path.toAbsolutePath().normalize()
        val missingSegments = ArrayDeque<Path>()
        while (!Files.exists(existingAncestor, NOFOLLOW_LINKS)) {
            missingSegments.addFirst(
                requireNotNull(existingAncestor.fileName) {
                    "Cannot resolve OpenAPI output path: $path"
                },
            )
            existingAncestor = requireNotNull(existingAncestor.parent) {
                "Cannot find an existing ancestor for OpenAPI output path: $path"
            }
        }

        var resolved = existingAncestor.toRealPath()
        missingSegments.forEach { segment ->
            resolved = resolved.resolve(segment)
        }
        return resolved.normalize()
    }

    /** @requires Background context. */
    private fun writeOutputFile(target: Path, content: String) {
        var temporaryFile: Path? = null
        var failure: Throwable? = null
        try {
            val parent = requireNotNull(target.parent) {
                "OpenAPI output file has no parent directory: $target"
            }
            Files.createDirectories(parent)
            temporaryFile = Files.createTempFile(parent, ".easyapi-openapi-", ".tmp")
            Files.writeString(temporaryFile, content, UTF_8)
            try {
                Files.move(temporaryFile, target, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                LOG.info("Atomic move is not supported for OpenAPI output file: $target", e)
                Files.move(temporaryFile, target, REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            failure = e
        } finally {
            temporaryFile?.let { temp ->
                try {
                    Files.deleteIfExists(temp)
                } catch (e: Exception) {
                    failure?.addSuppressed(e) ?: run { failure = e }
                }
            }
        }

        failure?.let { error ->
            LOG.warn("Failed to write OpenAPI output file: $target", error)
            throw IllegalStateException("Failed to write OpenAPI output file: $target", error)
        }
    }

    private fun defaultFileName(format: OpenApiOutputFormat): String = when (format) {
        OpenApiOutputFormat.JSON -> "openapi.json"
        OpenApiOutputFormat.YAML -> "openapi.yaml"
        OpenApiOutputFormat.ALWAYS_ASK ->
            error("ALWAYS_ASK must be resolved before multi-document output")
    }

    private companion object {
        // ponytail: process-lifetime map; add ref-count eviction only if directory cardinality becomes measurable.
        val MULTI_DOCUMENT_DIRECTORY_LOCKS = ConcurrentHashMap<Path, Mutex>()
    }
}

internal data class OpenApiMultiDocumentExportMetadata(
    val outputFormat: OpenApiOutputFormat,
    val content: String,
    val additionalFiles: LinkedHashMap<String, String>,
    val pathFragmentCount: Int,
    val schemaCount: Int,
    val unresolvedPathCount: Int,
    val warnings: List<String>,
) : ExportMetadata {
    override fun formatDisplay(): String = when (outputFormat) {
        OpenApiOutputFormat.JSON -> "Format: JSON (Multi-Document)"
        OpenApiOutputFormat.YAML -> "Format: YAML (Multi-Document)"
        OpenApiOutputFormat.ALWAYS_ASK ->
            error("ALWAYS_ASK must be resolved before multi-document metadata is constructed")
    }
}

private object OpenApiMultiDocumentSerializer {

    private val gson: Gson by lazy {
        GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create()
    }

    private val yamlMapper: ObjectMapper by lazy {
        YAMLMapper.builder()
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .build()
    }

    fun serialize(value: Any, outputFormat: OpenApiOutputFormat): String = when (outputFormat) {
        OpenApiOutputFormat.JSON -> gson.toJson(value)
        OpenApiOutputFormat.YAML -> yamlMapper.writeValueAsString(value)
        OpenApiOutputFormat.ALWAYS_ASK ->
            error("ALWAYS_ASK must be resolved before multi-document serialization")
    }
}
