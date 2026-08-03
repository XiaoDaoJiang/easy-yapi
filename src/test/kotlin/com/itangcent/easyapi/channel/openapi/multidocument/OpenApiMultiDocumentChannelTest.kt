package com.itangcent.easyapi.channel.openapi.multidocument

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class OpenApiMultiDocumentChannelTest : EasyApiLightCodeInsightFixtureTestCase() {

    private lateinit var channel: OpenApiMultiDocumentChannel
    private var previousDialog: TestDialog? = null

    override fun setUp() {
        super.setUp()
        channel = OpenApiMultiDocumentChannel()
        previousDialog = try {
            TestDialogManager.setTestDialog(TestDialog { 0 })
        } catch (_: Exception) {
            null
        }
    }

    override fun tearDown() {
        try {
            previousDialog?.let { TestDialogManager.setTestDialog(it) }
        } finally {
            super.tearDown()
        }
    }

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

    fun testHandleResultDoesNotWriteFilesWithoutMetadata() = runTest {
        val outputDirectory = Files.createTempDirectory("easyapi-openapi-multi-channel")
        try {
            val success = ExportResult.Success(
                count = 3,
                target = "delegate-target",
            )

            val handled = channel.handleResult(
                project,
                success,
                ChannelConfig.FileConfig(outputDirectory.toString(), "openapi.json"),
            )

            assertFalse("Success without multi-document metadata should not be handled", handled)
            Files.list(outputDirectory).use { files ->
                assertEquals("Channel should not write files without metadata", 0L, files.count())
            }
        } finally {
            Files.deleteIfExists(outputDirectory)
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
        val success = ExportResult.Success(
            count = 1,
            target = "Foreign",
            metadata = ForeignMetadata,
        )

        val handled = channel.handleResult(project, success, ChannelConfig.Empty)

        assertFalse("handleResult should return false for foreign metadata", handled)
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
        metadata = multiDocumentMetadata(
            format = format,
            content = content,
            additionalFiles = additionalFiles,
            pathFragmentCount = pathFragmentCount,
            schemaCount = schemaCount,
            unresolvedPathCount = unresolvedPathCount,
            warnings = warnings,
        ),
    )

    private fun multiDocumentMetadata(
        format: OpenApiOutputFormat,
        content: String = "root",
        additionalFiles: LinkedHashMap<String, String> = linkedMapOf(
            "paths/UserController.${format.name.lowercase()}" to "fragment",
        ),
        pathFragmentCount: Int = 1,
        schemaCount: Int = 0,
        unresolvedPathCount: Int = 0,
        warnings: List<String> = emptyList(),
    ) = OpenApiMultiDocumentExportMetadata(
        outputFormat = format,
        content = content,
        additionalFiles = additionalFiles,
        pathFragmentCount = pathFragmentCount,
        schemaCount = schemaCount,
        unresolvedPathCount = unresolvedPathCount,
        warnings = warnings,
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
