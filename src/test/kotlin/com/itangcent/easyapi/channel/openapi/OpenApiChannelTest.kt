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
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.psi.PsiMethod
import com.intellij.testFramework.registerServiceInstance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

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

    fun testHandleResultWritesMultiDocumentYamlFiles() = runTest {
        val tempDir = Files.createTempDirectory("openapi-multi-yaml")
        try {
            val result = multiDocumentResult(
                format = OpenApiOutputFormat.YAML,
                content = "paths:\n  /users:\n    \$ref: './paths/UserController.yaml#/paths/~1users'\n",
                additionalFiles = linkedMapOf(
                    "paths/UserController.yaml" to
                        "paths:\n  /users:\n    get:\n      responses: {}\n",
                    "schemas/schemas.yaml" to
                        "components:\n  schemas:\n    User:\n      type: object\n",
                ),
                pathFragmentCount = 1,
                schemaCount = 1,
            )

            assertTrue(
                "Multi-document YAML result should be handled",
                channel.handleResult(
                    project,
                    result,
                    ChannelConfig.FileConfig(tempDir.toString(), "ignored.yaml"),
                ),
            )

            val root = tempDir.resolve("openapi.yaml")
            val pathFragment = tempDir.resolve("paths/UserController.yaml")
            val schemas = tempDir.resolve("schemas/schemas.yaml")
            assertTrue("Root openapi.yaml should be written", Files.exists(root))
            assertTrue("Controller path fragment should be written", Files.exists(pathFragment))
            assertTrue("Optional schema document should be written", Files.exists(schemas))
            assertTrue(
                "Root should retain its fragment reference",
                Files.readString(root).contains("./paths/UserController.yaml#/paths/~1users"),
            )
            assertTrue(
                "No temporary files should remain",
                Files.walk(tempDir).use { files ->
                    files.noneMatch { it.fileName.toString().endsWith(".tmp") }
                },
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    fun testHandleResultWritesMultiDocumentJsonRootName() = runTest {
        val tempDir = Files.createTempDirectory("openapi-multi-json")
        try {
            channel.handleResult(
                project,
                multiDocumentResult(
                    format = OpenApiOutputFormat.JSON,
                    content = """{"openapi":"3.0.3"}""",
                    additionalFiles = linkedMapOf(
                        "paths/UserController.json" to """{"paths":{}}""",
                    ),
                    pathFragmentCount = 1,
                ),
                ChannelConfig.FileConfig(tempDir.toString(), "ignored-name.yaml"),
            )

            assertTrue("JSON multi-document root should use fixed name", Files.exists(tempDir.resolve("openapi.json")))
            assertFalse(
                "FileConfig.fileName must be ignored in multi-document mode",
                Files.exists(tempDir.resolve("ignored-name.yaml")),
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    fun testHandleResultConfirmsExistingTargetsOnceThenOverwritesAll() = runTest {
        val tempDir = Files.createTempDirectory("openapi-multi-overwrite")
        val prompts = mutableListOf<String>()
        try {
            val root = tempDir.resolve("openapi.yaml")
            val fragment = tempDir.resolve("paths/UserController.yaml")
            Files.createDirectories(fragment.parent)
            Files.writeString(root, "old root")
            Files.writeString(fragment, "old fragment")
            TestDialogManager.setTestDialog(TestDialog { message ->
                prompts += message
                Messages.YES
            })

            channel.handleResult(
                project,
                multiDocumentResult(
                    format = OpenApiOutputFormat.YAML,
                    content = "new root",
                    additionalFiles = linkedMapOf("paths/UserController.yaml" to "new fragment"),
                    pathFragmentCount = 1,
                ),
                ChannelConfig.FileConfig(tempDir.toString()),
            )

            assertEquals("Root should be replaced", "new root", Files.readString(root))
            assertEquals("Fragment should be replaced", "new fragment", Files.readString(fragment))
            assertEquals(
                "Existing targets should trigger exactly one overwrite prompt",
                1,
                prompts.count { it.contains("existing OpenAPI", ignoreCase = true) },
            )
            assertTrue(
                "Overwrite prompt should report both existing targets: $prompts",
                prompts.any { it.contains("2") },
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    fun testConcurrentMultiDocumentExportsToSameDirectoryStayConsistent() = runTest {
        val tempDir = Files.createTempDirectory("openapi-multi-concurrent")
        val prompts = java.util.Collections.synchronizedList(mutableListOf<String>())
        try {
            Files.writeString(tempDir.resolve("openapi.yaml"), "existing root")
            TestDialogManager.setTestDialog(TestDialog { message ->
                prompts += message
                Messages.YES
            })
            val first = multiDocumentResult(
                format = OpenApiOutputFormat.YAML,
                content = "batch-a root",
                additionalFiles = linkedMapOf("paths/UserController.yaml" to "batch-a fragment"),
                pathFragmentCount = 1,
            )
            val second = multiDocumentResult(
                format = OpenApiOutputFormat.YAML,
                content = "batch-b root",
                additionalFiles = linkedMapOf("paths/UserController.yaml" to "batch-b fragment"),
                pathFragmentCount = 1,
            )

            withTimeout(20_000) {
                coroutineScope {
                    listOf(first, second).map { result ->
                        async(Dispatchers.Default) {
                            channel.handleResult(
                                project,
                                result,
                                ChannelConfig.FileConfig(tempDir.toString()),
                            )
                        }
                    }.awaitAll()
                }
            }

            val rootBatch = Files.readString(tempDir.resolve("openapi.yaml")).substringBefore(' ')
            val fragmentBatch = Files.readString(tempDir.resolve("paths/UserController.yaml")).substringBefore(' ')
            assertEquals("Root and referenced fragment must come from the same export batch", rootBatch, fragmentBatch)
            val overwritePrompts = prompts.filter { it.contains("existing OpenAPI", ignoreCase = true) }
            assertEquals("Each batch should confirm against the state it actually observed", 2, overwritePrompts.size)
            assertTrue(
                "The waiting export should recount both files after the first export: $overwritePrompts",
                overwritePrompts.any { it.contains("2") },
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    fun testConcurrentMultiDocumentExportsToDifferentDirectoriesComplete() = runTest {
        val firstDir = Files.createTempDirectory("openapi-multi-concurrent-a")
        val secondDir = Files.createTempDirectory("openapi-multi-concurrent-b")
        try {
            withTimeout(20_000) {
                coroutineScope {
                    listOf(firstDir to "a", secondDir to "b").map { (directory, marker) ->
                        async(Dispatchers.Default) {
                            channel.handleResult(
                                project,
                                multiDocumentResult(
                                    format = OpenApiOutputFormat.YAML,
                                    content = "$marker root",
                                    additionalFiles = linkedMapOf(
                                        "paths/UserController.yaml" to "$marker fragment",
                                    ),
                                    pathFragmentCount = 1,
                                ),
                                ChannelConfig.FileConfig(directory.toString()),
                            )
                        }
                    }.awaitAll()
                }
            }

            assertEquals("First directory should receive its root", "a root", Files.readString(firstDir.resolve("openapi.yaml")))
            assertEquals("Second directory should receive its root", "b root", Files.readString(secondDir.resolve("openapi.yaml")))
        } finally {
            firstDir.toFile().deleteRecursively()
            secondDir.toFile().deleteRecursively()
        }
    }

    fun testMultiDocumentDirectoryLockSerializesSameDirectory() = runTest {
        val directory = Files.createTempDirectory("openapi-lock-same")
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        try {
            coroutineScope {
                val first = async {
                    channel.withMultiDocumentDirectoryLock(directory) {
                        firstEntered.complete(Unit)
                        releaseFirst.await()
                    }
                }
                withTimeout(5_000) { firstEntered.await() }
                val second = async {
                    secondStarted.complete(Unit)
                    channel.withMultiDocumentDirectoryLock(directory) {
                        secondEntered.complete(Unit)
                    }
                }
                withTimeout(5_000) { secondStarted.await() }

                try {
                    assertNull(
                        "Second block must not enter while the same directory lock is held",
                        withTimeoutOrNull(500) { secondEntered.await() },
                    )
                } finally {
                    releaseFirst.complete(Unit)
                }
                withTimeout(5_000) {
                    secondEntered.await()
                    awaitAll(first, second)
                }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    fun testMultiDocumentDirectoryLockDoesNotBlockDifferentDirectory() = runTest {
        val firstDirectory = Files.createTempDirectory("openapi-lock-first")
        val secondDirectory = Files.createTempDirectory("openapi-lock-second")
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        try {
            coroutineScope {
                val first = async {
                    channel.withMultiDocumentDirectoryLock(firstDirectory) {
                        firstEntered.complete(Unit)
                        releaseFirst.await()
                    }
                }
                withTimeout(5_000) { firstEntered.await() }

                val secondCompleted = try {
                    withTimeoutOrNull(2_000) {
                        channel.withMultiDocumentDirectoryLock(secondDirectory) { true }
                    }
                } finally {
                    releaseFirst.complete(Unit)
                }
                assertEquals(
                    "A different directory must complete before the first directory lock is released",
                    true,
                    secondCompleted,
                )
                withTimeout(5_000) { first.await() }
            }
        } finally {
            firstDirectory.toFile().deleteRecursively()
            secondDirectory.toFile().deleteRecursively()
        }
    }

    fun testMultiDocumentDirectoryLockSerializesSymlinkAlias() = runTest {
        val parent = Files.createTempDirectory("openapi-lock-symlink")
        val realDirectory = Files.createDirectory(parent.resolve("real"))
        val alias = parent.resolve("alias")
        try {
            try {
                Files.createSymbolicLink(alias, realDirectory)
            } catch (e: Exception) {
                if (e is UnsupportedOperationException || e is IOException || e is SecurityException) {
                    Assume.assumeNoException(e)
                }
                throw e
            }
            val firstEntered = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val aliasStarted = CompletableDeferred<Unit>()
            val aliasEntered = CompletableDeferred<Unit>()

            coroutineScope {
                val first = async {
                    channel.withMultiDocumentDirectoryLock(realDirectory) {
                        firstEntered.complete(Unit)
                        releaseFirst.await()
                    }
                }
                withTimeout(5_000) { firstEntered.await() }
                val second = async {
                    aliasStarted.complete(Unit)
                    channel.withMultiDocumentDirectoryLock(alias) {
                        aliasEntered.complete(Unit)
                    }
                }
                withTimeout(5_000) { aliasStarted.await() }

                try {
                    assertNull(
                        "A symlink alias must share the canonical directory lock",
                        withTimeoutOrNull(500) { aliasEntered.await() },
                    )
                } finally {
                    releaseFirst.complete(Unit)
                }
                withTimeout(5_000) {
                    aliasEntered.await()
                    awaitAll(first, second)
                }
            }
        } finally {
            Files.deleteIfExists(alias)
            parent.toFile().deleteRecursively()
        }
    }

    fun testHandleResultDeclinedOverwriteLeavesAllTargetsUntouched() = runTest {
        val tempDir = Files.createTempDirectory("openapi-multi-decline")
        try {
            val root = tempDir.resolve("openapi.yaml")
            val fragment = tempDir.resolve("paths/UserController.yaml")
            val newSchema = tempDir.resolve("schemas/schemas.yaml")
            Files.createDirectories(fragment.parent)
            Files.writeString(root, "old root")
            Files.writeString(fragment, "old fragment")
            TestDialogManager.setTestDialog(TestDialog { Messages.NO })

            var cancelled = false
            try {
                channel.handleResult(
                    project,
                    multiDocumentResult(
                        format = OpenApiOutputFormat.YAML,
                        content = "new root",
                        additionalFiles = linkedMapOf(
                            "paths/UserController.yaml" to "new fragment",
                            "schemas/schemas.yaml" to "new schema",
                        ),
                        pathFragmentCount = 1,
                        schemaCount = 1,
                    ),
                    ChannelConfig.FileConfig(tempDir.toString()),
                )
            } catch (_: CancellationException) {
                cancelled = true
            }

            assertTrue("Declining overwrite should cancel the export", cancelled)
            assertEquals("Existing root must stay untouched", "old root", Files.readString(root))
            assertEquals("Existing fragment must stay untouched", "old fragment", Files.readString(fragment))
            assertFalse("Missing schema target must not be created", Files.exists(newSchema))
            assertFalse("Missing schema directory must not be created", Files.exists(newSchema.parent))
            assertTrue(
                "Declining overwrite must not leave temporary files",
                Files.walk(tempDir).use { files ->
                    files.noneMatch { it.fileName.toString().endsWith(".tmp") }
                },
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    fun testHandleResultOnlyCountsCurrentTargetsAndKeepsStaleFiles() = runTest {
        val tempDir = Files.createTempDirectory("openapi-multi-stale")
        val prompts = mutableListOf<String>()
        try {
            val root = tempDir.resolve("openapi.yaml")
            val stale = tempDir.resolve("paths/OldController.yaml")
            Files.createDirectories(stale.parent)
            Files.writeString(root, "old root")
            Files.writeString(stale, "stale content")
            TestDialogManager.setTestDialog(TestDialog { message ->
                prompts += message
                Messages.YES
            })

            channel.handleResult(
                project,
                multiDocumentResult(
                    format = OpenApiOutputFormat.YAML,
                    content = "new root",
                    additionalFiles = linkedMapOf("paths/UserController.yaml" to "new fragment"),
                    pathFragmentCount = 1,
                ),
                ChannelConfig.FileConfig(tempDir.toString()),
            )

            val overwritePrompts = prompts.filter {
                it.contains("existing OpenAPI", ignoreCase = true)
            }
            assertEquals("Stale files must not add overwrite prompts", 1, overwritePrompts.size)
            assertTrue(
                "Only the existing root target should be counted: $prompts",
                overwritePrompts.single().contains("1"),
            )
            assertEquals("Stale files must not be deleted or changed", "stale content", Files.readString(stale))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    fun testHandleResultRejectsPathTraversalBeforeWriting() = runTest {
        val tempDir = Files.createTempDirectory("openapi-multi-traversal")
        val escaped = tempDir.parent.resolve("escape-${tempDir.fileName}.yaml")
        try {
            var rejected = false
            try {
                channel.handleResult(
                    project,
                    multiDocumentResult(
                        format = OpenApiOutputFormat.YAML,
                        content = "root",
                        additionalFiles = linkedMapOf("../${escaped.fileName}" to "escaped"),
                    ),
                    ChannelConfig.FileConfig(tempDir.toString()),
                )
            } catch (_: IllegalArgumentException) {
                rejected = true
            }

            assertTrue("Parent-directory traversal should be rejected", rejected)
            assertFalse("No root file should be written after rejection", Files.exists(tempDir.resolve("openapi.yaml")))
            assertFalse("No file should be written outside the root", Files.exists(escaped))
        } finally {
            Files.deleteIfExists(escaped)
            tempDir.toFile().deleteRecursively()
        }
    }

    fun testHandleResultRejectsNormalizedDuplicateTargetsBeforeWriting() = runTest {
        val tempDir = Files.createTempDirectory("openapi-multi-duplicate")
        try {
            assertMultiDocumentTargetsRejected(
                tempDir,
                linkedMapOf(
                    "paths/A.yaml" to "first",
                    "paths/../paths/A.yaml" to "second",
                ),
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    fun testHandleResultRejectsAdditionalFileThatConflictsWithRoot() = runTest {
        val tempDir = Files.createTempDirectory("openapi-multi-root-conflict")
        try {
            assertMultiDocumentTargetsRejected(
                tempDir,
                linkedMapOf("openapi.yaml" to "not the root document"),
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    fun testHandleResultRejectsAbsoluteAdditionalFilePath() = runTest {
        val tempDir = Files.createTempDirectory("openapi-multi-absolute")
        try {
            assertMultiDocumentTargetsRejected(
                tempDir,
                linkedMapOf(tempDir.resolve("paths/A.yaml").toString() to "absolute"),
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    fun testHandleResultRejectsWindowsBackslashTraversalBeforeWriting() = runTest {
        if (File.separatorChar != '\\') return@runTest
        val tempDir = Files.createTempDirectory("openapi-multi-backslash")
        val escaped = tempDir.parent.resolve("escape-${tempDir.fileName}.yaml")
        try {
            assertMultiDocumentTargetsRejected(
                tempDir,
                linkedMapOf("..\\${escaped.fileName}" to "escaped"),
            )
            assertFalse("Backslash traversal must not write outside the root", Files.exists(escaped))
        } finally {
            Files.deleteIfExists(escaped)
            tempDir.toFile().deleteRecursively()
        }
    }

    fun testHandleResultRejectsSymlinkedChildDirectoryBeforeWriting() = runTest {
        val root = Files.createTempDirectory("openapi-multi-symlink-root")
        val outside = Files.createTempDirectory("openapi-multi-symlink-outside")
        val pathsLink = root.resolve("paths")
        try {
            try {
                Files.createSymbolicLink(pathsLink, outside)
            } catch (e: Exception) {
                if (e is UnsupportedOperationException || e is IOException || e is SecurityException) {
                    Assume.assumeNoException(e)
                }
                throw e
            }

            var rejected = false
            try {
                channel.handleResult(
                    project,
                    multiDocumentResult(
                        format = OpenApiOutputFormat.YAML,
                        content = "root",
                        additionalFiles = linkedMapOf("paths/UserController.yaml" to "fragment"),
                    ),
                    ChannelConfig.FileConfig(root.toString()),
                )
            } catch (_: IllegalArgumentException) {
                rejected = true
            }

            assertTrue("A child symlink outside the selected root should be rejected", rejected)
            assertFalse(
                "Symlink target must not receive the controller fragment",
                Files.exists(outside.resolve("UserController.yaml")),
            )
            assertFalse("Root document must not be published", Files.exists(root.resolve("openapi.yaml")))
        } finally {
            Files.deleteIfExists(pathsLink)
            root.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }

    fun testHandleResultFragmentWriteFailureKeepsRootAndCleansTemporaryFile() = runTest {
        val tempDir = Files.createTempDirectory("openapi-multi-write-failure")
        try {
            val root = tempDir.resolve("openapi.yaml")
            val fragmentTarget = tempDir.resolve("paths/UserController.yaml")
            Files.createDirectories(fragmentTarget)
            Files.writeString(fragmentTarget.resolve("keep.txt"), "prevents directory replacement")
            Files.writeString(root, "old root")
            TestDialogManager.setTestDialog(TestDialog { Messages.YES })

            var failure: IllegalStateException? = null
            try {
                channel.handleResult(
                    project,
                    multiDocumentResult(
                        format = OpenApiOutputFormat.YAML,
                        content = "new root",
                        additionalFiles = linkedMapOf("paths/UserController.yaml" to "new fragment"),
                    ),
                    ChannelConfig.FileConfig(tempDir.toString()),
                )
            } catch (e: IllegalStateException) {
                failure = e
            }

            assertNotNull("Fragment replacement should fail", failure)
            assertTrue(
                "Failure should identify the exact fragment target: ${failure?.message}",
                failure?.message?.contains(fragmentTarget.toString()) == true,
            )
            assertEquals("Root must be written last and remain unchanged", "old root", Files.readString(root))
            assertTrue(
                "Failed write must not leave temporary files",
                Files.walk(tempDir).use { files ->
                    files.noneMatch { it.fileName.toString().endsWith(".tmp") }
                },
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    fun testHandleResultShowsMultiDocumentWarnings() = runTest {
        val tempDir = Files.createTempDirectory("openapi-multi-warning")
        val dialogs = mutableListOf<String>()
        try {
            TestDialogManager.setTestDialog(TestDialog { message ->
                dialogs += message
                Messages.YES
            })

            channel.handleResult(
                project,
                multiDocumentResult(
                    format = OpenApiOutputFormat.YAML,
                    content = "root",
                    additionalFiles = linkedMapOf("paths/Unresolved.yaml" to "unresolved"),
                    pathFragmentCount = 1,
                    unresolvedPathCount = 1,
                    warnings = listOf("Path /hooked has no endpoint owner"),
                ),
                ChannelConfig.FileConfig(tempDir.toString()),
            )

            assertTrue(
                "Success dialog should include split counts and warning text: $dialogs",
                dialogs.any {
                    it.contains("Path fragments: 1") &&
                        it.contains("Schemas: 0") &&
                        it.contains("Unresolved paths: 1") &&
                        it.contains("Path /hooked has no endpoint owner")
                },
            )
        } finally {
            tempDir.toFile().deleteRecursively()
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

    private fun multiDocumentResult(
        format: OpenApiOutputFormat,
        content: String,
        additionalFiles: LinkedHashMap<String, String>,
        pathFragmentCount: Int = 0,
        schemaCount: Int = 0,
        unresolvedPathCount: Int = 0,
        warnings: List<String> = emptyList(),
    ): ExportResult.Success = ExportResult.Success(
        count = 2,
        target = "OpenAPI",
        metadata = OpenApiExportMetadata(
            document = OpenApiDocument(
                info = InfoObject(title = "Test", version = "1.0.0"),
                paths = linkedMapOf(),
            ),
            outputFormat = format,
            content = content,
            documentMode = OpenApiDocumentMode.MULTI_FILE_BY_CONTROLLER,
            additionalFiles = additionalFiles,
            pathFragmentCount = pathFragmentCount,
            schemaCount = schemaCount,
            unresolvedPathCount = unresolvedPathCount,
            warnings = warnings,
        ),
    )

    private suspend fun assertMultiDocumentTargetsRejected(
        tempDir: Path,
        additionalFiles: LinkedHashMap<String, String>,
    ) {
        var rejected = false
        try {
            channel.handleResult(
                project,
                multiDocumentResult(
                    format = OpenApiOutputFormat.YAML,
                    content = "root",
                    additionalFiles = additionalFiles,
                ),
                ChannelConfig.FileConfig(tempDir.toString()),
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue("Unsafe output targets should be rejected: ${additionalFiles.keys}", rejected)
        assertFalse("Root must not be published after target rejection", Files.exists(tempDir.resolve("openapi.yaml")))
        assertTrue(
            "Target rejection must happen before writing any file",
            Files.walk(tempDir).use { files -> files.noneMatch(Files::isRegularFile) },
        )
    }

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
