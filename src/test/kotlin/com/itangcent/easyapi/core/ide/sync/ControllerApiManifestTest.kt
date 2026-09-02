package com.itangcent.easyapi.core.ide.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ControllerApiManifestTest {

    @Test
    fun `creates a missing manifest with formatted candidates`() {
        val manifest = temporaryManifest()

        val result = ControllerApiManifest.append(
            manifest,
            listOf(methodCandidate("com.acme.UserController", "create", "java.lang.String"))
        )

        assertTrue("New manifest should be written", result.written)
        assertEquals(
            "New candidates should use hash selectors with parameter types",
            "com.acme.UserController#create(java.lang.String)\n",
            Files.readString(manifest)
        )
    }

    @Test
    fun `preserves prior content and appends only missing candidates`() {
        val manifest = temporaryManifest("# keep this comment\n\ncom.acme.UserController#get()\n")

        val result = ControllerApiManifest.append(
            manifest,
            listOf(
                methodCandidate("com.acme.UserController", "get"),
                methodCandidate("com.acme.UserController", "create", "java.lang.String")
            )
        )

        assertTrue("A missing candidate should be appended", result.written)
        assertEquals(
            "Existing comments, whitespace and selectors should stay unchanged",
            "# keep this comment\n\ncom.acme.UserController#get()\n" +
                "com.acme.UserController#create(java.lang.String)\n",
            Files.readString(manifest)
        )
    }

    @Test
    fun `treats hash and dot selectors as duplicates`() {
        val manifest = temporaryManifest("com.acme.UserController.create(java.lang.String)\n")

        val result = ControllerApiManifest.append(
            manifest,
            listOf(methodCandidate("com.acme.UserController", "create", "java.lang.String"))
        )

        assertFalse("Equivalent dot selector should not be appended again", result.written)
        assertEquals("Existing selector spelling must be preserved", "com.acme.UserController.create(java.lang.String)\n", Files.readString(manifest))
    }

    @Test
    fun `existing class wildcard covers incoming methods`() {
        val manifest = temporaryManifest("com.acme.UserController#*\n")

        val result = ControllerApiManifest.append(
            manifest,
            listOf(methodCandidate("com.acme.UserController", "create", "java.lang.String"))
        )

        assertFalse("Existing class wildcard should cover the method", result.written)
        assertEquals("Wildcard manifest must remain unchanged", "com.acme.UserController#*\n", Files.readString(manifest))
    }

    @Test
    fun `incoming class wildcard covers same controller methods`() {
        val manifest = temporaryManifest()

        val result = ControllerApiManifest.append(
            manifest,
            listOf(
                methodCandidate("com.acme.UserController", "create", "java.lang.String"),
                classCandidate("com.acme.UserController"),
                methodCandidate("com.acme.UserController", "get")
            )
        )

        assertTrue("Incoming wildcard should be appended", result.written)
        assertEquals("Wildcard should replace same-controller incoming methods", "com.acme.UserController#*\n", Files.readString(manifest))
    }

    @Test
    fun `invalid existing manifest causes zero write`() {
        val manifest = temporaryManifest("com.acme.UserController#\n")

        val result = ControllerApiManifest.append(
            manifest,
            listOf(methodCandidate("com.acme.UserController", "create"))
        )

        assertFalse("Invalid manifest must not be changed", result.written)
        assertTrue("Invalid manifest should report parse errors", result.errors.isNotEmpty())
        assertEquals("Invalid content must remain unchanged", "com.acme.UserController#\n", Files.readString(manifest))
    }

    @Test
    fun `empty merge causes zero write`() {
        val manifest = temporaryManifest("com.acme.UserController#create()\n")

        val result = ControllerApiManifest.append(manifest, emptyList())

        assertFalse("Empty candidate merge must not write", result.written)
        assertEquals("Existing content must remain unchanged", "com.acme.UserController#create()\n", Files.readString(manifest))
    }

    @Test
    fun `parses comments simple selectors and signature selectors`() {
        val result = ControllerApiManifest.parse(
            """
            # changed this iteration
            com.acme.UserController#get
            com.acme.UserController#create(com.acme.CreateRequest,java.lang.String)
            """.trimIndent()
        )

        assertEquals(
            "Should parse simple and signature-qualified selectors",
            listOf(
                ControllerMethodSelector("com.acme.UserController", "get", null, 2),
                ControllerMethodSelector(
                    "com.acme.UserController",
                    "create",
                    listOf("com.acme.CreateRequest", "java.lang.String"),
                    3
                )
            ),
            result.selectors
        )
        assertTrue("Valid manifest should not contain errors", result.errors.isEmpty())
    }

    @Test
    fun `parses Java style dot selectors`() {
        val result = ControllerApiManifest.parse(
            """
            com.gyenno.scoring.project.api.PatientApi.queryPatientList
            com.gyenno.scoring.project.api.PatientApi.queryPatient(java.lang.String)
            """.trimIndent()
        )

        assertEquals(
            "Should parse simple and signature-qualified dot selectors",
            listOf(
                ControllerMethodSelector(
                    "com.gyenno.scoring.project.api.PatientApi",
                    "queryPatientList",
                    null,
                    1
                ),
                ControllerMethodSelector(
                    "com.gyenno.scoring.project.api.PatientApi",
                    "queryPatient",
                    listOf("java.lang.String"),
                    2
                )
            ),
            result.selectors
        )
        assertTrue("Valid dot selectors should not contain errors", result.errors.isEmpty())
    }

    @Test
    fun `parses whole Controller selectors`() {
        val result = ControllerApiManifest.parse(
            """
            com.acme.UserController#*
            com.acme.AdminController.*
            """.trimIndent()
        )

        assertEquals(
            "Both separators should support whole Controller selection",
            listOf(
                ControllerSelector("com.acme.UserController", 1),
                ControllerSelector("com.acme.AdminController", 2)
            ),
            result.selectors
        )
        assertTrue("Whole Controller selectors should not contain errors", result.errors.isEmpty())
    }

    @Test
    fun `reports the original line number for malformed selector`() {
        val result = ControllerApiManifest.parse("\ncom.acme.UserController#\n")

        assertEquals("Malformed selector should retain its source line", 2, result.errors.single().lineNumber)
    }

    @Test
    fun `rejects empty parameter and unbalanced parentheses`() {
        val result = ControllerApiManifest.parse(
            """
            com.acme.UserController#create(com.acme.Request,)
            com.acme.UserController#get(java.lang.String
            """.trimIndent()
        )

        assertEquals("Both malformed selectors should be reported", listOf(1, 2), result.errors.map { it.lineNumber })
        assertTrue("Malformed selectors must not be returned", result.selectors.isEmpty())
    }

    private fun temporaryManifest(content: String? = null): Path {
        val directory = Files.createTempDirectory("controller-api-manifest")
        return directory.resolve(".easyapi").resolve("sync").resolve("sync-apis.txt").also { manifest ->
            if (content != null) {
                Files.createDirectories(manifest.parent)
                Files.writeString(manifest, content)
            }
        }
    }

    private fun classCandidate(className: String) =
        ChangedApiCandidate(ControllerSelector(className, 1), "test")

    private fun methodCandidate(className: String, methodName: String, vararg parameterTypes: String) =
        ChangedApiCandidate(ControllerMethodSelector(className, methodName, parameterTypes.toList(), 1), "test")
}
