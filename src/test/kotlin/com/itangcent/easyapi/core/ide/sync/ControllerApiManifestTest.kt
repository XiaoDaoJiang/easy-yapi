package com.itangcent.easyapi.core.ide.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerApiManifestTest {

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
}
