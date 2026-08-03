package com.itangcent.easyapi.channel.openapi.multidocument

import com.google.gson.GsonBuilder
import com.itangcent.easyapi.channel.openapi.ComponentsObject
import com.itangcent.easyapi.channel.openapi.InfoObject
import com.itangcent.easyapi.channel.openapi.MediaTypeObject
import com.itangcent.easyapi.channel.openapi.OpenApiDocument
import com.itangcent.easyapi.channel.openapi.OperationObject
import com.itangcent.easyapi.channel.openapi.PathItemObject
import com.itangcent.easyapi.channel.openapi.ResponseObject
import com.itangcent.easyapi.channel.openapi.SchemaObject
import com.itangcent.easyapi.core.export.HttpMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenApiSemanticSchemaNamerTest {

    @Test
    fun testNamesQualifiedGenericResponseAndRewritesOperationReference() {
        val result = namer(
            operation("/templates", HttpMethod.GET, "com.acme.BaseResponse<java.util.List<com.acme.SelfAssessmentTemplateVO>>"),
        ).rename(
            document(
                paths = linkedMapOf("/templates" to pathItem(HttpMethod.GET, "BaseResponse")),
                schemas = linkedMapOf("BaseResponse" to objectSchema("data" to SchemaObject(type = "array"))),
            ),
        )

        val expected = "BaseResponse_List_SelfAssessmentTemplateVO"
        assertEquals("Qualified generic type should become the component name", setOf(expected), result.document.components!!.schemas!!.keys)
        assertEquals("Operation response should follow the renamed component", ref(expected), responseRef(result.document, "/templates", HttpMethod.GET))
        assertTrue("Resolved legacy component should not warn", result.warnings.isEmpty())
    }

    @Test
    fun testAddsStableHashOnlyForDifferentCanonicalShapes() {
        val operations = listOf(
            operation("/first", HttpMethod.GET, "com.first.BaseResponse<com.first.UserVO>"),
            operation("/second", HttpMethod.GET, "com.second.BaseResponse<com.second.UserVO>"),
        )
        val source = document(
            paths = linkedMapOf(
                "/first" to pathItem(HttpMethod.GET, "BaseResponse"),
                "/second" to pathItem(HttpMethod.GET, "BaseResponse_2"),
            ),
            schemas = linkedMapOf(
                "BaseResponse" to objectSchema("value" to SchemaObject(type = "string", description = "ignored")),
                "BaseResponse_2" to objectSchema("value" to SchemaObject(type = "integer", example = 7)),
            ),
        )

        val forward = namer(*operations.toTypedArray()).rename(source).document
        val reversed = namer(*operations.reversed().toTypedArray()).rename(source).document
        val keys = forward.components!!.schemas!!.keys

        assertEquals("Endpoint order should not change allocated component keys", keys, reversed.components!!.schemas!!.keys)
        assertTrue("One canonical shape should retain the plain semantic name", "BaseResponse_UserVO" in keys)
        assertEquals("Only the conflicting shape should receive an eight-hex suffix", 1, keys.count { Regex("BaseResponse_UserVO__[0-9a-f]{8}").matches(it) })
        assertNotEquals("Different shapes must remain different response components", responseRef(forward, "/first", HttpMethod.GET), responseRef(forward, "/second", HttpMethod.GET))
    }

    @Test
    fun testMergesSameSemanticNameAndCanonicalShapeWithoutHash() {
        val shape = objectSchema(
            "value" to SchemaObject(type = "string"),
            required = listOf("value"),
        )
        val result = namer(
            operation("/first", HttpMethod.GET, "com.first.BaseResponse<com.first.UserVO>"),
            operation("/second", HttpMethod.GET, "com.second.BaseResponse<com.second.UserVO>"),
        ).rename(
            document(
                paths = linkedMapOf(
                    "/first" to pathItem(HttpMethod.GET, "BaseResponse"),
                    "/second" to pathItem(HttpMethod.GET, "BaseResponse_2"),
                ),
                schemas = linkedMapOf(
                    "BaseResponse" to shape,
                    "BaseResponse_2" to shape.copy(description = "ignored", required = listOf("value")),
                ),
            ),
        )

        assertEquals("Equal canonical shapes should share the plain name", setOf("BaseResponse_UserVO"), result.document.components!!.schemas!!.keys)
        assertEquals("Both operations should use the shared component", ref("BaseResponse_UserVO"), responseRef(result.document, "/first", HttpMethod.GET))
        assertEquals("Both operations should use the shared component", ref("BaseResponse_UserVO"), responseRef(result.document, "/second", HttpMethod.GET))
    }

    @Test
    fun testNamesReachableGeneratedSchemasFromRootAndFieldPath() {
        val result = namer(
            operation("/tasks", HttpMethod.GET, "com.acme.SelfAssessmentTaskVO"),
            operation("/other", HttpMethod.GET, "com.acme.AnotherVO"),
        ).rename(
            document(
                paths = linkedMapOf(
                    "/tasks" to pathItem(HttpMethod.GET, "TaskRoot"),
                    "/other" to pathItem(HttpMethod.GET, "OtherRoot"),
                ),
                schemas = linkedMapOf(
                    "TaskRoot" to objectSchema(
                        "questions" to SchemaObject(type = "array", items = SchemaObject(`$ref` = ref("GeneratedSchema1"))),
                        "attributes" to SchemaObject(type = "object", additionalProperties = SchemaObject(`$ref` = ref("GeneratedSchema2"))),
                    ),
                    "OtherRoot" to objectSchema("alpha" to SchemaObject(`$ref` = ref("GeneratedSchema3"))),
                    "GeneratedSchema1" to objectSchema("shared" to SchemaObject(`$ref` = ref("GeneratedSchema3"))),
                    "GeneratedSchema2" to objectSchema("shared" to SchemaObject(`$ref` = ref("GeneratedSchema3"))),
                    "GeneratedSchema3" to objectSchema("id" to SchemaObject(type = "string")),
                ),
            ),
        )
        val schemas = result.document.components!!.schemas!!

        assertTrue("Array item should append the field and Item path", "SelfAssessmentTaskVO_questions_Item" in schemas)
        assertTrue("Map values should append the field and Value path", "SelfAssessmentTaskVO_attributes_Value" in schemas)
        assertTrue("Shared generated schema should use the lexicographically smallest root candidate", "AnotherVO_alpha" in schemas)
        assertFalse("Resolved generated names should be removed", schemas.keys.any { Regex("GeneratedSchema\\d+").matches(it) })
        assertEquals("Generated property reference should be rewritten", ref("SelfAssessmentTaskVO_questions_Item"), schemas.getValue("SelfAssessmentTaskVO").properties!!.getValue("questions").items!!.`$ref`)
        assertEquals("Generated map reference should be rewritten", ref("SelfAssessmentTaskVO_attributes_Value"), schemas.getValue("SelfAssessmentTaskVO").properties!!.getValue("attributes").additionalProperties!!.`$ref`)
        assertEquals("Every reference to a shared generated schema should use the same selected name", ref("AnotherVO_alpha"), schemas.getValue("SelfAssessmentTaskVO_questions_Item").properties!!.getValue("shared").`$ref`)
    }

    @Test
    fun testPreservesUnmappedLegacyNamesWarnsAndSerializesNoSourceMetadata() {
        val result = namer(
            operation("/missing", HttpMethod.GET, "com.acme.MissingVO"),
        ).rename(
            document(
                paths = linkedMapOf("/existing" to pathItem(HttpMethod.GET, "Known")),
                schemas = linkedMapOf(
                    "Known" to objectSchema("id" to SchemaObject(type = "string")),
                    "BaseResponse_2" to objectSchema("code" to SchemaObject(type = "integer")),
                    "GeneratedSchema7" to objectSchema("value" to SchemaObject(type = "string")),
                ),
            ),
        )
        val json = GsonBuilder().create().toJson(result.document.components!!.schemas)

        assertTrue("Unmapped legacy collision name should be preserved", "BaseResponse_2" in result.document.components!!.schemas!!)
        assertTrue("Unmapped generated name should be preserved", "GeneratedSchema7" in result.document.components!!.schemas!!)
        assertEquals("Each unresolved suspicious component should warn once", 2, result.warnings.size)
        assertTrue("Legacy warning should identify the component", result.warnings.single { it.contains("BaseResponse_2") }.contains("unresolved", ignoreCase = true))
        assertTrue("Generated warning should identify the component", result.warnings.single { it.contains("GeneratedSchema7") }.contains("unresolved", ignoreCase = true))
        assertFalse("Serialized schemas must not expose Java type metadata", json.contains("x-java-type"))
        assertFalse("Serialized schemas must not expose a schema source index", json.contains("schemaSourceIndex"))
    }

    private fun namer(vararg operations: Pair<EndpointOperationKey, EndpointOperationInfo>) =
        OpenApiSemanticSchemaNamer(linkedMapOf(*operations))

    private fun operation(
        path: String,
        method: HttpMethod,
        responseType: String,
    ) = EndpointOperationKey(path, method) to EndpointOperationInfo(
        controller = "com.acme.Controller",
        folder = null,
        methodName = "operation",
        responseType = responseType,
    )

    private fun document(
        paths: LinkedHashMap<String, PathItemObject>,
        schemas: LinkedHashMap<String, SchemaObject>,
    ) = OpenApiDocument(
        info = InfoObject(title = "Test", version = "1"),
        paths = paths,
        components = ComponentsObject(schemas),
    )

    private fun pathItem(method: HttpMethod, schemaName: String): PathItemObject =
        PathItemObject().withMethod(
            method,
            OperationObject(
                operationId = "${method.name.lowercase()}_$schemaName",
                responses = linkedMapOf(
                    "200" to ResponseObject(
                        description = "OK",
                        content = linkedMapOf("application/json" to MediaTypeObject(SchemaObject(`$ref` = ref(schemaName)))),
                    ),
                ),
            ),
        )

    private fun objectSchema(
        vararg properties: Pair<String, SchemaObject>,
        required: List<String>? = null,
    ) = SchemaObject(type = "object", properties = linkedMapOf(*properties), required = required)

    private fun responseRef(document: OpenApiDocument, path: String, method: HttpMethod): String? =
        operationAt(document.paths.getValue(path), method)!!.responses.getValue("200").content!!
            .getValue("application/json").schema.`$ref`

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

    private fun ref(name: String) = "#/components/schemas/$name"
}
