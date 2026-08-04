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
    fun testTreatsInlineAndReferencedAliasAsTheSameCanonicalShape() {
        val result = namer(
            operation("/inline", HttpMethod.GET, "com.first.Wrapper"),
            operation("/alias", HttpMethod.GET, "com.second.Wrapper"),
        ).rename(
            document(
                paths = linkedMapOf(
                    "/inline" to pathItem(HttpMethod.GET, "InlineWrapper"),
                    "/alias" to pathItem(HttpMethod.GET, "AliasWrapper"),
                ),
                schemas = linkedMapOf(
                    "InlineWrapper" to objectSchema("value" to SchemaObject(type = "string")),
                    "AliasWrapper" to objectSchema("value" to SchemaObject(`$ref` = ref("StringAlias"))),
                    "StringAlias" to SchemaObject(type = "string"),
                ),
            ),
        )

        assertTrue("Equivalent inline and referenced shapes should share the plain semantic name", "Wrapper" in result.document.components!!.schemas!!)
        assertFalse("Equivalent alias expansion must not add a collision hash", result.document.components!!.schemas!!.keys.any { it.startsWith("Wrapper__") })
        assertEquals("Both operations should use the shared canonical component", ref("Wrapper"), responseRef(result.document, "/inline", HttpMethod.GET))
        assertEquals("Both operations should use the shared canonical component", ref("Wrapper"), responseRef(result.document, "/alias", HttpMethod.GET))
    }

    @Test
    fun testNamesDirectGeneratedRootFromResponseTypeThenOperationContext() {
        val result = namer(
            operation("/typed", HttpMethod.GET, "com.acme.DirectRootVO"),
            operation("/fallback", HttpMethod.GET, responseType = null, methodName = null),
        ).rename(
            document(
                paths = linkedMapOf(
                    "/typed" to pathItem(HttpMethod.GET, "GeneratedSchema40", operationId = "loadTyped"),
                    "/fallback" to pathItem(HttpMethod.GET, "GeneratedSchema41", operationId = "loadFallback"),
                ),
                schemas = linkedMapOf(
                    "GeneratedSchema40" to objectSchema("id" to SchemaObject(type = "string")),
                    "GeneratedSchema41" to objectSchema("id" to SchemaObject(type = "integer")),
                ),
            ),
        )

        assertTrue("Response type should take precedence for a generated root", "DirectRootVO" in result.document.components!!.schemas!!)
        assertTrue("Operation context should name an otherwise anonymous generated root", "loadFallback_Response" in result.document.components!!.schemas!!)
        assertEquals("Typed operation should reference its semantic root", ref("DirectRootVO"), responseRef(result.document, "/typed", HttpMethod.GET))
        assertEquals("Fallback operation should reference its contextual root", ref("loadFallback_Response"), responseRef(result.document, "/fallback", HttpMethod.GET))
        assertFalse("Mapped generated roots should not remain unresolved", result.document.components!!.schemas!!.keys.any { Regex("GeneratedSchema\\d+").matches(it) })
    }

    @Test
    fun testPreservesNumericSuffixWhenNamingGeneratedRootFromOperationId() {
        val result = namer(
            operation("/load", HttpMethod.GET, responseType = null, methodName = null),
        ).rename(
            document(
                paths = linkedMapOf(
                    "/load" to pathItem(HttpMethod.GET, "GeneratedSchema50", operationId = "load-2"),
                ),
                schemas = linkedMapOf(
                    "GeneratedSchema50" to objectSchema("id" to SchemaObject(type = "string")),
                ),
            ),
        )

        assertEquals("Wire operation collision suffix should remain meaningful", setOf("load_2_Response"), result.document.components!!.schemas!!.keys)
        assertEquals("Operation response should use the suffix-preserving name", ref("load_2_Response"), responseRef(result.document, "/load", HttpMethod.GET))
    }

    @Test
    fun testKeepsExistingSchemaWhenItOccupiesAHashedCandidateName() {
        val operations = arrayOf(
            operation("/first", HttpMethod.GET, "com.first.Collision"),
            operation("/second", HttpMethod.GET, "com.second.Collision"),
        )
        val candidateSchemas = linkedMapOf(
            "FirstCollision" to SchemaObject(type = "string"),
            "SecondCollision" to SchemaObject(type = "integer"),
        )
        val paths = linkedMapOf(
            "/first" to pathItem(HttpMethod.GET, "FirstCollision"),
            "/second" to pathItem(HttpMethod.GET, "SecondCollision"),
        )
        val baseline = namer(*operations).rename(document(paths, candidateSchemas)).document
        val occupiedName = baseline.components!!.schemas!!.keys.single { it.startsWith("Collision__") }
        val fallbackPath = listOf("/first", "/second").single { path ->
            responseRef(baseline, path, HttpMethod.GET) == ref(occupiedName)
        }
        val fallbackOldName = if (fallbackPath == "/first") "FirstCollision" else "SecondCollision"
        val existing = SchemaObject(
            type = "boolean",
            description = "existing schema must survive",
            example = true,
        )
        val source = document(
            paths = LinkedHashMap(paths).apply {
                put("/existing", pathItem(HttpMethod.GET, occupiedName))
            },
            schemas = LinkedHashMap(candidateSchemas).apply {
                put(occupiedName, existing)
            },
        )

        val result = namer(*operations).rename(source)
        val schemas = result.document.components!!.schemas!!
        val candidateRefs = setOf(
            responseRef(result.document, "/first", HttpMethod.GET),
            responseRef(result.document, "/second", HttpMethod.GET),
        )
        val reversedSource = document(
            paths = source.paths.entries.reversed().associateTo(linkedMapOf()) { it.key to it.value },
            schemas = source.components!!.schemas!!.entries.reversed().associateTo(linkedMapOf()) { it.key to it.value },
        )
        val reversed = namer(*operations.reversedArray()).rename(reversedSource)

        assertEquals("Reserved schema content must not be overwritten", existing, schemas.getValue(occupiedName))
        assertEquals("Existing operation reference should keep its meaning", ref(occupiedName), responseRef(result.document, "/existing", HttpMethod.GET))
        assertEquals("Conflicting candidate should keep its protected original name", ref(fallbackOldName), responseRef(result.document, fallbackPath, HttpMethod.GET))
        assertFalse("Candidate response must not point at the conflicting existing schema", ref(occupiedName) in candidateRefs)
        assertTrue(
            "Semantic collision names must use exactly one eight-hex structural hash",
            schemas.keys.filter { it.startsWith("Collision__") }.all { Regex("Collision__[0-9a-f]{8}").matches(it) },
        )
        assertTrue("Different-shape reserved collision should produce a warning", result.warnings.any { it.contains(occupiedName) && it.contains("conflict", ignoreCase = true) })
        assertEquals("Schema insertion order must not change final keys", schemas.keys, reversed.document.components!!.schemas!!.keys)
        assertEquals(
            "Endpoint insertion order must not change response references",
            listOf("/first", "/second", "/existing").associateWith { responseRef(result.document, it, HttpMethod.GET) },
            listOf("/first", "/second", "/existing").associateWith { responseRef(reversed.document, it, HttpMethod.GET) },
        )
    }

    @Test
    fun testReusesReleasedCandidateNameWithoutFalseConflict() {
        val operations = arrayOf(
            operation("/foo", HttpMethod.GET, "com.acme.Bar"),
            operation("/baz", HttpMethod.GET, "com.acme.Foo"),
        )
        val source = document(
            paths = linkedMapOf(
                "/foo" to pathItem(HttpMethod.GET, "Foo"),
                "/baz" to pathItem(HttpMethod.GET, "Baz"),
            ),
            schemas = linkedMapOf(
                "Foo" to SchemaObject(type = "string"),
                "Baz" to SchemaObject(type = "integer"),
            ),
        )

        val result = namer(*operations).rename(source)
        val reversedSource = document(
            paths = source.paths.entries.reversed().associateTo(linkedMapOf()) { it.key to it.value },
            schemas = source.components!!.schemas!!.entries.reversed().associateTo(linkedMapOf()) { it.key to it.value },
        )
        val reversed = namer(*operations.reversedArray()).rename(reversedSource)

        assertEquals("Released candidate names should be reusable without a hash", setOf("Bar", "Foo"), result.document.components!!.schemas!!.keys)
        assertEquals("The renamed Foo component should follow its semantic name", ref("Bar"), responseRef(result.document, "/foo", HttpMethod.GET))
        assertEquals("The Baz component should reuse the released Foo name", ref("Foo"), responseRef(result.document, "/baz", HttpMethod.GET))
        assertTrue("Reusing a released candidate name should not warn", result.warnings.isEmpty())
        assertEquals("Schema insertion order must not change released-name allocation", result.document.components!!.schemas!!.keys, reversed.document.components!!.schemas!!.keys)
        assertEquals(
            "Endpoint insertion order must not change released-name references",
            listOf("/foo", "/baz").associateWith { responseRef(result.document, it, HttpMethod.GET) },
            listOf("/foo", "/baz").associateWith { responseRef(reversed.document, it, HttpMethod.GET) },
        )
    }

    @Test
    fun testReusesSameShapeReservedSchemaWithoutOverwritingMetadata() {
        val existing = objectSchema("id" to SchemaObject(type = "string")).copy(
            description = "keep existing description",
            example = linkedMapOf("id" to "existing"),
        )
        val candidate = objectSchema("id" to SchemaObject(type = "string")).copy(
            description = "candidate description",
            example = linkedMapOf("id" to "candidate"),
        )
        val result = namer(
            operation("/candidate", HttpMethod.GET, "com.acme.ExistingResponse"),
        ).rename(
            document(
                paths = linkedMapOf(
                    "/candidate" to pathItem(HttpMethod.GET, "CandidateSource"),
                    "/existing" to pathItem(HttpMethod.GET, "ExistingResponse"),
                ),
                schemas = linkedMapOf(
                    "CandidateSource" to candidate,
                    "ExistingResponse" to existing,
                ),
            ),
        )

        assertEquals("Same-shape reserved schema should be reused with its metadata intact", existing, result.document.components!!.schemas!!.getValue("ExistingResponse"))
        assertEquals("Candidate should point at the reused schema", ref("ExistingResponse"), responseRef(result.document, "/candidate", HttpMethod.GET))
        assertEquals("Existing reference should remain unchanged", ref("ExistingResponse"), responseRef(result.document, "/existing", HttpMethod.GET))
    }

    @Test
    fun testKeepsGeneratedRootAndWarnsWhenOperationIdIsBlank() {
        val result = namer(
            operation("/blank", HttpMethod.GET, responseType = null, methodName = null),
        ).rename(
            document(
                paths = linkedMapOf(
                    "/blank" to pathItem(HttpMethod.GET, "GeneratedSchema51", operationId = "   "),
                ),
                schemas = linkedMapOf(
                    "GeneratedSchema51" to objectSchema("id" to SchemaObject(type = "string")),
                ),
            ),
        )

        assertTrue("Blank operation context should not invent a semantic name", "GeneratedSchema51" in result.document.components!!.schemas!!)
        assertEquals("Unresolved generated response should keep its original reference", ref("GeneratedSchema51"), responseRef(result.document, "/blank", HttpMethod.GET))
        assertTrue("Unresolved generated root should warn", result.warnings.any { it.contains("GeneratedSchema51") })
    }

    @Test
    fun testClonesOneSharedComponentForDifferentOperationResponseTypes() {
        val result = namer(
            operation("/alpha", HttpMethod.GET, "com.acme.AlphaResponse"),
            operation("/zeta", HttpMethod.GET, "com.acme.ZetaResponse"),
        ).rename(
            document(
                paths = linkedMapOf(
                    "/alpha" to pathItem(HttpMethod.GET, "SharedResponse"),
                    "/zeta" to pathItem(HttpMethod.GET, "SharedResponse"),
                ),
                schemas = linkedMapOf("SharedResponse" to objectSchema("id" to SchemaObject(type = "string"))),
            ),
        )

        assertEquals("Shared source should be cloned once per response type", setOf("AlphaResponse", "ZetaResponse"), result.document.components!!.schemas!!.keys)
        assertEquals("Alpha operation should use its clone", ref("AlphaResponse"), responseRef(result.document, "/alpha", HttpMethod.GET))
        assertEquals("Zeta operation should use its clone", ref("ZetaResponse"), responseRef(result.document, "/zeta", HttpMethod.GET))
    }

    @Test
    fun testTokenizesArraysWildcardBoundsAndNestedClassSeparators() {
        val cases = listOf(
            Triple("/array", "ArraySource", "com.acme.ArrayBox<com.acme.UserVO[]>"),
            Triple("/wildcard", "WildcardSource", "com.acme.WildcardBox<? extends com.acme.UserVO>"),
            Triple("/java-nested", "JavaNestedSource", "com.acme.Outer\$JavaInner"),
            Triple("/kotlin-nested", "KotlinNestedSource", "com.acme.Outer.KotlinInner"),
        )
        val expected = setOf("ArrayBox_UserVO", "WildcardBox_UserVO", "Outer_JavaInner", "Outer_KotlinInner")
        val result = namer(
            *cases.map { (path, _, type) -> operation(path, HttpMethod.GET, type) }.toTypedArray(),
        ).rename(
            document(
                paths = cases.associateTo(linkedMapOf()) { (path, source, _) -> path to pathItem(HttpMethod.GET, source) },
                schemas = cases.associateTo(linkedMapOf()) { (_, source, _) -> source to SchemaObject(type = "string") },
            ),
        )

        assertEquals("Tokenizer should preserve type order while collapsing syntax separators", expected, result.document.components!!.schemas!!.keys)
    }

    @Test
    fun testCanonicalShapeIgnoresPropertyAndRequiredInsertionOrder() {
        val firstProperties = linkedMapOf(
            "alpha" to SchemaObject(type = "string"),
            "zeta" to SchemaObject(type = "integer"),
        )
        val secondProperties = linkedMapOf(
            "zeta" to SchemaObject(type = "integer"),
            "alpha" to SchemaObject(type = "string"),
        )
        val result = namer(
            operation("/first-order", HttpMethod.GET, "com.first.OrderedResponse"),
            operation("/second-order", HttpMethod.GET, "com.second.OrderedResponse"),
        ).rename(
            document(
                paths = linkedMapOf(
                    "/first-order" to pathItem(HttpMethod.GET, "FirstOrder"),
                    "/second-order" to pathItem(HttpMethod.GET, "SecondOrder"),
                ),
                schemas = linkedMapOf(
                    "FirstOrder" to SchemaObject(type = "object", properties = firstProperties, required = listOf("zeta", "alpha")),
                    "SecondOrder" to SchemaObject(type = "object", properties = secondProperties, required = listOf("alpha", "zeta")),
                ),
            ),
        )

        assertEquals("Map and required ordering should not create a hash", setOf("OrderedResponse"), result.document.components!!.schemas!!.keys)
    }

    @Test
    fun testCanonicalShapePreservesEnumAndRequiredBoundaries() {
        val operations = arrayOf(
            operation("/enum-single", HttpMethod.GET, "com.first.EnumCollision"),
            operation("/enum-split", HttpMethod.GET, "com.second.EnumCollision"),
            operation("/required-single", HttpMethod.GET, "com.first.RequiredCollision"),
            operation("/required-split", HttpMethod.GET, "com.second.RequiredCollision"),
        )
        val source = document(
            paths = linkedMapOf(
                "/enum-single" to pathItem(HttpMethod.GET, "EnumSingle"),
                "/enum-split" to pathItem(HttpMethod.GET, "EnumSplit"),
                "/required-single" to pathItem(HttpMethod.GET, "RequiredSingle"),
                "/required-split" to pathItem(HttpMethod.GET, "RequiredSplit"),
            ),
            schemas = linkedMapOf(
                "EnumSingle" to SchemaObject(type = "string", enumValues = listOf("a,b")),
                "EnumSplit" to SchemaObject(type = "string", enumValues = listOf("a", "b")),
                "RequiredSingle" to SchemaObject(type = "object", required = listOf("a,b")),
                "RequiredSplit" to SchemaObject(type = "object", required = listOf("a", "b")),
            ),
        )

        val result = namer(*operations).rename(source).document
        val reversedSource = document(
            paths = source.paths.entries.reversed().associateTo(linkedMapOf()) { it.key to it.value },
            schemas = source.components!!.schemas!!.entries.reversed().associateTo(linkedMapOf()) { it.key to it.value },
        )
        val reversed = namer(*operations.reversedArray()).rename(reversedSource).document
        val keys = result.components!!.schemas!!.keys

        assertTrue("One enum shape should retain the plain semantic name", "EnumCollision" in keys)
        assertEquals("Delimited enum values should produce one structural hash", 1, keys.count { Regex("EnumCollision__[0-9a-f]{8}").matches(it) })
        assertNotEquals("One delimited enum value must differ from two enum values", responseRef(result, "/enum-single", HttpMethod.GET), responseRef(result, "/enum-split", HttpMethod.GET))
        assertTrue("One required shape should retain the plain semantic name", "RequiredCollision" in keys)
        assertEquals("Delimited required names should produce one structural hash", 1, keys.count { Regex("RequiredCollision__[0-9a-f]{8}").matches(it) })
        assertNotEquals("One delimited required name must differ from two required names", responseRef(result, "/required-single", HttpMethod.GET), responseRef(result, "/required-split", HttpMethod.GET))
        assertEquals("Schema insertion order must not change boundary-aware keys", keys, reversed.components!!.schemas!!.keys)
        assertEquals(
            "Endpoint insertion order must not change boundary-aware references",
            source.paths.keys.associateWith { responseRef(result, it, HttpMethod.GET) },
            source.paths.keys.associateWith { responseRef(reversed, it, HttpMethod.GET) },
        )
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
        responseType: String?,
        methodName: String? = "operation",
    ) = EndpointOperationKey(path, method) to EndpointOperationInfo(
        controller = "com.acme.Controller",
        folder = null,
        methodName = methodName,
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

    private fun pathItem(
        method: HttpMethod,
        schemaName: String,
        operationId: String = "${method.name.lowercase()}_$schemaName",
    ): PathItemObject =
        PathItemObject().withMethod(
            method,
            OperationObject(
                operationId = operationId,
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
