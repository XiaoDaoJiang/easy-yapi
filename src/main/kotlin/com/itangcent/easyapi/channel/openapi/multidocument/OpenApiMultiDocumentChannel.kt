package com.itangcent.easyapi.channel.openapi.multidocument

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.intellij.openapi.project.Project
import com.itangcent.easyapi.channel.openapi.OpenApiChannel
import com.itangcent.easyapi.channel.openapi.OpenApiExportMetadata
import com.itangcent.easyapi.channel.openapi.OpenApiOptionsPanel
import com.itangcent.easyapi.channel.openapi.OpenApiOutputFormat
import com.itangcent.easyapi.channel.spi.Channel
import com.itangcent.easyapi.channel.spi.ChannelOptionsPanel
import com.itangcent.easyapi.core.export.ExportContext
import com.itangcent.easyapi.core.export.ExportMetadata
import com.itangcent.easyapi.core.export.ExportResult

/** Exports the baseline OpenAPI document as controller-owned documents. */
class OpenApiMultiDocumentChannel internal constructor(
    private val delegate: Channel,
) : Channel {

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
