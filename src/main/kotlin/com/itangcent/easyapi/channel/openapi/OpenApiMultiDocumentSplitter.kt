package com.itangcent.easyapi.channel.openapi

import com.itangcent.easyapi.core.export.ApiEndpoint
import com.itangcent.easyapi.core.export.HttpMethod
import com.itangcent.easyapi.core.export.httpMetadata

/**
 * Validates that every normalized OpenAPI path belongs to one document owner.
 *
 * Actual document splitting is intentionally handled separately.
 *
 * @param endpoints endpoints whose normalized HTTP paths are validated
 * @throws IllegalArgumentException when multiple owners claim the same path
 */
class OpenApiMultiDocumentSplitter(endpoints: List<ApiEndpoint>) {

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
        }
    }

    private fun ownerOf(endpoint: ApiEndpoint): String {
        endpoint.className?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return "Controller $it"
        }
        endpoint.folder?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return "Folder $it"
        }
        return "Unresolved"
    }

    private data class OwnershipObservation(
        val method: HttpMethod,
        val owner: String,
    )
}
