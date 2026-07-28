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
    fun `reports the original line number for malformed selector`() {
        val result = ControllerApiManifest.parse("\ncom.acme.UserController\n")

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
