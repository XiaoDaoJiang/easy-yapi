package com.itangcent.easyapi.channel.openapi

import com.itangcent.easyapi.core.export.ExportMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Plain-JUnit tests for [OpenApiExportMetadata].
 *
 * Pins the contract:
 *  - `formatDisplay()` returns `"Format: JSON"` for JSON output and
 *    `"Format: YAML"` for YAML output.
 *  - `document` and `content` are stored unmodified (the channel layer
 *    pre-serializes the content so handleResult doesn't re-serialize).
 *  - The class implements [ExportMetadata] (carried in ExportResult.Success).
 *
 * Mirrors the [com.itangcent.easyapi.channel.hoppscotch.HoppscotchExportMetadata]
 * pattern (file at `channel/hoppscotch/HoppscotchExportMetadata.kt` is the
 * reference).
 */
class OpenApiExportMetadataTest {

    @Test
    fun `formatDisplay returns Format JSON for JSON output`() {
        val metadata = OpenApiExportMetadata(
            document = minimalDoc(),
            outputFormat = OpenApiOutputFormat.JSON,
            content = "{}",
        )
        assertEquals("Format: JSON", metadata.formatDisplay())
    }

    @Test
    fun `formatDisplay returns Format YAML for YAML output`() {
        val metadata = OpenApiExportMetadata(
            document = minimalDoc(),
            outputFormat = OpenApiOutputFormat.YAML,
            content = "openapi: \"3.0.3\"",
        )
        assertEquals("Format: YAML", metadata.formatDisplay())
    }

    @Test
    fun `document is stored unmodified`() {
        val doc = minimalDoc()
        val metadata = OpenApiExportMetadata(
            document = doc,
            outputFormat = OpenApiOutputFormat.JSON,
            content = "{}",
        )
        // Identity check — the exact same instance is returned, no copy.
        assertSame(doc, metadata.document)
    }

    @Test
    fun `content is stored unmodified`() {
        val content = """{"openapi":"3.0.3","info":{"title":"T","version":"1.0.0"}}"""
        val metadata = OpenApiExportMetadata(
            document = minimalDoc(),
            outputFormat = OpenApiOutputFormat.JSON,
            content = content,
        )
        // Equality check — content is a String (immutable), so equality is
        // the strongest guarantee we can give.
        assertEquals(content, metadata.content)
    }

    @Test
    fun `metadata implements ExportMetadata interface`() {
        // Compile-time + runtime check — ExportResult.Success carries an
        // ExportMetadata, so the channel layer must produce one.
        val metadata: ExportMetadata = OpenApiExportMetadata(
            document = minimalDoc(),
            outputFormat = OpenApiOutputFormat.JSON,
            content = "{}",
        )
        assertNotNull(metadata.formatDisplay())
    }

    @Test
    fun `data class copy preserves all fields`() {
        // Pin the data-class shape — handleResult reads `document` and
        // `content` directly. A refactor that drops either field would
        // break the file-write flow.
        val doc = minimalDoc()
        val metadata = OpenApiExportMetadata(
            document = doc,
            outputFormat = OpenApiOutputFormat.JSON,
            content = "{}",
        )
        val copy = metadata.copy(outputFormat = OpenApiOutputFormat.YAML)
        assertEquals(OpenApiOutputFormat.YAML, copy.outputFormat)
        assertSame(doc, copy.document)
        assertEquals("{}", copy.content)
    }

    @Test
    fun `legacy constructor defaults to single file metadata`() {
        val metadata = OpenApiExportMetadata(
            document = minimalDoc(),
            outputFormat = OpenApiOutputFormat.JSON,
            content = "{}",
        )

        assertEquals(OpenApiDocumentMode.SINGLE_FILE, metadata.documentMode)
        assertEquals(linkedMapOf<String, String>(), metadata.additionalFiles)
        assertEquals(0, metadata.pathFragmentCount)
        assertEquals(0, metadata.schemaCount)
        assertEquals(0, metadata.unresolvedPathCount)
        assertEquals(emptyList<String>(), metadata.warnings)
    }

    @Test
    fun `multi-file metadata stores generated files and keeps format display`() {
        val additionalFiles = linkedMapOf(
            "paths/UserController.yaml" to "paths:\n  /users: {}",
            "schemas/schemas.yaml" to "components:\n  schemas: {}",
        )
        val warnings = listOf("Path '/hooked' was unresolved")
        val metadata = OpenApiExportMetadata(
            document = minimalDoc(),
            outputFormat = OpenApiOutputFormat.YAML,
            content = "openapi: \"3.0.3\"",
            documentMode = OpenApiDocumentMode.MULTI_FILE_BY_CONTROLLER,
            additionalFiles = additionalFiles,
            pathFragmentCount = 1,
            schemaCount = 2,
            unresolvedPathCount = 1,
            warnings = warnings,
        )

        assertEquals("Format: YAML", metadata.formatDisplay())
        assertSame(additionalFiles, metadata.additionalFiles)
        assertEquals(OpenApiDocumentMode.MULTI_FILE_BY_CONTROLLER, metadata.documentMode)
        assertEquals(1, metadata.pathFragmentCount)
        assertEquals(2, metadata.schemaCount)
        assertEquals(1, metadata.unresolvedPathCount)
        assertSame(warnings, metadata.warnings)
    }

    private fun minimalDoc(): OpenApiDocument = OpenApiDocument(
        info = InfoObject(title = "T", version = "1.0.0"),
        paths = linkedMapOf(),
    )
}
