package com.itangcent.easyapi.channel.openapi.multidocument

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.itangcent.easyapi.channel.openapi.ComponentsObject
import com.itangcent.easyapi.channel.openapi.InfoObject
import com.itangcent.easyapi.channel.openapi.MediaTypeObject
import com.itangcent.easyapi.channel.openapi.OpenApiConfig
import com.itangcent.easyapi.channel.openapi.OpenApiDocument
import com.itangcent.easyapi.channel.openapi.OpenApiExportMetadata
import com.itangcent.easyapi.channel.openapi.OpenApiOptionsPanel
import com.itangcent.easyapi.channel.openapi.OpenApiOutputFormat
import com.itangcent.easyapi.channel.openapi.OperationObject
import com.itangcent.easyapi.channel.openapi.PathItemObject
import com.itangcent.easyapi.channel.openapi.ResponseObject
import com.itangcent.easyapi.channel.openapi.SchemaObject
import com.itangcent.easyapi.channel.spi.Channel
import com.itangcent.easyapi.channel.spi.ChannelConfig
import com.itangcent.easyapi.core.export.ApiEndpoint
import com.itangcent.easyapi.core.export.ExportContext
import com.itangcent.easyapi.core.export.ExportMetadata
import com.itangcent.easyapi.core.export.ExportResult
import com.itangcent.easyapi.core.export.HttpMethod
import com.itangcent.easyapi.core.export.httpMetadata
import com.itangcent.easyapi.testFramework.EasyApiLightCodeInsightFixtureTestCase
import java.nio.file.Files

class OpenApiMultiDocumentChannelTest : EasyApiLightCodeInsightFixtureTestCase() {

    fun testDeclaresIndependentBetaChannelContract() {
        val channel = OpenApiMultiDocumentChannel()
        val optionsPanel = channel.createOptionsPanel(project)

        assertEquals("Channel id should be independent from baseline OpenAPI", "openapi-multi", channel.id)
        assertEquals("Display name should identify the beta multi-document channel", "OpenAPI Multi-Document (Beta)", channel.displayName)
        assertTrue("Channel should support HTTP endpoints", channel.supportsHttp)
        assertFalse("Channel should reject gRPC endpoints", channel.supportsGrpc)
        assertTrue("Channel should be exposed as an action", channel.exposeAsAction)
        assertEquals("Action text should identify multi-document export", "Export to OpenAPI Multi-Document", channel.actionText)
        assertFalse("Channel should be disabled by default", channel.enabledByDefault)
        assertTrue("Channel should be marked beta", channel.beta)
        assertNull("Channel should reuse OpenAPI settings instead of owning settings", channel.settingsType)
        assertTrue("Channel should reuse the OpenAPI options panel", optionsPanel is OpenApiOptionsPanel)
        assertTrue("Options should keep producing OpenApiConfig", optionsPanel.buildConfig() is OpenApiConfig)
        assertNull("Channel should not contribute a settings panel", channel.createSettingsPanel(project))
        assertTrue("Channel should not contribute config files", channel.configFiles().isEmpty())
        assertTrue("Channel should not contribute rule keys", channel.ruleKeys().isEmpty())
    }

    fun testDelegatesOnceWithOnlyChannelIdChanged() = runTest {
        val endpoints = endpoints()
        val selectedEndpoints = listOf(endpoints.first())
        val sourceClasses = emptyList<com.intellij.psi.PsiClass>()
        val config = OpenApiConfig(outputFormat = OpenApiOutputFormat.JSON)
        val indicator = EmptyProgressIndicator()
        val delegate = RecordingChannel(successResult(OpenApiOutputFormat.JSON))
        val context = ExportContext(
            project = project,
            endpoints = endpoints,
            selectedEndpoints = selectedEndpoints,
            sourceClasses = sourceClasses,
            channelId = "openapi-multi",
            channelConfig = config,
            indicator = indicator,
        )

        OpenApiMultiDocumentChannel(delegate).export(context)

        assertEquals("Delegate should be called exactly once", 1, delegate.callCount)
        val delegated = delegate.lastContext ?: throw AssertionError("Delegate should receive a context")
        assertEquals("Delegate channel id should be baseline OpenAPI", "openapi", delegated.channelId)
        assertSame("Project should be preserved", project, delegated.project)
        assertSame("Endpoints should be preserved", endpoints, delegated.endpoints)
        assertSame("Selected endpoints should be preserved", selectedEndpoints, delegated.selectedEndpoints)
        assertSame("Source classes should be preserved", sourceClasses, delegated.sourceClasses)
        assertSame("Channel config should be preserved", config, delegated.channelConfig)
        assertSame("Progress indicator should be preserved", indicator, delegated.indicator)
    }

    fun testBuildsJsonMultiDocumentMetadata() = runTest {
        assertMultiDocumentMetadata(OpenApiOutputFormat.JSON)
    }

    fun testBuildsYamlMultiDocumentMetadata() = runTest {
        assertMultiDocumentMetadata(OpenApiOutputFormat.YAML)
    }

    fun testReturnsDelegateErrorUnchanged() = runTest {
        val expected = ExportResult.Error("delegate failed")
        val delegate = RecordingChannel(expected)

        val actual = OpenApiMultiDocumentChannel(delegate).export(context())

        assertSame("Delegate error should pass through unchanged", expected, actual)
        assertEquals("Delegate should be called once", 1, delegate.callCount)
    }

    fun testReturnsDelegateCancellationUnchanged() = runTest {
        val delegate = RecordingChannel(ExportResult.Cancelled)

        val actual = OpenApiMultiDocumentChannel(delegate).export(context())

        assertSame("Delegate cancellation should pass through unchanged", ExportResult.Cancelled, actual)
        assertEquals("Delegate should be called once", 1, delegate.callCount)
    }

    fun testRejectsSuccessWithoutOpenApiMetadata() = runTest {
        val delegate = RecordingChannel(ExportResult.Success(1, "OpenAPI"))

        val error = expectError(OpenApiMultiDocumentChannel(delegate).export(context()))

        assertTrue("Error should identify missing OpenAPI metadata", error.message.contains("OpenApiExportMetadata"))
        assertEquals("Delegate should be called once", 1, delegate.callCount)
    }

    fun testRejectsSuccessWithForeignMetadata() = runTest {
        val delegate = RecordingChannel(ExportResult.Success(1, "OpenAPI", ForeignMetadata))

        val error = expectError(OpenApiMultiDocumentChannel(delegate).export(context()))

        assertTrue("Error should identify foreign OpenAPI metadata", error.message.contains("OpenApiExportMetadata"))
        assertEquals("Delegate should be called once", 1, delegate.callCount)
    }

    fun testValidatesOnlySelectedEndpoints() = runTest {
        val allEndpoints = conflictingEndpoints()
        val selectedEndpoints = listOf(allEndpoints.first())
        val selectedDocument = document().let { document ->
            document.copy(paths = linkedMapOf("/users/{id}" to document.paths.getValue("/users")))
        }
        val delegate = RecordingChannel(
            successResult(
                OpenApiOutputFormat.JSON,
                selectedDocument,
            ),
        )

        val result = OpenApiMultiDocumentChannel(delegate).export(
            ExportContext(
                project = project,
                endpoints = allEndpoints,
                selectedEndpoints = selectedEndpoints,
                channelId = "openapi-multi",
            ),
        )

        assertTrue("Selecting one owner should succeed, got: $result", result is ExportResult.Success)
        assertEquals("Delegate should run exactly once for the selected endpoint", 1, delegate.callCount)
        val metadata = (result as ExportResult.Success).metadata as? OpenApiMultiDocumentExportMetadata
            ?: throw AssertionError("Success should contain OpenApiMultiDocumentExportMetadata")
        val rootPaths = parse(metadata.content, OpenApiOutputFormat.JSON).path("paths")
        assertEquals("Metadata should contain only the selected path", 1, rootPaths.size())
        assertTrue("Metadata should retain the selected path", rootPaths.has("/users/{id}"))
        assertEquals(
            "Metadata should contain only the selected controller fragment",
            listOf("paths/UserController.json"),
            metadata.additionalFiles.keys.filter { it.startsWith("paths/") },
        )
    }

    fun testRejectsOwnershipConflictBeforeDelegate() = runTest {
        val conflictEndpoints = conflictingEndpoints()
        val delegate = RecordingChannel(successResult(OpenApiOutputFormat.JSON))

        val error = expectError(
            OpenApiMultiDocumentChannel(delegate).export(
                ExportContext(
                    project = project,
                    endpoints = conflictEndpoints,
                    selectedEndpoints = conflictEndpoints,
                    channelId = "openapi-multi",
                ),
            ),
        )

        assertTrue("Error should identify normalized path ownership conflict", error.message.contains("ownership conflict"))
        assertTrue("Error should include the normalized path", error.message.contains("/users/{id}"))
        assertEquals("Delegate must not run after ownership conflict", 0, delegate.callCount)
    }

    fun testRejectsUnresolvedAlwaysAskFormat() = runTest {
        val delegate = RecordingChannel(successResult(OpenApiOutputFormat.ALWAYS_ASK))

        val error = expectError(OpenApiMultiDocumentChannel(delegate).export(context()))

        assertTrue("Error should identify unresolved ALWAYS_ASK", error.message.contains("ALWAYS_ASK"))
        assertEquals("Delegate should still run exactly once", 1, delegate.callCount)
    }

    fun testHandleResultDoesNotWriteFilesYet() = runTest {
        val outputDirectory = Files.createTempDirectory("easyapi-openapi-multi-channel")
        try {
            val success = ExportResult.Success(
                count = 3,
                target = "delegate-target",
                metadata = multiDocumentMetadata(OpenApiOutputFormat.JSON),
            )

            val handled = OpenApiMultiDocumentChannel().handleResult(
                project,
                success,
                ChannelConfig.FileConfig(outputDirectory.toString(), "openapi.json"),
            )

            assertFalse("Task 3 should leave result handling to the later writer task", handled)
            Files.list(outputDirectory).use { files ->
                assertEquals("Channel should not write any files yet", 0L, files.count())
            }
        } finally {
            Files.deleteIfExists(outputDirectory)
        }
    }

    private suspend fun assertMultiDocumentMetadata(format: OpenApiOutputFormat) {
        val delegate = RecordingChannel(successResult(format))

        val result = OpenApiMultiDocumentChannel(delegate).export(context())

        assertTrue("Export should succeed for $format, got: $result", result is ExportResult.Success)
        val success = result as ExportResult.Success
        assertEquals("Delegate count should be preserved", 7, success.count)
        assertEquals("Delegate target should be preserved", "delegate-target", success.target)
        assertEquals("Delegate should be called once", 1, delegate.callCount)
        val metadata = success.metadata as? OpenApiMultiDocumentExportMetadata
            ?: throw AssertionError("Success should contain OpenApiMultiDocumentExportMetadata")
        assertEquals("Resolved format should be preserved", format, metadata.outputFormat)
        assertEquals("Path fragment count should be transferred", 3, metadata.pathFragmentCount)
        assertEquals("Schema count should be transferred", 1, metadata.schemaCount)
        assertEquals("Unresolved path count should be transferred", 1, metadata.unresolvedPathCount)
        assertTrue("Transformer warnings should be transferred", metadata.warnings.any { it.contains("/orphan") })
        assertEquals("Display should identify multi-document format", "Format: ${format.name} (Multi-Document)", metadata.formatDisplay())

        val extension = if (format == OpenApiOutputFormat.JSON) "json" else "yaml"
        assertEquals(
            "All generated files should be serialized in transformer order",
            listOf(
                "paths/UserController.$extension",
                "paths/AdminController.$extension",
                "paths/Unresolved.$extension",
                "schemas/schemas.$extension",
            ),
            metadata.additionalFiles.keys.toList(),
        )
        assertSerializedDocuments(metadata, format)
    }

    private fun assertSerializedDocuments(
        metadata: OpenApiMultiDocumentExportMetadata,
        format: OpenApiOutputFormat,
    ) {
        val root = parse(metadata.content, format)
        assertEquals("Root should remain OpenAPI 3.0.3", "3.0.3", root.path("openapi").asText())
        assertEquals("Root should reference every path", 3, root.path("paths").size())
        root.path("paths").properties().forEach { (_, pathItem) ->
            assertTrue("Every root path item should contain a ref", pathItem.has("\$ref"))
        }
        assertTrue(
            "Root schemas should reference the shared schema document",
            root.path("components").path("schemas").path("User").has("\$ref"),
        )
        assertFalse("Null fields should be omitted", root.path("info").has("description"))

        metadata.additionalFiles.forEach { (fileName, content) ->
            val parsed = parse(content, format)
            when {
                fileName.startsWith("paths/") -> {
                    assertTrue("$fileName should contain paths", parsed.path("paths").isObject)
                    assertEquals("$fileName should contain one path", 1, parsed.path("paths").size())
                    assertTrue("$fileName should contain rewritten schema refs", parsed.findValues("\$ref").isNotEmpty())
                }

                fileName.startsWith("schemas/") ->
                    assertTrue(
                        "$fileName should contain the shared User schema",
                        parsed.path("components").path("schemas").has("User"),
                    )

                else -> fail("Unexpected additional file: $fileName")
            }
        }

        when (format) {
            OpenApiOutputFormat.JSON ->
                assertTrue("JSON should keep HTML characters unescaped", metadata.content.contains("Multi <API>"))

            OpenApiOutputFormat.YAML ->
                assertTrue("YAML should quote the OpenAPI version and omit a document marker", metadata.content.startsWith("openapi: \"3.0.3\""))

            OpenApiOutputFormat.ALWAYS_ASK -> fail("ALWAYS_ASK should not be serialized")
        }
    }

    private fun parse(content: String, format: OpenApiOutputFormat): JsonNode = when (format) {
        OpenApiOutputFormat.JSON -> ObjectMapper().readTree(content)
        OpenApiOutputFormat.YAML -> YAMLMapper().readTree(content)
        OpenApiOutputFormat.ALWAYS_ASK -> throw AssertionError("ALWAYS_ASK should not be parsed")
    }

    private fun context(): ExportContext = ExportContext(
        project = project,
        endpoints = endpoints(),
        channelId = "openapi-multi",
        channelConfig = OpenApiConfig(outputFormat = OpenApiOutputFormat.JSON),
    )

    private fun successResult(
        format: OpenApiOutputFormat,
        document: OpenApiDocument = document(),
    ) = ExportResult.Success(
        count = 7,
        target = "delegate-target",
        metadata = OpenApiExportMetadata(
            document = document,
            outputFormat = format,
            content = "delegate-single-document",
        ),
    )

    private fun multiDocumentMetadata(format: OpenApiOutputFormat) = OpenApiMultiDocumentExportMetadata(
        outputFormat = format,
        content = "root",
        additionalFiles = linkedMapOf("paths/UserController.json" to "fragment"),
        pathFragmentCount = 1,
        schemaCount = 0,
        unresolvedPathCount = 0,
        warnings = emptyList(),
    )

    private fun endpoints() = listOf(
        endpoint("/users", HttpMethod.GET, "com.acme.UserController"),
        endpoint("/admins", HttpMethod.GET, "com.acme.AdminController"),
    )

    private fun conflictingEndpoints() = listOf(
        endpoint("/users/{id:\\d+}", HttpMethod.GET, "com.acme.UserController"),
        endpoint("/users/{id}", HttpMethod.POST, "com.acme.AdminController"),
    )

    private fun endpoint(path: String, method: HttpMethod, className: String) = ApiEndpoint(
        name = method.name,
        className = className,
        metadata = httpMetadata(path, method),
    )

    private fun document(): OpenApiDocument {
        fun path(operationId: String) = PathItemObject(
            get = OperationObject(
                operationId = operationId,
                responses = linkedMapOf(
                    "200" to ResponseObject(
                        description = "OK",
                        content = linkedMapOf(
                            "application/json" to MediaTypeObject(SchemaObject(`$ref` = "#/components/schemas/User")),
                        ),
                    ),
                ),
            ),
        )

        return OpenApiDocument(
            info = InfoObject(title = "Multi <API>", version = "1.0.0"),
            paths = linkedMapOf(
                "/users" to path("getUsers"),
                "/admins" to path("getAdmins"),
                "/orphan" to path("getOrphan"),
            ),
            components = ComponentsObject(
                schemas = linkedMapOf(
                    "User" to SchemaObject(
                        type = "object",
                        properties = linkedMapOf("id" to SchemaObject(type = "string")),
                    ),
                ),
            ),
        )
    }

    private fun expectError(result: ExportResult): ExportResult.Error {
        assertTrue("Expected ExportResult.Error, got: $result", result is ExportResult.Error)
        return result as ExportResult.Error
    }

    private class RecordingChannel(private val result: ExportResult) : Channel {
        override val id: String = "recording"
        override val displayName: String = "Recording"
        var callCount: Int = 0
            private set
        var lastContext: ExportContext? = null
            private set

        override suspend fun export(context: ExportContext): ExportResult {
            callCount++
            lastContext = context
            return result
        }
    }

    private object ForeignMetadata : ExportMetadata {
        override fun formatDisplay(): String = "foreign"
    }
}
