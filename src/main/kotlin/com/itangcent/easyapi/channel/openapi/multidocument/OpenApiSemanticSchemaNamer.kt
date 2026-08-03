package com.itangcent.easyapi.channel.openapi.multidocument

import com.itangcent.easyapi.channel.openapi.ComponentsObject
import com.itangcent.easyapi.channel.openapi.MediaTypeObject
import com.itangcent.easyapi.channel.openapi.OpenApiDocument
import com.itangcent.easyapi.channel.openapi.OperationObject
import com.itangcent.easyapi.channel.openapi.PathItemObject
import com.itangcent.easyapi.channel.openapi.SchemaObject
import com.itangcent.easyapi.core.export.HttpMethod
import java.security.MessageDigest
import java.util.PriorityQueue

internal data class SchemaRenameResult(
    val document: OpenApiDocument,
    val warnings: List<String>,
)

internal class OpenApiSemanticSchemaNamer(
    private val operationIndex: Map<EndpointOperationKey, EndpointOperationInfo>,
) {

    fun rename(document: OpenApiDocument): SchemaRenameResult {
        val schemas = document.components?.schemas
            ?: return SchemaRenameResult(document, emptyList())
        if (schemas.isEmpty()) return SchemaRenameResult(document, emptyList())

        val rootBindings = collectRootBindings(document, schemas)
        val candidates = rootBindings
            .map { Candidate(it.oldName, it.semanticName, canonicalStructure(it.oldName, schemas)) }
            .toMutableList()
        val directlyMapped = candidates.mapTo(mutableSetOf()) { it.oldName }
        generatedCandidates(rootBindings, directlyMapped, schemas).forEach { (oldName, semanticName) ->
            candidates += Candidate(oldName, semanticName, canonicalStructure(oldName, schemas))
        }
        val distinctCandidates = candidates.distinct()
        val finalNames = allocateFinalNames(distinctCandidates, schemas)
        val finalNamesByOld = distinctCandidates.groupBy(Candidate::oldName).mapValues { (_, values) ->
            values.mapTo(sortedSetOf()) { finalNames.getValue(it) }
        }
        val globalRename = finalNamesByOld.mapValues { (_, names) -> names.first() }

        val rewrittenSchemas = linkedMapOf<String, SchemaObject>()
        val renamedOldNames = distinctCandidates.mapTo(mutableSetOf(), Candidate::oldName)
        for ((oldName, schema) in schemas.toSortedMap()) {
            if (oldName !in renamedOldNames) {
                rewrittenSchemas[oldName] = rewriteSchema(schema, globalRename)
            }
        }
        for ((finalName, sameTarget) in distinctCandidates.groupBy { finalNames.getValue(it) }.toSortedMap()) {
            val source = sameTarget.minBy(Candidate::oldName)
            rewrittenSchemas[finalName] = rewriteSchema(
                schemas.getValue(source.oldName),
                globalRename,
                source.oldName,
                finalName,
            )
        }

        val targetByOperationAndOld = rootBindings.associate { binding ->
            val candidate = Candidate(
                binding.oldName,
                binding.semanticName,
                canonicalStructure(binding.oldName, schemas),
            )
            OperationComponent(binding.key, binding.oldName) to finalNames.getValue(candidate)
        }
        val rewrittenPaths = document.paths.mapValuesTo(linkedMapOf()) { (path, pathItem) ->
            rewritePathItem(path, pathItem, targetByOperationAndOld, globalRename)
        }
        val warnings = schemas.keys
            .asSequence()
            .filterNot(renamedOldNames::contains)
            .filter { GENERATED_NAME.matches(it) || LEGACY_COLLISION_NAME.matches(it) }
            .sorted()
            .map { name -> "OpenAPI schema '$name' has an unresolved generated or collision name; kept unchanged" }
            .toList()

        return SchemaRenameResult(
            document.copy(
                paths = rewrittenPaths,
                components = ComponentsObject(rewrittenSchemas),
            ),
            warnings,
        )
    }

    private fun collectRootBindings(
        document: OpenApiDocument,
        schemas: Map<String, SchemaObject>,
    ): List<RootBinding> = operationIndex.entries
        .asSequence()
        .sortedWith(compareBy({ it.key.path }, { it.key.method.name }))
        .mapNotNull { (key, info) ->
            val semanticName = semanticName(info.responseType) ?: return@mapNotNull null
            val operation = operationAt(document.paths[key.path] ?: return@mapNotNull null, key.method)
                ?: return@mapNotNull null
            val refs = operation.responses["200"]?.content
                ?.values
                ?.mapNotNull { internalName(it.schema.`$ref`) }
                ?.filter(schemas::containsKey)
                ?.distinct()
                .orEmpty()
            refs.singleOrNull()?.let { RootBinding(key, it, semanticName) }
        }
        .distinct()
        .toList()

    private fun generatedCandidates(
        roots: List<RootBinding>,
        directlyMapped: Set<String>,
        schemas: Map<String, SchemaObject>,
    ): Map<String, String> {
        val queue = PriorityQueue<ReachableComponent>(compareBy(ReachableComponent::candidate, ReachableComponent::name))
        roots.forEach { queue += ReachableComponent(it.oldName, it.semanticName) }
        val bestPath = mutableMapOf<String, String>()
        val generated = mutableMapOf<String, String>()

        while (queue.isNotEmpty()) {
            val current = queue.remove()
            val previous = bestPath[current.name]
            if (previous != null && previous <= current.candidate) continue
            bestPath[current.name] = current.candidate
            val schema = schemas[current.name] ?: continue
            schemaEdges(schema, current.candidate).forEach { edge ->
                if (GENERATED_NAME.matches(edge.name) && edge.name !in directlyMapped) {
                    val existing = generated[edge.name]
                    if (existing == null || edge.candidate < existing) generated[edge.name] = edge.candidate
                }
                queue += edge
            }
        }
        return generated.toSortedMap()
    }

    private fun schemaEdges(schema: SchemaObject, candidate: String): List<ReachableComponent> {
        val result = mutableListOf<ReachableComponent>()
        fun visit(current: SchemaObject, path: String) {
            internalName(current.`$ref`)?.let { result += ReachableComponent(it, path) }
            current.properties?.toSortedMap()?.forEach { (name, property) ->
                visit(property, appendName(path, name))
            }
            current.items?.let { visit(it, appendName(path, "Item")) }
            current.additionalProperties?.let { visit(it, appendName(path, "Value")) }
        }
        visit(schema, candidate)
        return result
    }

    private fun allocateFinalNames(
        candidates: List<Candidate>,
        schemas: Map<String, SchemaObject>,
    ): Map<Candidate, String> {
        val renamedOldNames = candidates.mapTo(mutableSetOf(), Candidate::oldName)
        val reserved = schemas.keys
            .filterNot(renamedOldNames::contains)
            .associateWith { canonicalStructure(it, schemas) }
        val result = mutableMapOf<Candidate, String>()

        for ((baseName, sameName) in candidates.groupBy(Candidate::semanticName).toSortedMap()) {
            val byShape = sameName.groupBy(Candidate::canonical).toSortedMap()
            val plainShape = byShape.keys.firstOrNull { canonical ->
                reserved[baseName]?.let { it == canonical } != false
            }
            for ((canonical, sameShape) in byShape) {
                val finalName = if (canonical == plainShape) {
                    baseName
                } else {
                    "${baseName}__${stableHash(canonical)}"
                }
                sameShape.forEach { result[it] = finalName }
            }
        }
        return result
    }

    private fun rewritePathItem(
        path: String,
        pathItem: PathItemObject,
        operationTargets: Map<OperationComponent, String>,
        globalRename: Map<String, String>,
    ): PathItemObject = pathItem.copy(
        get = pathItem.get?.let { rewriteOperation(path, HttpMethod.GET, it, operationTargets, globalRename) },
        post = pathItem.post?.let { rewriteOperation(path, HttpMethod.POST, it, operationTargets, globalRename) },
        put = pathItem.put?.let { rewriteOperation(path, HttpMethod.PUT, it, operationTargets, globalRename) },
        delete = pathItem.delete?.let { rewriteOperation(path, HttpMethod.DELETE, it, operationTargets, globalRename) },
        patch = pathItem.patch?.let { rewriteOperation(path, HttpMethod.PATCH, it, operationTargets, globalRename) },
        head = pathItem.head?.let { rewriteOperation(path, HttpMethod.HEAD, it, operationTargets, globalRename) },
        options = pathItem.options?.let { rewriteOperation(path, HttpMethod.OPTIONS, it, operationTargets, globalRename) },
    )

    private fun rewriteOperation(
        path: String,
        method: HttpMethod,
        operation: OperationObject,
        operationTargets: Map<OperationComponent, String>,
        globalRename: Map<String, String>,
    ): OperationObject = operation.copy(
        parameters = operation.parameters?.map { it.copy(schema = rewriteSchema(it.schema, globalRename)) },
        requestBody = operation.requestBody?.let { body ->
            body.copy(content = rewriteContent(body.content, globalRename))
        },
        responses = operation.responses.mapValuesTo(linkedMapOf()) { (status, response) ->
            response.copy(
                content = response.content?.mapValuesTo(linkedMapOf()) { (_, mediaType) ->
                    val oldName = internalName(mediaType.schema.`$ref`)
                    val operationTarget = if (status == "200" && oldName != null) {
                        operationTargets[OperationComponent(EndpointOperationKey(path, method), oldName)]
                    } else {
                        null
                    }
                    mediaType.copy(
                        schema = rewriteSchema(
                            mediaType.schema,
                            globalRename,
                            directReferenceName = operationTarget,
                        ),
                    )
                },
            )
        },
    )

    private fun rewriteContent(
        content: LinkedHashMap<String, MediaTypeObject>,
        globalRename: Map<String, String>,
    ): LinkedHashMap<String, MediaTypeObject> = content.mapValuesTo(linkedMapOf()) { (_, mediaType) ->
        mediaType.copy(schema = rewriteSchema(mediaType.schema, globalRename))
    }

    private fun rewriteSchema(
        schema: SchemaObject,
        globalRename: Map<String, String>,
        selfOldName: String? = null,
        selfFinalName: String? = null,
        directReferenceName: String? = null,
    ): SchemaObject {
        val oldReferenceName = internalName(schema.`$ref`)
        val renamedReference = when {
            directReferenceName != null && oldReferenceName != null -> directReferenceName
            oldReferenceName == selfOldName -> selfFinalName
            oldReferenceName != null -> globalRename[oldReferenceName]
            else -> null
        }
        return schema.copy(
            `$ref` = renamedReference?.let(::internalRef) ?: schema.`$ref`,
            properties = schema.properties?.mapValuesTo(linkedMapOf()) { (_, property) ->
                rewriteSchema(property, globalRename, selfOldName, selfFinalName)
            },
            additionalProperties = schema.additionalProperties?.let {
                rewriteSchema(it, globalRename, selfOldName, selfFinalName)
            },
            items = schema.items?.let {
                rewriteSchema(it, globalRename, selfOldName, selfFinalName)
            },
        )
    }

    private fun canonicalStructure(name: String, schemas: Map<String, SchemaObject>): String =
        canonicalSchema(schemas.getValue(name), schemas, listOf(name))

    private fun canonicalSchema(
        schema: SchemaObject,
        schemas: Map<String, SchemaObject>,
        stack: List<String>,
    ): String {
        val reference = internalName(schema.`$ref`)
        if (reference != null && reference in schemas) {
            val cycleIndex = stack.indexOf(reference)
            return if (cycleIndex >= 0) {
                "cycle:${stack.size - cycleIndex}"
            } else {
                "ref:${canonicalSchema(schemas.getValue(reference), schemas, stack + reference)}"
            }
        }
        return buildString {
            append("ref=").append(schema.`$ref`.orEmpty())
            append(";type=").append(schema.type.orEmpty())
            append(";format=").append(schema.format.orEmpty())
            append(";required=").append(schema.required.orEmpty().sorted().joinToString(","))
            append(";enum=").append(canonicalValue(schema.enumValues))
            append(";properties={")
            schema.properties?.toSortedMap()?.forEach { (name, property) ->
                append(name.length).append(':').append(name).append('=')
                append(canonicalSchema(property, schemas, stack)).append(';')
            }
            append("};items=")
            schema.items?.let { append(canonicalSchema(it, schemas, stack)) }
            append(";additional=")
            schema.additionalProperties?.let { append(canonicalSchema(it, schemas, stack)) }
        }
    }

    private fun canonicalValue(value: Any?): String = when (value) {
        null -> ""
        is Map<*, *> -> value.entries
            .sortedBy { it.key.toString() }
            .joinToString(prefix = "{", postfix = "}") { "${canonicalValue(it.key)}:${canonicalValue(it.value)}" }

        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { canonicalValue(it) }
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { canonicalValue(it) }
        else -> value.toString()
    }

    private fun semanticName(responseType: String?): String? {
        if (responseType.isNullOrBlank()) return null
        val parts = TYPE_IDENTIFIER.findAll(responseType).mapNotNull { match ->
            val token = match.value
            if (token in TYPE_BOUND_KEYWORDS) return@mapNotNull null
            val names = token.split('.', '$').filter(String::isNotEmpty)
            val firstType = names.indexOfFirst { it.firstOrNull()?.isUpperCase() == true }
            names.drop(if (firstType >= 0) firstType else names.lastIndex.coerceAtLeast(0))
                .joinToString("_")
                .takeIf(String::isNotEmpty)
        }.toList()
        return parts.joinToString("_")
            .replace(NON_NAME_CHARACTER, "_")
            .replace(REPEATED_UNDERSCORE, "_")
            .trim('_')
            .takeIf(String::isNotEmpty)
    }

    private fun appendName(prefix: String, part: String): String {
        val cleanPart = part
            .replace(NON_NAME_CHARACTER, "_")
            .replace(REPEATED_UNDERSCORE, "_")
            .trim('_')
            .ifEmpty { "Field" }
        return "${prefix}_$cleanPart"
    }

    private fun operationAt(pathItem: PathItemObject, method: HttpMethod): OperationObject? = when (method) {
        HttpMethod.GET -> pathItem.get
        HttpMethod.POST -> pathItem.post
        HttpMethod.PUT -> pathItem.put
        HttpMethod.DELETE -> pathItem.delete
        HttpMethod.PATCH -> pathItem.patch
        HttpMethod.HEAD -> pathItem.head
        HttpMethod.OPTIONS -> pathItem.options
        HttpMethod.NO_METHOD -> null
    }

    private fun stableHash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(4)
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun internalName(reference: String?): String? =
        reference?.takeIf { it.startsWith(INTERNAL_SCHEMA_PREFIX) }?.removePrefix(INTERNAL_SCHEMA_PREFIX)

    private fun internalRef(name: String): String = "$INTERNAL_SCHEMA_PREFIX$name"

    private data class RootBinding(
        val key: EndpointOperationKey,
        val oldName: String,
        val semanticName: String,
    )

    private data class Candidate(
        val oldName: String,
        val semanticName: String,
        val canonical: String,
    )

    private data class OperationComponent(
        val key: EndpointOperationKey,
        val oldName: String,
    )

    private data class ReachableComponent(
        val name: String,
        val candidate: String,
    )

    private companion object {
        const val INTERNAL_SCHEMA_PREFIX = "#/components/schemas/"
        val GENERATED_NAME = Regex("GeneratedSchema\\d+")
        val LEGACY_COLLISION_NAME = Regex(".+_\\d+")
        val TYPE_IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$.]*")
        val TYPE_BOUND_KEYWORDS = setOf("extends", "super", "out", "in")
        val NON_NAME_CHARACTER = Regex("[^A-Za-z0-9_]")
        val REPEATED_UNDERSCORE = Regex("_+")
    }
}
