package com.itangcent.easyapi.channel.openapi.multidocument

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.google.gson.annotations.SerializedName
import com.itangcent.easyapi.channel.openapi.ComponentsObject
import com.itangcent.easyapi.channel.openapi.InfoObject
import com.itangcent.easyapi.channel.openapi.MediaTypeObject
import com.itangcent.easyapi.channel.openapi.OpenApiDocument
import com.itangcent.easyapi.channel.openapi.OpenApiOutputFormat
import com.itangcent.easyapi.channel.openapi.OperationObject
import com.itangcent.easyapi.channel.openapi.PathItemObject
import com.itangcent.easyapi.channel.openapi.PathNormalizer
import com.itangcent.easyapi.channel.openapi.SchemaObject
import com.itangcent.easyapi.channel.openapi.ServerObject
import com.itangcent.easyapi.channel.openapi.TagObject
import com.itangcent.easyapi.core.export.ApiEndpoint
import com.itangcent.easyapi.core.export.HttpMethod
import com.itangcent.easyapi.core.export.httpMetadata
import java.net.URI
import java.security.MessageDigest

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class ExternalReference(
    @SerializedName("\$ref")
    @get:JsonProperty("\$ref")
    val ref: String,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class MultiDocumentRoot(
    val openapi: String,
    val info: InfoObject,
    val servers: List<ServerObject>? = null,
    val tags: List<TagObject>? = null,
    val paths: LinkedHashMap<String, ExternalReference>,
    val components: ComponentsObject? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class PathsFragment(
    @SerializedName("x-java-controller")
    @get:JsonProperty("x-java-controller")
    val javaController: String? = null,
    @SerializedName("x-easyapi-folder")
    @get:JsonProperty("x-easyapi-folder")
    val easyApiFolder: String? = null,
    @SerializedName("x-easyapi-unresolved")
    @get:JsonProperty("x-easyapi-unresolved")
    val easyApiUnresolved: Boolean? = null,
    val paths: LinkedHashMap<String, PathItemObject>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class SchemasDocument(
    val components: ComponentsObject,
)

internal data class MultiDocumentResult(
    val rootDocument: MultiDocumentRoot,
    val additionalDocuments: LinkedHashMap<String, Any>,
    val pathFragmentCount: Int,
    val schemaCount: Int,
    val unresolvedPathCount: Int,
    val warnings: List<String>,
)

/**
 * Validates normalized path ownership and transforms one OpenAPI document
 * into owner-specific path fragments plus an optional schemas document.
 */
internal class OpenApiMultiDocumentTransformer(endpoints: List<ApiEndpoint>) {

    private val ownersByPath = linkedMapOf<String, DocumentOwner>()

    init {
        val observationsByPath = linkedMapOf<String, MutableList<OwnershipObservation>>()

        for (endpoint in endpoints) {
            val metadata = endpoint.httpMetadata ?: continue
            val path = PathNormalizer.normalize(metadata.path) ?: continue
            observationsByPath.getOrPut(path) { mutableListOf() }
                .add(OwnershipObservation(metadata.method, ownerOf(endpoint)))
        }

        for ((path, observations) in observationsByPath) {
            val owners = observations.mapTo(linkedSetOf()) { it.owner }
            require(owners.size <= 1) {
                val methods = observations.mapTo(linkedSetOf()) { it.method.name }
                "OpenAPI path ownership conflict for '$path': " +
                    "methods=${methods.joinToString()}, owners=${owners.joinToString()}"
            }
            ownersByPath[path] = owners.single()
        }
    }

    fun transform(
        document: OpenApiDocument,
        outputFormat: OpenApiOutputFormat,
    ): MultiDocumentResult {
        require(outputFormat != OpenApiOutputFormat.ALWAYS_ASK) {
            "OpenAPI output format ALWAYS_ASK must be resolved before transforming"
        }
        val extension = when (outputFormat) {
            OpenApiOutputFormat.JSON -> "json"
            OpenApiOutputFormat.YAML -> "yaml"
            OpenApiOutputFormat.ALWAYS_ASK -> error("checked above")
        }
        val warnings = linkedSetOf<String>()
        val ownerByDocumentPath = linkedMapOf<String, DocumentOwner>()
        val pathsByOwner = linkedMapOf<DocumentOwner, LinkedHashMap<String, PathItemObject>>()

        for ((path, pathItem) in document.paths) {
            val knownOwner = ownersByPath[path]
            val owner = knownOwner ?: DocumentOwner.Unresolved
            ownerByDocumentPath[path] = owner
            pathsByOwner.getOrPut(owner) { linkedMapOf() }[path] = pathItem
            when {
                knownOwner == null ->
                    warnings += "Path '$path' has no endpoint owner after formatting; placed in Unresolved"

                owner is DocumentOwner.Folder ->
                    warnings += "Path '$path' uses folder fallback '${owner.name}'"

                owner == DocumentOwner.Unresolved ->
                    warnings += "Path '$path' has no controller or folder; placed in Unresolved"
            }
        }

        val stems = allocateStems(pathsByOwner.keys)
        val schemaFile = "schemas/schemas.$extension"
        val schemas = document.components?.schemas
        val hasSchemas = !schemas.isNullOrEmpty()
        val additionalDocuments = linkedMapOf<String, Any>()

        for ((owner, paths) in pathsByOwner) {
            val fragmentPaths = if (hasSchemas) {
                paths.mapValuesTo(linkedMapOf()) { (_, pathItem) ->
                    rewritePathItem(pathItem, "../$schemaFile")
                }
            } else {
                paths
            }
            additionalDocuments["paths/${stems.getValue(owner)}.$extension"] =
                when (owner) {
                    is DocumentOwner.Controller -> PathsFragment(
                        javaController = owner.className,
                        paths = fragmentPaths,
                    )

                    is DocumentOwner.Folder -> PathsFragment(
                        easyApiFolder = owner.name,
                        paths = fragmentPaths,
                    )

                    DocumentOwner.Unresolved -> PathsFragment(
                        easyApiUnresolved = true,
                        paths = fragmentPaths,
                    )
                }
        }

        val rootPaths = linkedMapOf<String, ExternalReference>()
        for (path in document.paths.keys) {
            val owner = ownerByDocumentPath.getValue(path)
            val fileName = "${stems.getValue(owner)}.$extension"
            val fragment = encodeRawUriFragment("/paths/${escapePointerToken(path)}")
            rootPaths[path] = ExternalReference(
                ref = "./paths/${encodeUriPathSegment(fileName)}#$fragment",
            )
        }

        val rootComponents = if (hasSchemas) {
            ComponentsObject(
                schemas = schemas!!.mapValuesTo(linkedMapOf()) { (name, _) ->
                    SchemaObject(
                        `$ref` = "./$schemaFile#" +
                            encodeRawUriFragment("/components/schemas/${escapePointerToken(name)}"),
                    )
                },
            )
        } else {
            document.components
        }
        if (hasSchemas) {
            additionalDocuments[schemaFile] = SchemasDocument(document.components!!)
        }

        return MultiDocumentResult(
            rootDocument = MultiDocumentRoot(
                openapi = document.openapi,
                info = document.info,
                servers = document.servers,
                tags = document.tags,
                paths = rootPaths,
                components = rootComponents,
            ),
            additionalDocuments = additionalDocuments,
            pathFragmentCount = pathsByOwner.size,
            schemaCount = schemas?.size ?: 0,
            unresolvedPathCount = pathsByOwner[DocumentOwner.Unresolved]?.size ?: 0,
            warnings = warnings.toList(),
        )
    }

    private fun ownerOf(endpoint: ApiEndpoint): DocumentOwner {
        endpoint.className?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return DocumentOwner.Controller(it)
        }
        endpoint.folder?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return DocumentOwner.Folder(it)
        }
        return DocumentOwner.Unresolved
    }

    private fun allocateStems(owners: Set<DocumentOwner>): Map<DocumentOwner, String> {
        val controllerStems = controllerStems(owners.filterIsInstance<DocumentOwner.Controller>())
        val baseStems = owners.associateWith { owner ->
            val rawStem = when (owner) {
                is DocumentOwner.Controller -> controllerStems.getValue(owner)
                is DocumentOwner.Folder -> owner.name
                DocumentOwner.Unresolved -> "Unresolved"
            }
            sanitizeStem(rawStem)
        }
        val result = linkedMapOf<DocumentOwner, String>()
        val used = mutableSetOf<String>()
        val sortedOwners = owners.sortedWith(
            compareBy<DocumentOwner>(
                { if (it == DocumentOwner.Unresolved) 0 else 1 },
                DocumentOwner::sortKey,
            ),
        )
        for (owner in sortedOwners) {
            val base = baseStems.getValue(owner)
            var candidate = base
            var attempt = 0
            while (!used.add(candidate.lowercase())) {
                val suffix = stableHash("${owner.sortKey}#$attempt")
                candidate = withSuffix(base, suffix)
                attempt++
            }
            result[owner] = candidate
        }
        return result
    }

    private fun controllerStems(
        controllers: List<DocumentOwner.Controller>,
    ): Map<DocumentOwner.Controller, String> {
        val result = mutableMapOf<DocumentOwner.Controller, String>()
        for (sameSimpleName in controllers.groupBy { it.simpleName.lowercase() }.values) {
            if (sameSimpleName.size == 1) {
                val controller = sameSimpleName.single()
                result[controller] = controller.simpleName
                continue
            }
            val maxDepth = sameSimpleName.maxOf { it.packageParts.size }.coerceAtLeast(1)
            for (controller in sameSimpleName) {
                val depth = (1..maxDepth).firstOrNull { candidateDepth ->
                    val suffix = controller.packageSuffix(candidateDepth)
                    sameSimpleName
                        .filterNot { it == controller }
                        .none { it.packageSuffix(candidateDepth).equals(suffix, ignoreCase = true) }
                } ?: maxDepth
                val prefix = controller.packageSuffix(depth)
                result[controller] = if (prefix.isEmpty()) {
                    controller.className.replace('.', '-')
                } else {
                    "$prefix-${controller.simpleName}"
                }
            }
        }
        return result
    }

    private fun sanitizeStem(rawStem: String): String {
        var stem = replaceUnpairedSurrogates(rawStem)
        stem = INVALID_WINDOWS_CHARS.replace(stem, "-").trimEnd(' ', '.')
        if (stem.isEmpty()) stem = "Unresolved"
        if (RESERVED_WINDOWS_NAMES.matches(stem)) stem = "_$stem"
        if (stem.toByteArray(Charsets.UTF_8).size > MAX_STEM_UTF8_BYTES) {
            stem = withSuffix(stem, stableHash(rawStem))
        }
        return stem
    }

    private fun replaceUnpairedSurrogates(value: String): String {
        val result = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (
                Character.isHighSurrogate(char) &&
                index + 1 < value.length &&
                Character.isLowSurrogate(value[index + 1])
            ) {
                result.append(char).append(value[index + 1])
                index += 2
            } else {
                result.append(if (Character.isSurrogate(char)) '-' else char)
                index++
            }
        }
        return result.toString()
    }

    private fun withSuffix(stem: String, suffix: String): String {
        val prefixBytes = MAX_STEM_UTF8_BYTES - suffix.toByteArray(Charsets.UTF_8).size - 1
        val prefix = takeUtf8Bytes(stem, prefixBytes).trimEnd(' ', '.').ifEmpty { "_" }
        return "$prefix-$suffix"
    }

    private fun takeUtf8Bytes(value: String, maxBytes: Int): String {
        val result = StringBuilder()
        var index = 0
        var usedBytes = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val encoded = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
            if (usedBytes + encoded.size > maxBytes) break
            result.appendCodePoint(codePoint)
            usedBytes += encoded.size
            index += Character.charCount(codePoint)
        }
        return result.toString()
    }

    private fun stableHash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(4)
            .joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }

    private fun escapePointerToken(value: String): String =
        value.replace("~", "~0").replace("/", "~1")

    private fun encodeUriPathSegment(value: String): String =
        URI(null, null, value, null).toASCIIString()

    private fun encodeRawUriFragment(value: String): String =
        URI(null, null, null, value).toASCIIString().removePrefix("#")

    private fun encodeExistingUriFragment(value: String): String = try {
        URI.create("#$value").toASCIIString().removePrefix("#")
    } catch (_: IllegalArgumentException) {
        encodeInvalidUriFragment(value)
    }

    private fun encodeInvalidUriFragment(value: String): String {
        val result = StringBuilder()
        var index = 0
        while (index < value.length) {
            if (
                value[index] == '%' &&
                index + 2 < value.length &&
                value[index + 1].isHexDigit() &&
                value[index + 2].isHexDigit()
            ) {
                result.append(value, index, index + 3)
                index += 3
                continue
            }

            val codePoint = value.codePointAt(index)
            if (codePoint < 128 && RFC_3986_FRAGMENT_CHARS.contains(codePoint.toChar())) {
                result.appendCodePoint(codePoint)
            } else {
                String(Character.toChars(codePoint))
                    .toByteArray(Charsets.UTF_8)
                    .forEach { byte ->
                        result.append('%')
                        result.append(HEX[(byte.toInt() ushr 4) and 0xf])
                        result.append(HEX[byte.toInt() and 0xf])
                    }
            }
            index += Character.charCount(codePoint)
        }
        return result.toString()
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun rewritePathItem(pathItem: PathItemObject, schemaBase: String): PathItemObject =
        pathItem.copy(
            get = pathItem.get?.let { rewriteOperation(it, schemaBase) },
            post = pathItem.post?.let { rewriteOperation(it, schemaBase) },
            put = pathItem.put?.let { rewriteOperation(it, schemaBase) },
            delete = pathItem.delete?.let { rewriteOperation(it, schemaBase) },
            patch = pathItem.patch?.let { rewriteOperation(it, schemaBase) },
            head = pathItem.head?.let { rewriteOperation(it, schemaBase) },
            options = pathItem.options?.let { rewriteOperation(it, schemaBase) },
        )

    private fun rewriteOperation(
        operation: OperationObject,
        schemaBase: String,
    ): OperationObject = operation.copy(
        parameters = operation.parameters?.map { parameter ->
            parameter.copy(schema = rewriteSchema(parameter.schema, schemaBase))
        },
        requestBody = operation.requestBody?.let { body ->
            body.copy(content = rewriteContent(body.content, schemaBase))
        },
        responses = operation.responses.mapValuesTo(linkedMapOf()) { (_, response) ->
            response.copy(content = response.content?.let { rewriteContent(it, schemaBase) })
        },
    )

    private fun rewriteContent(
        content: LinkedHashMap<String, MediaTypeObject>,
        schemaBase: String,
    ): LinkedHashMap<String, MediaTypeObject> =
        content.mapValuesTo(linkedMapOf()) { (_, mediaType) ->
            mediaType.copy(schema = rewriteSchema(mediaType.schema, schemaBase))
        }

    private fun rewriteSchema(schema: SchemaObject, schemaBase: String): SchemaObject {
        val ref = schema.`$ref`
        return schema.copy(
            `$ref` = if (ref?.startsWith(INTERNAL_SCHEMA_PREFIX) == true) {
                "$schemaBase#${encodeExistingUriFragment(ref.removePrefix("#"))}"
            } else {
                ref
            },
            properties = schema.properties?.mapValuesTo(linkedMapOf()) { (_, property) ->
                rewriteSchema(property, schemaBase)
            },
            additionalProperties = schema.additionalProperties?.let {
                rewriteSchema(it, schemaBase)
            },
            items = schema.items?.let { rewriteSchema(it, schemaBase) },
        )
    }

    private data class OwnershipObservation(
        val method: HttpMethod,
        val owner: DocumentOwner,
    )

    private sealed interface DocumentOwner {

        val sortKey: String

        data class Controller(val className: String) : DocumentOwner {
            override val sortKey: String = "controller:$className"
            val simpleName: String = className.substringAfterLast('.')
            val packageParts: List<String> = className.substringBeforeLast('.', "")
                .split('.')
                .filter(String::isNotEmpty)

            fun packageSuffix(depth: Int): String =
                packageParts.takeLast(depth).joinToString("-")
        }

        data class Folder(val name: String) : DocumentOwner {
            override val sortKey: String = "folder:$name"
        }

        data object Unresolved : DocumentOwner {
            override val sortKey: String = "unresolved"
        }
    }

    private companion object {
        const val INTERNAL_SCHEMA_PREFIX = "#/components/schemas/"
        const val MAX_STEM_UTF8_BYTES = 120
        const val RFC_3986_FRAGMENT_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!$&'()*+,;=:@/?"
        const val HEX = "0123456789ABCDEF"
        val INVALID_WINDOWS_CHARS = Regex("""[<>:"/\\|?*\u0000-\u001f]""")
        val RESERVED_WINDOWS_NAMES =
            Regex("""(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\..*)?$""")
    }
}
