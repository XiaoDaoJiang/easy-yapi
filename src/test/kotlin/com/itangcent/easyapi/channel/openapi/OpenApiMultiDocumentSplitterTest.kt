package com.itangcent.easyapi.channel.openapi

import com.itangcent.easyapi.core.export.ApiEndpoint
import com.itangcent.easyapi.core.export.GrpcMetadata
import com.itangcent.easyapi.core.export.GrpcStreamingType
import com.itangcent.easyapi.core.export.HttpMethod
import com.itangcent.easyapi.core.export.httpMetadata
import org.junit.Assert.assertNotNull
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

    private fun expectConflict(vararg endpoints: ApiEndpoint): IllegalArgumentException {
        return try {
            OpenApiMultiDocumentSplitter(endpoints.toList())
            fail("Expected conflicting path owners to be rejected")
            throw AssertionError("unreachable")
        } catch (error: IllegalArgumentException) {
            error
        }
    }

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
