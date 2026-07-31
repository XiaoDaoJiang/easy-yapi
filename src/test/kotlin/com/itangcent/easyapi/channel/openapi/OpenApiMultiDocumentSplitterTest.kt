package com.itangcent.easyapi.channel.openapi

import com.itangcent.easyapi.core.export.ApiEndpoint
import com.itangcent.easyapi.core.export.GrpcMetadata
import com.itangcent.easyapi.core.export.GrpcStreamingType
import com.itangcent.easyapi.core.export.HttpMethod
import com.itangcent.easyapi.core.export.httpMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpenApiMultiDocumentSplitterTest {

    @Test
    fun testAcceptsMultipleMethodsAndDuplicateEndpointsFromSameController() {
        val splitter = OpenApiMultiDocumentSplitter(
            listOf(
                endpoint("/users", HttpMethod.GET, "com.acme.UserController", "Users"),
                endpoint("/users", HttpMethod.POST, "com.acme.UserController", "Admin"),
                endpoint("/users", HttpMethod.GET, "com.acme.UserController", "Users"),
            ),
        )

        assertNotNull("Same controller should own every method of one path", splitter)
    }

    @Test
    fun testRejectsSamePathOwnedByDifferentControllersWithActionableMessage() {
        val error = expectConflict(
            endpoint("/users", HttpMethod.GET, "com.acme.UserController"),
            endpoint("/users", HttpMethod.POST, "com.acme.AdminController"),
        )

        assertTrue("Message should include the normalized path", error.message.orEmpty().contains("/users"))
        assertTrue("Message should include GET", error.message.orEmpty().contains("GET"))
        assertTrue("Message should include POST", error.message.orEmpty().contains("POST"))
        assertTrue(
            "Message should include UserController",
            error.message.orEmpty().contains("com.acme.UserController"),
        )
        assertTrue(
            "Message should include AdminController",
            error.message.orEmpty().contains("com.acme.AdminController"),
        )
    }

    @Test
    fun testUsesFolderOwnerWhenClassNameIsBlank() {
        val error = expectConflict(
            endpoint("/users", HttpMethod.GET, " ", "Users"),
            endpoint("/users", HttpMethod.POST, null, "Admin"),
        )

        assertTrue("Message should include the Users folder", error.message.orEmpty().contains("Users"))
        assertTrue("Message should include the Admin folder", error.message.orEmpty().contains("Admin"))
    }

    @Test
    fun testAllowsUnresolvedEndpointsToShareAPath() {
        val splitter = OpenApiMultiDocumentSplitter(
            listOf(
                endpoint("/users", HttpMethod.GET),
                endpoint("/users", HttpMethod.POST, " ", " "),
            ),
        )

        assertNotNull("Unresolved endpoints should share the Unresolved owner", splitter)
    }

    @Test
    fun testIgnoresNonHttpAndUnnormalizablePaths() {
        val splitter = OpenApiMultiDocumentSplitter(
            listOf(
                grpcEndpoint("com.acme.UserGrpc"),
                grpcEndpoint("com.acme.AdminGrpc"),
                endpoint("/users/{unclosed", HttpMethod.GET, "com.acme.UserController"),
                endpoint("/users/{unclosed", HttpMethod.POST, "com.acme.AdminController"),
            ),
        )

        assertNotNull("Endpoints omitted by the formatter should not create ownership conflicts", splitter)
    }

    @Test
    fun testRejectsSpringPathVariantsThatNormalizeToSamePath() {
        val error = expectConflict(
            endpoint("/users/:id", HttpMethod.GET, "com.acme.UserController"),
            endpoint("/users/{id:\\d+}", HttpMethod.POST, "com.acme.AdminController"),
        )

        assertTrue(
            "Message should include the normalized Spring path",
            error.message.orEmpty().contains("/users/{id}"),
        )
    }

    @Test
    fun testSplitsYamlPathsByControllerAndPreservesRootEnvelope() {
        val splitter = OpenApiMultiDocumentSplitter(
            listOf(
                endpoint("/users", HttpMethod.GET, "com.acme.UserController"),
                endpoint("/orders", HttpMethod.POST, "com.acme.OrderController"),
            ),
        )
        val document = document(
            linkedMapOf(
                "/users" to pathItem(HttpMethod.GET, "getUsers"),
                "/orders" to pathItem(HttpMethod.POST, "createOrder"),
            ),
        )

        val result = splitter.split(document, OpenApiOutputFormat.YAML)

        assertEquals("Should emit one fragment per controller", 2, result.pathFragmentCount)
        assertEquals("Should not emit schemas for a schema-less document", 0, result.schemaCount)
        assertEquals(
            "Additional documents should preserve path fragment order",
            listOf("paths/UserController.yaml", "paths/OrderController.yaml"),
            result.additionalDocuments.keys.toList(),
        )
        assertEquals("Root info should be preserved", document.info, result.rootDocument.info)
        assertEquals("Root servers should be preserved", document.servers, result.rootDocument.servers)
        assertEquals("Root tags should be preserved", document.tags, result.rootDocument.tags)
        assertEquals(
            "Root path should reference its controller fragment",
            "./paths/UserController.yaml#/paths/~1users",
            result.rootDocument.paths.getValue("/users").`$ref`,
        )

        val users = result.additionalDocuments.getValue("paths/UserController.yaml") as OpenApiPathsFragment
        assertEquals("Controller metadata should be emitted", "com.acme.UserController", users.javaController)
        assertNull("Controller fragment should not have folder metadata", users.easyApiFolder)
        assertNull("Controller fragment should not be unresolved", users.easyApiUnresolved)
        assertSame(
            "Path operation should be preserved",
            document.paths.getValue("/users").get,
            users.paths.getValue("/users").get,
        )
        val fragmentJson = OpenApiSerializer.toJson(users)
        val fragmentYaml = OpenApiSerializer.toYaml(users)
        assertTrue("Gson should emit the controller wire extension", fragmentJson.contains("\"x-java-controller\""))
        assertTrue("Jackson should emit the controller wire extension", fragmentYaml.contains("x-java-controller:"))
        assertFalse("Null folder extension should be omitted", fragmentJson.contains("x-easyapi-folder"))
        assertFalse("Null unresolved extension should be omitted", fragmentYaml.contains("x-easyapi-unresolved"))
    }

    @Test
    fun testEscapesJsonPointerTokensAndUsesJsonExtension() {
        val path = "/users/~draft/{id}"
        val result = OpenApiMultiDocumentSplitter(
            listOf(endpoint(path, HttpMethod.GET, "com.acme.UserController")),
        ).split(
            document(
                linkedMapOf(path to pathItem(HttpMethod.GET, "getDraft")),
                ComponentsObject(linkedMapOf("User" to SchemaObject(type = "object"))),
            ),
            OpenApiOutputFormat.JSON,
        )

        assertEquals(
            "JSON output should use JSON for files and escaped path pointers",
            "./paths/UserController.json#/paths/~1users~1~0draft~1{id}",
            result.rootDocument.paths.getValue(path).`$ref`,
        )
        assertTrue(
            "JSON output should name every additional document with .json",
            result.additionalDocuments.keys.all { it.endsWith(".json") },
        )
        assertEquals(
            "JSON root schema refs should target the JSON schema document",
            "./schemas/schemas.json#/components/schemas/User",
            result.rootDocument.components!!.schemas!!.getValue("User").`$ref`,
        )
    }

    @Test
    fun testAllocatesShortestUniquePackageSuffixIndependentOfEndpointOrder() {
        val first = endpoint("/patient", HttpMethod.GET, "com.acme.patient.UserController")
        val second = endpoint("/admin", HttpMethod.GET, "com.acme.admin.UserController")
        val document = document(
            linkedMapOf(
                "/patient" to pathItem(HttpMethod.GET, "getPatient"),
                "/admin" to pathItem(HttpMethod.GET, "getAdmin"),
            ),
        )

        val forward = OpenApiMultiDocumentSplitter(listOf(first, second))
            .split(document, OpenApiOutputFormat.YAML)
        val reversed = OpenApiMultiDocumentSplitter(listOf(second, first))
            .split(document, OpenApiOutputFormat.YAML)

        assertEquals(
            "Patient controller should use the shortest distinguishing package suffix",
            "./paths/patient-UserController.yaml#/paths/~1patient",
            forward.rootDocument.paths.getValue("/patient").`$ref`,
        )
        assertEquals(
            "Admin controller should use the shortest distinguishing package suffix",
            "./paths/admin-UserController.yaml#/paths/~1admin",
            forward.rootDocument.paths.getValue("/admin").`$ref`,
        )
        assertEquals(
            "Endpoint order must not change allocated filenames",
            forward.rootDocument.paths.mapValues { it.value.`$ref` },
            reversed.rootDocument.paths.mapValues { it.value.`$ref` },
        )
    }

    @Test
    fun testUsesFolderAndUnresolvedFragmentsWithStableWarnings() {
        val splitter = OpenApiMultiDocumentSplitter(
            listOf(
                endpoint("/folder", HttpMethod.GET, null, "Patient APIs"),
                endpoint("/unresolved", HttpMethod.GET),
                endpoint("/renamed-before-hook", HttpMethod.GET, "com.acme.UserController"),
            ),
        )
        val result = splitter.split(
            document(
                linkedMapOf(
                    "/folder" to pathItem(HttpMethod.GET, "folder"),
                    "/unresolved" to pathItem(HttpMethod.GET, "unresolved"),
                    "/hook-added" to pathItem(HttpMethod.GET, "hookAdded"),
                ),
            ),
            OpenApiOutputFormat.YAML,
        )

        val folder = result.additionalDocuments.getValue("paths/Patient APIs.yaml") as OpenApiPathsFragment
        val unresolved = result.additionalDocuments.getValue("paths/Unresolved.yaml") as OpenApiPathsFragment
        assertEquals("Folder fragment should carry folder metadata", "Patient APIs", folder.easyApiFolder)
        assertNull("Folder fragment should not carry controller metadata", folder.javaController)
        assertEquals("Unresolved marker should be true", true, unresolved.easyApiUnresolved)
        assertEquals(
            "Endpoint and hook paths without an owner should share Unresolved",
            listOf("/unresolved", "/hook-added"),
            unresolved.paths.keys.toList(),
        )
        assertEquals("Should count actual unresolved document paths", 2, result.unresolvedPathCount)
        assertEquals("Warnings should be distinct and stable", result.warnings.distinct(), result.warnings)
        assertTrue(
            "Folder fallback should produce a warning",
            result.warnings.any { it.contains("/folder") && it.contains("folder", ignoreCase = true) },
        )
        assertTrue(
            "Unowned endpoint should produce a warning",
            result.warnings.any { it.contains("/unresolved") },
        )
        assertTrue(
            "Hook-added path should produce a warning",
            result.warnings.any { it.contains("/hook-added") },
        )
    }

    @Test
    fun testSanitizesWindowsNamesAndResolvesCaseAndCleaningCollisions() {
        val longFolder = "x".repeat(160)
        val endpoints = listOf(
            endpoint("/reserved", HttpMethod.GET, null, "CON"),
            endpoint("/reserved-extension", HttpMethod.GET, null, "CON.txt"),
            endpoint("/trailing", HttpMethod.GET, null, "Trailing. "),
            endpoint("/invalid-a", HttpMethod.GET, null, "a/b"),
            endpoint("/invalid-b", HttpMethod.GET, null, "a\\b"),
            endpoint("/case-a", HttpMethod.GET, null, "Users"),
            endpoint("/case-b", HttpMethod.GET, null, "users"),
            endpoint("/long", HttpMethod.GET, null, longFolder),
        )
        val paths = linkedMapOf<String, PathItemObject>()
        endpoints.forEach {
            val metadata = it.httpMetadata!!
            paths[metadata.path] = pathItem(metadata.method, metadata.path)
        }

        val result = OpenApiMultiDocumentSplitter(endpoints)
            .split(document(paths), OpenApiOutputFormat.YAML)
        val stems = result.additionalDocuments.keys
            .filter { it.startsWith("paths/") }
            .map { it.removePrefix("paths/").removeSuffix(".yaml") }

        assertTrue("Reserved Windows name should be prefixed", "_CON" in stems)
        assertTrue("Reserved Windows basename should be prefixed", "_CON.txt" in stems)
        assertTrue("Trailing dot and spaces should be removed", "Trailing" in stems)
        assertEquals(
            "Every owner should receive a case-insensitively unique filename",
            stems.size,
            stems.map { it.lowercase() }.distinct().size,
        )
        assertTrue(
            "Invalid Windows filename characters should be removed",
            stems.all { !Regex("""[<>:"/\\|?*\u0000-\u001f]""").containsMatchIn(it) },
        )
        assertTrue("Filenames should not end with dot or space", stems.all { !it.endsWith(".") && !it.endsWith(" ") })
        assertTrue("Filenames should stay within 120 characters", stems.all { it.length <= 120 })

        val reversed = OpenApiMultiDocumentSplitter(endpoints.reversed())
            .split(document(paths), OpenApiOutputFormat.YAML)
        assertEquals(
            "Collision hashes should not depend on endpoint order",
            result.rootDocument.paths.mapValues { it.value.`$ref` },
            reversed.rootDocument.paths.mapValues { it.value.`$ref` },
        )
    }

    @Test
    fun testExtractsSchemasAndRewritesEveryPathSchemaReference() {
        val internalRef = "#/components/schemas/User~Profile"
        val externalRef = "./common.yaml#/components/schemas/Error"
        val operation = OperationObject(
            tags = listOf("users"),
            operationId = "allRefs",
            parameters = listOf(
                ParameterObject(
                    name = "filter",
                    `in` = "query",
                    schema = SchemaObject(`$ref` = internalRef),
                    example = "kept",
                ),
            ),
            requestBody = RequestBodyObject(
                content = linkedMapOf(
                    "application/json" to MediaTypeObject(
                        schema = SchemaObject(
                            properties = linkedMapOf("property" to SchemaObject(`$ref` = internalRef)),
                            additionalProperties = SchemaObject(`$ref` = internalRef),
                            items = SchemaObject(`$ref` = internalRef),
                            example = "body-kept",
                        ),
                    ),
                ),
            ),
            responses = linkedMapOf(
                "200" to ResponseObject(
                    description = "OK",
                    content = linkedMapOf(
                        "application/json" to MediaTypeObject(SchemaObject(`$ref` = internalRef)),
                    ),
                ),
                "400" to ResponseObject(
                    description = "Bad",
                    content = linkedMapOf(
                        "application/json" to MediaTypeObject(SchemaObject(`$ref` = externalRef)),
                    ),
                ),
            ),
        )
        val pathItem = PathItemObject(
            get = operation,
            post = operation.copy(operationId = "post"),
            put = operation.copy(operationId = "put"),
            delete = operation.copy(operationId = "delete"),
            patch = operation.copy(operationId = "patch"),
            head = operation.copy(operationId = "head"),
            options = operation.copy(operationId = "options"),
        )
        val schemas = linkedMapOf(
            "User~Profile" to SchemaObject(
                type = "object",
                properties = linkedMapOf("manager" to SchemaObject(`$ref` = internalRef)),
            ),
            "Wrapper/Result" to SchemaObject(items = SchemaObject(`$ref` = internalRef)),
        )
        val document = document(
            linkedMapOf("/users" to pathItem),
            ComponentsObject(schemas),
        )

        val result = OpenApiMultiDocumentSplitter(
            listOf(endpoint("/users", HttpMethod.GET, "com.acme.UserController")),
        ).split(document, OpenApiOutputFormat.YAML)

        assertEquals("Should count extracted schemas", 2, result.schemaCount)
        assertEquals(
            "Schema document should follow path fragments",
            listOf("paths/UserController.yaml", "schemas/schemas.yaml"),
            result.additionalDocuments.keys.toList(),
        )
        assertEquals(
            "Root schema reference should use an escaped JSON pointer",
            "./schemas/schemas.yaml#/components/schemas/User~0Profile",
            result.rootDocument.components!!.schemas!!.getValue("User~Profile").`$ref`,
        )
        assertEquals(
            "Root schema name slash should be escaped",
            "./schemas/schemas.yaml#/components/schemas/Wrapper~1Result",
            result.rootDocument.components!!.schemas!!.getValue("Wrapper/Result").`$ref`,
        )

        val schemaDocument = result.additionalDocuments.getValue("schemas/schemas.yaml") as OpenApiSchemasDocument
        assertEquals(
            "Schema document internal references must remain local",
            internalRef,
            schemaDocument.components.schemas!!.getValue("User~Profile")
                .properties!!.getValue("manager").`$ref`,
        )

        val fragment = result.additionalDocuments.getValue("paths/UserController.yaml") as OpenApiPathsFragment
        val rewritten = "../schemas/schemas.yaml#/components/schemas/User~Profile"
        val methods = listOf(
            fragment.paths.getValue("/users").get,
            fragment.paths.getValue("/users").post,
            fragment.paths.getValue("/users").put,
            fragment.paths.getValue("/users").delete,
            fragment.paths.getValue("/users").patch,
            fragment.paths.getValue("/users").head,
            fragment.paths.getValue("/users").options,
        )
        assertTrue(
            "All seven HTTP methods should have their response schema references rewritten",
            methods.all {
                it!!.responses.getValue("200").content!!
                    .getValue("application/json").schema.`$ref` == rewritten
            },
        )
        val get = methods.first()!!
        assertEquals("Parameter schema ref should be rewritten", rewritten, get.parameters!!.single().schema.`$ref`)
        assertEquals("Parameter example should be preserved", "kept", get.parameters!!.single().example)
        val requestSchema = get.requestBody!!.content.getValue("application/json").schema
        assertEquals(
            "Property schema ref should be rewritten",
            rewritten,
            requestSchema.properties!!.getValue("property").`$ref`,
        )
        assertEquals(
            "additionalProperties schema ref should be rewritten",
            rewritten,
            requestSchema.additionalProperties!!.`$ref`,
        )
        assertEquals("items schema ref should be rewritten", rewritten, requestSchema.items!!.`$ref`)
        assertEquals("Schema example should be preserved", "body-kept", requestSchema.example)
        assertEquals(
            "Existing external refs must remain unchanged",
            externalRef,
            get.responses.getValue("400").content!!.getValue("application/json").schema.`$ref`,
        )
        assertEquals("Operation id should be preserved", "allRefs", get.operationId)
        assertEquals("Tags should be preserved", listOf("users"), get.tags)
        assertEquals(
            "Response insertion order should be preserved",
            listOf("200", "400"),
            get.responses.keys.toList(),
        )
    }

    @Test
    fun testDoesNotGenerateSchemaDocumentWhenSchemasAreAbsent() {
        val result = OpenApiMultiDocumentSplitter(
            listOf(endpoint("/users", HttpMethod.GET, "com.acme.UserController")),
        ).split(
            document(
                linkedMapOf("/users" to pathItem(HttpMethod.GET, "getUsers")),
                ComponentsObject(schemas = linkedMapOf()),
            ),
            OpenApiOutputFormat.YAML,
        )

        assertEquals("Empty schemas should not produce a schema document", 0, result.schemaCount)
        assertFalse(
            "Additional documents should not include a schemas file",
            result.additionalDocuments.keys.any { it.startsWith("schemas/") },
        )
        assertTrue(
            "Root components should remain present with an empty schemas map",
            result.rootDocument.components?.schemas?.isEmpty() == true,
        )
    }

    @Test
    fun testRejectsAlwaysAskOutputFormat() {
        val splitter = OpenApiMultiDocumentSplitter(
            listOf(endpoint("/users", HttpMethod.GET, "com.acme.UserController")),
        )

        try {
            splitter.split(
                document(linkedMapOf("/users" to pathItem(HttpMethod.GET, "getUsers"))),
                OpenApiOutputFormat.ALWAYS_ASK,
            )
            fail("ALWAYS_ASK should be resolved before splitting")
        } catch (error: IllegalArgumentException) {
            assertTrue(
                "Failure should identify unresolved output format",
                error.message.orEmpty().contains("ALWAYS_ASK"),
            )
        }
    }

    private fun expectConflict(vararg endpoints: ApiEndpoint): IllegalArgumentException {
        return try {
            OpenApiMultiDocumentSplitter(endpoints.toList())
            fail("Expected conflicting path owners to be rejected")
            throw AssertionError("unreachable")
        } catch (error: IllegalArgumentException) {
            error
        }
    }

    private fun document(
        paths: LinkedHashMap<String, PathItemObject>,
        components: ComponentsObject? = null,
    ) = OpenApiDocument(
        info = InfoObject(title = "Test API", version = "1.0.0"),
        servers = listOf(ServerObject("https://api.example.com")),
        tags = listOf(TagObject("test")),
        paths = paths,
        components = components,
    )

    private fun pathItem(method: HttpMethod, operationId: String): PathItemObject =
        PathItemObject().withMethod(
            method,
            OperationObject(
                operationId = operationId,
                responses = linkedMapOf("200" to ResponseObject("OK")),
            ),
        )

    private fun endpoint(
        path: String,
        method: HttpMethod,
        className: String? = null,
        folder: String? = null,
    ) = ApiEndpoint(
        name = method.name,
        folder = folder,
        className = className,
        metadata = httpMetadata(path, method),
    )

    private fun grpcEndpoint(className: String) = ApiEndpoint(
        name = "query",
        className = className,
        metadata = GrpcMetadata(
            path = "/acme.UserService/query",
            serviceName = "UserService",
            methodName = "query",
            packageName = "acme",
            streamingType = GrpcStreamingType.UNARY,
        ),
    )
}
