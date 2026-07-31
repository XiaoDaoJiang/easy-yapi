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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.URI

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
        assertEquals(
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
            "./paths/UserController.json#/paths/~1users~1~0draft~1%7Bid%7D",
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
    fun testEncodesLiteralPercentTripletsInRawPathAndSchemaKeys() {
        val path = "/files/%2F"
        val schemaName = "Rate%20Plan"
        val result = OpenApiMultiDocumentSplitter(
            listOf(endpoint(path, HttpMethod.GET, "com.acme.UserController")),
        ).split(
            document(
                linkedMapOf(path to pathItem(HttpMethod.GET, "getFile")),
                ComponentsObject(linkedMapOf(schemaName to SchemaObject(type = "object"))),
            ),
            OpenApiOutputFormat.YAML,
        )

        val pathRef = result.rootDocument.paths.getValue(path).`$ref`!!
        val schemaRef = result.rootDocument.components!!.schemas!!.getValue(schemaName).`$ref`!!
        assertEquals(
            "A raw path key should treat a percent triplet as literal text",
            "./paths/UserController.yaml#/paths/~1files~1%252F",
            pathRef,
        )
        assertEquals(
            "A raw schema key should treat a percent triplet as literal text",
            "./schemas/schemas.yaml#/components/schemas/Rate%2520Plan",
            schemaRef,
        )
        assertEquals("Raw path reference should be a strict ASCII URI", pathRef, URI.create(pathRef).toASCIIString())
        assertEquals("Raw schema reference should be a strict ASCII URI", schemaRef, URI.create(schemaRef).toASCIIString())
    }

    @Test
    fun testEncodesFolderFilenameAndPathPointerAsUriReference() {
        val folder = "患者 API#100%"
        val path = "/patients/{id}"
        val result = OpenApiMultiDocumentSplitter(
            listOf(endpoint(path, HttpMethod.GET, null, folder)),
        ).split(
            document(linkedMapOf(path to pathItem(HttpMethod.GET, "getPatient"))),
            OpenApiOutputFormat.YAML,
        )

        assertTrue(
            "Actual additional document key should keep the readable filename",
            "paths/$folder.yaml" in result.additionalDocuments,
        )
        val ref = result.rootDocument.paths.getValue(path).`$ref`!!
        assertEquals(
            "Reference should encode only its URI path segment and fragment",
            "./paths/%E6%82%A3%E8%80%85%20API%23100%25.yaml#/paths/~1patients~1%7Bid%7D",
            ref,
        )
        assertEquals("Reference should be a strict ASCII URI", ref, URI.create(ref).toASCIIString())
    }

    @Test
    fun testEncodesSpecialSchemaNamesAndRewrittenInternalSchemaRefs() {
        val specialSchemaName = "用户 Profile#%/~"
        val referencedSchemaName = "User Profile"
        val result = OpenApiMultiDocumentSplitter(
            listOf(endpoint("/users", HttpMethod.GET, "com.acme.UserController")),
        ).split(
            document(
                linkedMapOf(
                    "/users" to PathItemObject(
                        get = OperationObject(
                            operationId = "getUsers",
                            responses = linkedMapOf(
                                "200" to ResponseObject(
                                    description = "OK",
                                    content = linkedMapOf(
                                        "application/json" to MediaTypeObject(
                                            SchemaObject(
                                                `$ref` = "#/components/schemas/$referencedSchemaName",
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                ComponentsObject(
                    linkedMapOf(
                        specialSchemaName to SchemaObject(type = "object"),
                        referencedSchemaName to SchemaObject(type = "object"),
                    ),
                ),
            ),
            OpenApiOutputFormat.YAML,
        )

        val rootRef = result.rootDocument.components!!.schemas!!
            .getValue(specialSchemaName).`$ref`!!
        assertEquals(
            "Root schema ref should combine JSON Pointer and URI fragment escaping",
            "./schemas/schemas.yaml#/components/schemas/" +
                "%E7%94%A8%E6%88%B7%20Profile%23%25~1~0",
            rootRef,
        )
        assertEquals("Root schema ref should be a strict ASCII URI", rootRef, URI.create(rootRef).toASCIIString())

        val fragment = result.additionalDocuments.getValue("paths/UserController.yaml") as OpenApiPathsFragment
        val rewrittenRef = fragment.paths.getValue("/users").get!!
            .responses.getValue("200").content!!
            .getValue("application/json").schema.`$ref`!!
        assertEquals(
            "Rewritten internal schema ref should URI-encode its fragment",
            "../schemas/schemas.yaml#/components/schemas/User%20Profile",
            rewrittenRef,
        )
        assertEquals(
            "Rewritten schema ref should be a strict ASCII URI",
            rewrittenRef,
            URI.create(rewrittenRef).toASCIIString(),
        )
    }

    @Test
    fun testPreservesValidPercentEscapesWhenRewritingInternalSchemaRefs() {
        val refs = linkedMapOf(
            "encoded-space" to "#/components/schemas/User%20Profile",
            "encoded-hash" to "#/components/schemas/Hash%23Tag",
            "raw-space" to "#/components/schemas/User Profile",
            "raw-hash" to "#/components/schemas/Hash#Tag",
            "raw-unicode" to "#/components/schemas/用户{}",
            "invalid-percent" to "#/components/schemas/Bad%zz",
            "external" to "./common.yaml#/components/schemas/User%20Profile",
        )
        val operation = OperationObject(
            operationId = "getUsers",
            responses = linkedMapOf(
                "200" to ResponseObject(
                    description = "OK",
                    content = linkedMapOf(
                        "application/json" to MediaTypeObject(
                            schema = SchemaObject(
                                properties = refs.mapValuesTo(linkedMapOf()) { (_, ref) ->
                                    SchemaObject(`$ref` = ref)
                                },
                            ),
                        ),
                    ),
                ),
            ),
        )
        val result = OpenApiMultiDocumentSplitter(
            listOf(endpoint("/users", HttpMethod.GET, "com.acme.UserController")),
        ).split(
            document(
                linkedMapOf("/users" to PathItemObject(get = operation)),
                ComponentsObject(linkedMapOf("Dummy" to SchemaObject(type = "object"))),
            ),
            OpenApiOutputFormat.YAML,
        )

        val rewritten = (result.additionalDocuments.getValue("paths/UserController.yaml")
            as OpenApiPathsFragment)
            .paths.getValue("/users").get!!.responses.getValue("200").content!!
            .getValue("application/json").schema.properties!!
            .mapValues { it.value.`$ref` }
        assertEquals(
            "Existing percent-encoded space must not be encoded again",
            "../schemas/schemas.yaml#/components/schemas/User%20Profile",
            rewritten["encoded-space"],
        )
        assertEquals(
            "Existing percent-encoded hash must not be encoded again",
            "../schemas/schemas.yaml#/components/schemas/Hash%23Tag",
            rewritten["encoded-hash"],
        )
        assertEquals(
            "Raw spaces should be percent-encoded",
            "../schemas/schemas.yaml#/components/schemas/User%20Profile",
            rewritten["raw-space"],
        )
        assertEquals(
            "Raw hashes should be percent-encoded",
            "../schemas/schemas.yaml#/components/schemas/Hash%23Tag",
            rewritten["raw-hash"],
        )
        assertEquals(
            "Raw Unicode and braces should be encoded as UTF-8",
            "../schemas/schemas.yaml#/components/schemas/%E7%94%A8%E6%88%B7%7B%7D",
            rewritten["raw-unicode"],
        )
        assertEquals(
            "Invalid percent escapes should encode the percent sign",
            "../schemas/schemas.yaml#/components/schemas/Bad%25zz",
            rewritten["invalid-percent"],
        )
        assertEquals(
            "External references must remain unchanged",
            refs.getValue("external"),
            rewritten["external"],
        )
        rewritten.values.filterNotNull().forEach { ref ->
            assertEquals("Every rewritten reference should be a strict ASCII URI", ref, URI.create(ref).toASCIIString())
        }
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
    fun testKeepsHookPathVariantUnresolvedInsteadOfInheritingNormalizedOwner() {
        val hookPath = "/users/:id"
        val result = OpenApiMultiDocumentSplitter(
            listOf(endpoint("/users/{id}", HttpMethod.GET, "com.acme.UserController")),
        ).split(
            document(linkedMapOf(hookPath to pathItem(HttpMethod.GET, "hookUser"))),
            OpenApiOutputFormat.YAML,
        )

        assertEquals(
            "Hook path should reference the Unresolved fragment",
            "./paths/Unresolved.yaml#/paths/~1users~1:id",
            result.rootDocument.paths.getValue(hookPath).`$ref`,
        )
        val unresolved = result.additionalDocuments.getValue("paths/Unresolved.yaml") as OpenApiPathsFragment
        assertTrue("Unresolved fragment should contain the exact hook path", hookPath in unresolved.paths)
        assertEquals("Hook path should increment unresolved count", 1, result.unresolvedPathCount)
        assertTrue(
            "Hook path should produce an ownership warning",
            result.warnings.any { it.contains(hookPath) && it.contains("owner", ignoreCase = true) },
        )
    }

    @Test
    fun testGroupsDifferentPathsFromSameControllerIntoOneFragment() {
        val result = OpenApiMultiDocumentSplitter(
            listOf(
                endpoint("/users", HttpMethod.GET, "com.acme.UserController"),
                endpoint("/users/{id}", HttpMethod.GET, "com.acme.UserController"),
            ),
        ).split(
            document(
                linkedMapOf(
                    "/users" to pathItem(HttpMethod.GET, "getUsers"),
                    "/users/{id}" to pathItem(HttpMethod.GET, "getUser"),
                ),
            ),
            OpenApiOutputFormat.YAML,
        )

        assertEquals("One controller should produce one fragment", 1, result.pathFragmentCount)
        assertEquals(
            "Both root paths should reference the same controller file",
            setOf("./paths/UserController.yaml"),
            result.rootDocument.paths.values
                .map { it.`$ref`!!.substringBefore("#") }
                .toSet(),
        )
        val fragment = result.additionalDocuments.getValue("paths/UserController.yaml") as OpenApiPathsFragment
        assertEquals(
            "Controller fragment should contain both paths",
            listOf("/users", "/users/{id}"),
            fragment.paths.keys.toList(),
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
    fun testLimitsUnicodeFilenamesByUtf8BytesAndKeepsStableHash() {
        val longFolder = "中".repeat(37) + "😀" + "文".repeat(80)
        val endpoint = endpoint("/unicode", HttpMethod.GET, null, longFolder)
        val document = document(linkedMapOf("/unicode" to pathItem(HttpMethod.GET, "unicode")))

        val first = OpenApiMultiDocumentSplitter(listOf(endpoint))
            .split(document, OpenApiOutputFormat.YAML)
        val second = OpenApiMultiDocumentSplitter(listOf(endpoint))
            .split(document, OpenApiOutputFormat.YAML)
        val stem = first.additionalDocuments.keys.single()
            .removePrefix("paths/")
            .removeSuffix(".yaml")

        assertTrue(
            "Filename stem must stay within the 120-byte UTF-8 budget",
            stem.toByteArray(Charsets.UTF_8).size <= 120,
        )
        assertTrue("Long filename should retain an 8-hex stable hash", Regex(""".*-[0-9a-f]{8}$""").matches(stem))
        assertFalse("Code-point truncation must not leave an unpaired surrogate", stem.any(Char::isSurrogate))
        assertEquals(
            "Filename hashing should be stable",
            first.additionalDocuments.keys,
            second.additionalDocuments.keys,
        )
        val ref = first.rootDocument.paths.getValue("/unicode").`$ref`!!
        assertEquals("Reference using the bounded filename should be a valid URI", ref, URI.create(ref).toASCIIString())
    }

    @Test
    fun testDoesNotSplitEmojiWhenFilenameCrossesUtf8Budget() {
        val longFolder = "a".repeat(111) + "😀" + "b".repeat(20)
        val path = "/emoji"
        val result = OpenApiMultiDocumentSplitter(
            listOf(endpoint(path, HttpMethod.GET, null, longFolder)),
        ).split(
            document(linkedMapOf(path to pathItem(HttpMethod.GET, "emoji"))),
            OpenApiOutputFormat.YAML,
        )
        val stem = result.additionalDocuments.keys.single()
            .removePrefix("paths/")
            .removeSuffix(".yaml")

        assertTrue("The complete ASCII prefix should fit", stem.startsWith("a".repeat(111)))
        assertFalse("An emoji that crosses the byte budget must be omitted whole", stem.contains("😀"))
        assertEquals(
            "The bounded stem must contain only complete Unicode code points",
            stem,
            stem.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8),
        )
        assertTrue("Filename stem must remain within 120 UTF-8 bytes", stem.toByteArray(Charsets.UTF_8).size <= 120)
    }

    @Test
    fun testReplacesUnpairedSurrogatesBeforeBuildingFilenameAndReference() {
        val folder = "Bad\uD83DName\uDC00"
        val path = "/surrogate"
        val result = OpenApiMultiDocumentSplitter(
            listOf(endpoint(path, HttpMethod.GET, null, folder)),
        ).split(
            document(linkedMapOf(path to pathItem(HttpMethod.GET, "surrogate"))),
            OpenApiOutputFormat.YAML,
        )

        assertEquals(
            "Unpaired surrogates should be replaced in the physical filename",
            setOf("paths/Bad-Name-.yaml"),
            result.additionalDocuments.keys,
        )
        val ref = result.rootDocument.paths.getValue(path).`$ref`!!
        assertEquals(
            "Root reference must use the same sanitized filename",
            "./paths/Bad-Name-.yaml#/paths/~1surrogate",
            ref,
        )
        assertEquals("Sanitized reference should be a strict ASCII URI", ref, URI.create(ref).toASCIIString())
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
