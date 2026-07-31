package com.itangcent.easyapi.channel.openapi

import com.itangcent.easyapi.channel.spi.ChannelConfig
import com.itangcent.easyapi.core.config.ConfigReader
import com.itangcent.easyapi.core.export.ApiEndpoint
import com.itangcent.easyapi.core.export.ExportContext
import com.itangcent.easyapi.core.export.ExportResult
import com.itangcent.easyapi.core.export.GrpcMetadata
import com.itangcent.easyapi.core.export.GrpcStreamingType
import com.itangcent.easyapi.core.export.HttpMethod
import com.itangcent.easyapi.core.export.httpMetadata
import com.itangcent.easyapi.core.psi.model.FieldModel
import com.itangcent.easyapi.core.psi.model.ObjectModel
import com.itangcent.easyapi.core.settings.SettingBinder
import com.itangcent.easyapi.core.settings.update
import com.itangcent.easyapi.testFramework.EasyApiLightCodeInsightFixtureTestCase
import com.itangcent.easyapi.testFramework.TestConfigReader
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.psi.PsiMethod
import com.intellij.testFramework.registerServiceInstance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.io.File

/**
 * Tests for [OpenApiChannel].
 *
 * Covers:
 *  - Channel metadata contract (id / displayName / supports flags / action text).
 *  - gRPC filtering inside `export`: empty input → Error; gRPC-only →
 *    Error; mixed HTTP+gRPC → only HTTP reach the formatter (Success carries
 *    HTTP count); HTTP-only → Success carrying `OpenApiExportMetadata`.
 *  - `isAvailableFor`: HTTP-only → true; gRPC-only → false; mixed →
 *    true; empty → true.
 *  - `handleResult` writes the file and returns `true`. Uses
 *    `ChannelConfig.FileConfig` so the file path is deterministic (no dialog).
 *
 * Pattern mirrors [com.itangcent.easyapi.channel.postman.PostmanChannelWarnPathTest]
 * for the project-aware setup and [com.itangcent.easyapi.channel.dummy.DummyChannelTest]
 * for the channel-metadata assertions.
 */
class OpenApiChannelTest : EasyApiLightCodeInsightFixtureTestCase() {

    private lateinit var channel: OpenApiChannel
    private var previousDialog: TestDialog? = null

    override fun setUp() {
        super.setUp()
        channel = OpenApiChannel()
        // Messages.showInfoMessage in handleResult would otherwise block on a
        // real dialog — replace with a no-op for the file-save test.
        previousDialog = try {
            TestDialogManager.setTestDialog(TestDialog { 0 })
        } catch (_: Exception) {
            null
        }
        // OpenApiSettings defaults to "ALWAYS_ASK", which would cause
        // `Messages.showChooseDialog` to block tests that don't pass an
        // explicit `channelConfig`. Pin the persistent `outputFormat` to
        // "JSON" for the duration of each test so the settings-fallback path
        // resolves to JSON. Tests that need YAML / BINARY / ALWAYS_ASK
        // behavior set their own value and reset to "JSON" in their `finally`
        // blocks.
        SettingBinder.getInstance(project).update(OpenApiSettings::class) {
            outputFormat = "JSON"
        }
    }

    override fun tearDown() {
        try {
            previousDialog?.let { TestDialogManager.setTestDialog(it) }
            SettingBinder.getInstance(project).update(OpenApiSettings::class) {
                outputFormat = "JSON"
            }
        } finally {
            super.tearDown()
        }
    }

    // ─── Channel metadata contract ───────────────────────

    fun testChannelIdIsOpenapi() {
        assertEquals("openapi", channel.id)
    }

    fun testChannelDisplayNameIsOpenAPI() {
        // displayName carries the "(Beta)" suffix.
        assertEquals("OpenAPI (Beta)", channel.displayName)
    }

    fun testChannelIsMarkedAsBeta() {
        // OpenApiChannel is in beta status.
        assertTrue("OpenApiChannel should be marked as beta", channel.beta)
    }

    fun testChannelSupportsHttpOnly() {
        assertTrue(channel.supportsHttp)
        assertFalse(channel.supportsGrpc)
    }

    fun testChannelExposedAsActionWithExpectedText() {
        assertTrue(channel.exposeAsAction)
        assertEquals("Export to OpenAPI", channel.actionText)
    }

    fun testChannelDisabledByDefault() {
        // OpenAPI channel is opt-in — the user must enable it in Settings.
        assertFalse(channel.enabledByDefault)
    }

    fun testChannelSettingsTypeIsOpenApiSettings() {
        assertEquals(OpenApiSettings::class, channel.settingsType)
    }

    fun testChannelRuleKeysCollectedFromOpenApiRuleKeys() {
        val keys = channel.ruleKeys()
        assertEquals(6, keys.size)
        assertTrue(keys.any { it.name == "openapi.host" })
        assertTrue(keys.any { it.name == "openapi.server.url" })
        assertTrue(keys.any { it.name == "openapi.info.title" })
        assertTrue(keys.any { it.name == "openapi.info.version" })
        assertTrue(keys.any { it.name == "openapi.info.description" })
        assertTrue(keys.any { it.name == "openapi.format.after" })
    }

    // ─── export() — gRPC filtering ───────────────────────

    fun testExportWithEmptyInputReturnsError() = runTest {
        val context = ExportContext(
            project = project,
            endpoints = emptyList(),
            channelId = "openapi",
        )
        val result = channel.export(context)
        assertTrue("Empty input should yield ExportResult.Error, got: $result", result is ExportResult.Error)
        assertEquals("No HTTP endpoints to export", (result as ExportResult.Error).message)
    }

    fun testExportWithGrpcOnlyInputReturnsError() = runTest {
        val grpcEndpoint = grpcEndpoint()
        val context = ExportContext(
            project = project,
            endpoints = listOf(grpcEndpoint),
            channelId = "openapi",
        )
        val result = channel.export(context)
        assertTrue("gRPC-only input should yield ExportResult.Error, got: $result", result is ExportResult.Error)
        assertEquals("No HTTP endpoints to export", (result as ExportResult.Error).message)
    }

    fun testExportWithMixedHttpAndGrpcFiltersGrpcAndReturnsSuccessWithHttpCount() = runTest {
        val http1 = httpEndpoint(name = "Get user", path = "/users/{id}", method = HttpMethod.GET, methodName = "getUser")
        val http2 = httpEndpoint(name = "List users", path = "/users", method = HttpMethod.GET, methodName = "listUsers")
        val grpc = grpcEndpoint()
        val context = ExportContext(
            project = project,
            endpoints = listOf(http1, http2, grpc),
            channelId = "openapi",
        )
        val result = channel.export(context)
        assertTrue("Mixed input should yield ExportResult.Success, got: $result", result is ExportResult.Success)
        val success = result as ExportResult.Success
        assertEquals("Only HTTP endpoints should be counted", 2, success.count)
        val metadata = success.metadata
        assertNotNull("Success should carry OpenApiExportMetadata", metadata)
        assertTrue("Metadata should be OpenApiExportMetadata, got: ${metadata?.javaClass}", metadata is OpenApiExportMetadata)
    }

    fun testExportWithHttpOnlyReturnsSuccessWithMetadata() = runTest {
        val endpoint = httpEndpoint(
            name = "Get user",
            path = "/users/{id}",
            method = HttpMethod.GET,
            methodName = "getUser",
        )
        val context = ExportContext(
            project = project,
            endpoints = listOf(endpoint),
            channelId = "openapi",
        )
        val result = channel.export(context)
        assertTrue("HTTP-only input should yield ExportResult.Success, got: $result", result is ExportResult.Success)
        val success = result as ExportResult.Success
        assertEquals(1, success.count)
        assertEquals("OpenAPI", success.target)
        val metadata = success.metadata as? OpenApiExportMetadata
            ?: error("Expected OpenApiExportMetadata, got: ${success.metadata?.javaClass}")
        // Metadata carries the in-memory document, the format, and the
        // pre-serialized content string.
        assertNotNull(metadata.document)
        assertEquals(OpenApiOutputFormat.JSON, metadata.outputFormat)
        assertTrue("Content should be non-empty JSON", metadata.content.isNotBlank())
        // JSON output starts with `{` (the openapi document root object).
        val trimmed = metadata.content.trim()
        assertTrue("JSON content should start with '{', got: ${trimmed.take(20)}", trimmed.startsWith("{"))
        // The serialized content must contain the OAS version constant.
        assertTrue("Content should embed openapi 3.0.3", metadata.content.contains("\"openapi\": \"3.0.3\""))
    }

    fun testExportYamlFormatWhenConfigured() = runTest {
        val endpoint = httpEndpoint(
            name = "Get user",
            path = "/users/{id}",
            method = HttpMethod.GET,
            methodName = "getUser",
        )
        val context = ExportContext(
            project = project,
            endpoints = listOf(endpoint),
            channelId = "openapi",
            channelConfig = OpenApiConfig(outputFormat = OpenApiOutputFormat.YAML),
        )
        val result = channel.export(context)
        assertTrue(result is ExportResult.Success)
        val metadata = (result as ExportResult.Success).metadata as OpenApiExportMetadata
        assertEquals(OpenApiOutputFormat.YAML, metadata.outputFormat)
        // YAML output should not start with `{`; should not contain a leading `---`
        // marker.
        val trimmed = metadata.content.trimStart()
        assertFalse("YAML content should not start with '{', got: ${trimmed.take(20)}", trimmed.startsWith("{"))
        assertFalse("YAML content should not start with --- marker", trimmed.startsWith("---"))
        assertTrue("YAML content should embed openapi: \"3.0.3\"", metadata.content.contains("openapi: \"3.0.3\""))
    }

    fun testExportMultiDocumentYamlBuildsControllerFragmentMetadata() = runTest {
        val result = channel.export(
            exportContext(
                listOf(
                    httpEndpoint(
                        name = "List users",
                        path = "/users",
                        method = HttpMethod.GET,
                        methodName = "listUsers",
                        className = "com.example.UserController",
                    ),
                ),
                OpenApiOutputFormat.YAML,
            )
        )

        assertTrue("Multi-document YAML export should succeed, got: $result", result is ExportResult.Success)
        val success = result as ExportResult.Success
        val metadata = success.metadata as OpenApiExportMetadata
        assertEquals("HTTP endpoint count should be preserved", 1, success.count)
        assertEquals("OpenAPI", success.target)
        assertEquals(OpenApiDocumentMode.MULTI_FILE_BY_CONTROLLER, metadata.documentMode)
        assertEquals(1, metadata.pathFragmentCount)
        assertEquals(0, metadata.schemaCount)
        assertEquals(0, metadata.unresolvedPathCount)
        assertEquals(
            listOf("paths/UserController.yaml"),
            metadata.additionalFiles.keys.toList(),
        )
        assertTrue(
            "Root YAML should reference the controller fragment",
            metadata.content.contains("./paths/UserController.yaml#/paths/~1users"),
        )
        val fragment = metadata.additionalFiles.getValue("paths/UserController.yaml")
        assertTrue(
            "Controller fragment should contain its source class",
            fragment.contains("x-java-controller:") &&
                fragment.contains("com.example.UserController"),
        )
        assertTrue("Controller fragment should contain the path", fragment.contains("/users:"))
        assertNotNull(
            "Metadata document should keep the full operation before splitting",
            metadata.document.paths.getValue("/users").get,
        )
        assertNull(
            "Metadata document should not be replaced by root references",
            metadata.document.paths.getValue("/users").`$ref`,
        )
    }

    fun testExportMultiDocumentYamlIncludesSharedSchemas() = runTest {
        val responseBody = ObjectModel.Object(
            linkedMapOf(
                "id" to FieldModel(model = ObjectModel.single("long"), required = true),
            )
        )
        val result = channel.export(
            exportContext(
                listOf(
                    httpEndpoint(
                        name = "Get user",
                        path = "/users/{id}",
                        method = HttpMethod.GET,
                        methodName = "getUser",
                        className = "com.example.UserController",
                        responseBody = responseBody,
                        responseType = "com.example.User",
                    ),
                ),
                OpenApiOutputFormat.YAML,
            )
        )

        assertTrue("Multi-document YAML schema export should succeed, got: $result", result is ExportResult.Success)
        val metadata = (result as ExportResult.Success).metadata as OpenApiExportMetadata
        assertTrue(
            "Shared schemas document should be serialized",
            metadata.additionalFiles.containsKey("schemas/schemas.yaml"),
        )
        assertEquals("One named schema should be counted", 1, metadata.schemaCount)
        assertTrue(
            "Controller fragment should reference the shared schemas file",
            metadata.additionalFiles.getValue("paths/UserController.yaml")
                .contains("../schemas/schemas.yaml#/components/schemas/User"),
        )
    }

    fun testExportMultiDocumentJsonUsesJsonNamesAndReferences() = runTest {
        val responseBody = ObjectModel.Object(
            linkedMapOf("name" to FieldModel(model = ObjectModel.single("string")))
        )
        val result = channel.export(
            exportContext(
                listOf(
                    httpEndpoint(
                        name = "Get user",
                        path = "/users",
                        method = HttpMethod.GET,
                        methodName = "getUser",
                        className = "com.example.UserController",
                        responseBody = responseBody,
                        responseType = "com.example.User",
                    ),
                ),
                OpenApiOutputFormat.JSON,
            )
        )

        assertTrue("Multi-document JSON export should succeed, got: $result", result is ExportResult.Success)
        val metadata = (result as ExportResult.Success).metadata as OpenApiExportMetadata
        assertTrue(
            "Every additional file should use the JSON extension: ${metadata.additionalFiles.keys}",
            metadata.additionalFiles.keys.all { it.endsWith(".json") },
        )
        val serializedOutput = buildString {
            append(metadata.content)
            metadata.additionalFiles.values.forEach(::append)
        }
        assertTrue(
            "JSON references should point to JSON documents",
            serializedOutput.contains("./paths/UserController.json#/paths/~1users") &&
                serializedOutput.contains("./schemas/schemas.json#/components/schemas/User") &&
                serializedOutput.contains("../schemas/schemas.json#/components/schemas/User"),
        )
        assertFalse("JSON output should not contain YAML document references", serializedOutput.contains(".yaml"))
    }

    fun testExportMultiDocumentRejectsCrossControllerNormalizedPathConflict() = runTest {
        val result = channel.export(
            exportContext(
                listOf(
                    httpEndpoint(
                        path = "/users/:id",
                        method = HttpMethod.GET,
                        methodName = "getUser",
                        className = "com.example.UserController",
                    ),
                    httpEndpoint(
                        path = "/users/{id}",
                        method = HttpMethod.POST,
                        methodName = "updateUser",
                        className = "com.example.AdminController",
                    ),
                ),
                OpenApiOutputFormat.YAML,
            )
        )

        assertTrue("Conflicting ownership should return Error, got: $result", result is ExportResult.Error)
        val message = (result as ExportResult.Error).message
        assertTrue("Error should include normalized path: $message", message.contains("/users/{id}"))
        assertTrue("Error should include GET: $message", message.contains("GET"))
        assertTrue("Error should include POST: $message", message.contains("POST"))
        assertTrue("Error should include UserController: $message", message.contains("UserController"))
        assertTrue("Error should include AdminController: $message", message.contains("AdminController"))
    }

    fun testExportMultiDocumentRunsHookOnceAndPlacesRenamedPathInUnresolved() = runTest {
        project.registerServiceInstance(
            serviceInterface = ConfigReader::class.java,
            instance = TestConfigReader.fromRules(
                project,
                "openapi.format.after" to
                    """groovy:if (document.paths.containsKey("/users")) { def item = document.paths.remove("/users"); document.paths.put("/hooked", item) } else { document.paths.put("/hook-fired-twice", document.paths.get("/hooked")) }""",
            ),
        )
        val result = channel.export(
            exportContext(
                listOf(
                    httpEndpoint(
                        path = "/users",
                        method = HttpMethod.GET,
                        methodName = "listUsers",
                        className = "com.example.UserController",
                    ),
                ),
                OpenApiOutputFormat.YAML,
            )
        )

        assertTrue("Hooked multi-document export should succeed, got: $result", result is ExportResult.Success)
        val metadata = (result as ExportResult.Success).metadata as OpenApiExportMetadata
        assertEquals(1, metadata.unresolvedPathCount)
        assertTrue(
            "Renamed path should be emitted in Unresolved fragment",
            metadata.additionalFiles.containsKey("paths/Unresolved.yaml"),
        )
        assertTrue(
            "Warning should identify the hook-renamed path: ${metadata.warnings}",
            metadata.warnings.any { it.contains("/hooked") },
        )
        assertEquals(
            "Hook should run exactly once",
            setOf("/hooked"),
            metadata.document.paths.keys,
        )
        val fullPathItem = metadata.document.paths.getValue("/hooked")
        assertNotNull("Metadata document should retain the complete GET operation", fullPathItem.get)
        assertNull("Metadata document should not contain split root refs", fullPathItem.`$ref`)
        assertTrue(
            "Root document should reference Unresolved fragment",
            metadata.content.contains("./paths/Unresolved.yaml#/paths/~1hooked"),
        )
    }

    // ─── Settings fallback for outputFormat ───────────────────────

    fun testExportUsesSettingsOutputFormatWhenNoTypedConfig() = runTest {
        // Quick-export path: caller passes ChannelConfig.Empty (no options
        // panel shown). The channel must fall back to the persistent
        // OpenApiSettings.outputFormat.
        SettingBinder.getInstance(project).update(OpenApiSettings::class) {
            outputFormat = "YAML"
        }
        try {
            val endpoint = httpEndpoint(
                name = "Get user",
                path = "/users/{id}",
                method = HttpMethod.GET,
                methodName = "getUser",
            )
            val context = ExportContext(
                project = project,
                endpoints = listOf(endpoint),
                channelId = "openapi",
                channelConfig = ChannelConfig.Empty,
            )
            val result = channel.export(context)
            assertTrue(result is ExportResult.Success)
            val metadata = (result as ExportResult.Success).metadata as OpenApiExportMetadata
            assertEquals(
                "Settings outputFormat=YAML should be used when no typed config is provided",
                OpenApiOutputFormat.YAML,
                metadata.outputFormat,
            )
            assertFalse(
                "Content should be YAML, not JSON",
                metadata.content.trimStart().startsWith("{"),
            )
        } finally {
            SettingBinder.getInstance(project).update(OpenApiSettings::class) {
                outputFormat = "JSON"
            }
        }
    }

    fun testExportTypedConfigOverridesSettingsOutputFormat() = runTest {
        // Options-panel path: typed config from buildConfig() takes precedence
        // over the persistent settings value.
        SettingBinder.getInstance(project).update(OpenApiSettings::class) {
            outputFormat = "YAML"
        }
        try {
            val endpoint = httpEndpoint(
                name = "Get user",
                path = "/users/{id}",
                method = HttpMethod.GET,
                methodName = "getUser",
            )
            val context = ExportContext(
                project = project,
                endpoints = listOf(endpoint),
                channelId = "openapi",
                channelConfig = OpenApiConfig(outputFormat = OpenApiOutputFormat.JSON),
            )
            val result = channel.export(context)
            assertTrue(result is ExportResult.Success)
            val metadata = (result as ExportResult.Success).metadata as OpenApiExportMetadata
            assertEquals(
                "Typed config JSON should override settings YAML",
                OpenApiOutputFormat.JSON,
                metadata.outputFormat,
            )
        } finally {
            SettingBinder.getInstance(project).update(OpenApiSettings::class) {
                outputFormat = "JSON"
            }
        }
    }

    fun testExportFallsBackToJsonForUnrecognizedSettingsOutputFormat() = runTest {
        SettingBinder.getInstance(project).update(OpenApiSettings::class) {
            outputFormat = "BINARY"
        }
        try {
            val endpoint = httpEndpoint(
                name = "Get user",
                path = "/users/{id}",
                method = HttpMethod.GET,
                methodName = "getUser",
            )
            val context = ExportContext(
                project = project,
                endpoints = listOf(endpoint),
                channelId = "openapi",
                channelConfig = ChannelConfig.Empty,
            )
            val result = channel.export(context)
            assertTrue(result is ExportResult.Success)
            val metadata = (result as ExportResult.Success).metadata as OpenApiExportMetadata
            assertEquals(
                "Unrecognized format should fall back to JSON",
                OpenApiOutputFormat.JSON,
                metadata.outputFormat,
            )
        } finally {
            SettingBinder.getInstance(project).update(OpenApiSettings::class) {
                outputFormat = "JSON"
            }
        }
    }

    // ─── isAvailableFor ───────────────────────────────────────────

    fun testIsAvailableForEmptyEndpointsReturnsTrue() {
        // Channel.isAvailableFor returns true for empty input by SPI contract.
        assertTrue(channel.isAvailableFor(emptyList()))
    }

    fun testIsAvailableForHttpEndpointsReturnsTrue() {
        val http = httpEndpoint(path = "/users", method = HttpMethod.GET, methodName = "listUsers")
        assertTrue(channel.isAvailableFor(listOf(http)))
    }

    fun testIsAvailableForGrpcOnlyEndpointsReturnsFalse() {
        val grpc = grpcEndpoint()
        assertFalse(channel.isAvailableFor(listOf(grpc)))
    }

    fun testIsAvailableForMixedEndpointsReturnsTrue() {
        val http = httpEndpoint(path = "/users", method = HttpMethod.GET, methodName = "listUsers")
        val grpc = grpcEndpoint()
        assertTrue(channel.isAvailableFor(listOf(http, grpc)))
    }

    // ─── handleResult ───────────────────────────────────────

    fun testHandleResultWritesFileAndReturnsTrue() = runTest {
        // Use a temp directory + FileConfig so no save dialog is shown.
        val tempDir = createTempDir(prefix = "openapi-test").apply { mkdirs() }
        try {
            val endpoint = httpEndpoint(
                name = "Get user",
                path = "/users/{id}",
                method = HttpMethod.GET,
                methodName = "getUser",
            )
            val context = ExportContext(
                project = project,
                endpoints = listOf(endpoint),
                channelId = "openapi",
            )
            val exportResult = channel.export(context) as ExportResult.Success

            val fileConfig = ChannelConfig.FileConfig(
                outputDir = tempDir.absolutePath,
                fileName = "openapi",
            )
            val handled = channel.handleResult(project, exportResult, fileConfig)

            assertTrue("handleResult should return true (suppress default toast)", handled)
            val expectedFile = File(tempDir, "openapi.json")
            assertTrue("File ${expectedFile.absolutePath} should exist after handleResult", expectedFile.exists())
            val content = expectedFile.readText()
            assertTrue("Written file should contain openapi 3.0.3", content.contains("\"openapi\": \"3.0.3\""))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    fun testHandleResultReturnsFalseForForeignMetadata() = runTest {
        // When the metadata is not OpenApiExportMetadata, handleResult should
        // return false so the orchestrator can fall back to default handling.
        val foreignMetadata = object : com.itangcent.easyapi.core.export.ExportMetadata {
            override fun formatDisplay(): String? = "Foreign"
        }
        val success = ExportResult.Success(
            count = 1,
            target = "Foreign",
            metadata = foreignMetadata,
        )
        val handled = channel.handleResult(project, success, ChannelConfig.Empty)
        assertFalse("handleResult should return false for foreign metadata", handled)
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private fun httpEndpoint(
        name: String? = null,
        path: String,
        method: HttpMethod,
        methodName: String,
        className: String? = null,
        folder: String? = null,
        responseBody: ObjectModel? = null,
        responseType: String? = null,
    ): ApiEndpoint {
        val psiMethod = mock<PsiMethod> {
            on { this.name } doReturn methodName
        }
        return ApiEndpoint(
            name = name,
            folder = folder,
            metadata = httpMetadata(
                path = path,
                method = method,
                responseBody = responseBody,
                responseType = responseType,
            ),
            sourceMethod = psiMethod,
            className = className,
        )
    }

    private fun exportContext(
        endpoints: List<ApiEndpoint>,
        outputFormat: OpenApiOutputFormat,
    ): ExportContext = ExportContext(
        project = project,
        endpoints = endpoints,
        channelId = "openapi",
        channelConfig = OpenApiConfig(
            outputFormat = outputFormat,
            documentMode = OpenApiDocumentMode.MULTI_FILE_BY_CONTROLLER,
        ),
    )

    private fun grpcEndpoint(): ApiEndpoint = ApiEndpoint(
        name = "SayHello",
        metadata = GrpcMetadata(
            path = "/helloworld.Greeter/SayHello",
            serviceName = "Greeter",
            methodName = "SayHello",
            packageName = "helloworld",
            streamingType = GrpcStreamingType.UNARY,
        ),
    )
}
