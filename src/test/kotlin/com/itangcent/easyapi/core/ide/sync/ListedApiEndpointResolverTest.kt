package com.itangcent.easyapi.core.ide.sync

import com.itangcent.easyapi.testFramework.EasyApiLightCodeInsightFixtureTestCase
import com.itangcent.easyapi.testFramework.TestConfigReader

class ListedApiEndpointResolverTest : EasyApiLightCodeInsightFixtureTestCase() {

    private lateinit var resolver: ListedApiEndpointResolver

    override fun setUp() {
        super.setUp()
        loadFile("spring/RequestMapping.java")
        loadFile("spring/GetMapping.java")
        loadFile("spring/PostMapping.java")
        loadFile("spring/PutMapping.java")
        loadFile("spring/RestController.java")
        loadFile("spring/Controller.java")
        loadFile("spring/RequestParam.java")
        loadFile("spring/PathVariable.java")
        loadFile("spring/RequestBody.java")
        loadFile("spring/ModelAttribute.java")
        loadFile("model/Result.java")
        loadFile("model/UserInfo.java")
        loadFile("api/UserCtrl.java")
        loadFile(
            "api/OverloadedCtrl.java",
            """
            package com.itangcent.api;

            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RequestMapping;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            @RequestMapping("/overloaded")
            public class OverloadedCtrl {
                @GetMapping("/string")
                public String find(String value) { return value; }

                @GetMapping("/long")
                public String find(Long value) { return String.valueOf(value); }
            }
            """.trimIndent()
        )
        loadFile(
            "api/UserService.java",
            """
            package com.itangcent.api;

            public class UserService {
                public String find() { return "user"; }
            }
            """.trimIndent()
        )
        resolver = ListedApiEndpointResolver(project)
    }

    override fun createConfigReader() = TestConfigReader.empty(project)

    fun testResolvesListedMethodToItsEndpoint() = runTest {
        val result = resolver.resolve(
            listOf(ControllerMethodSelector("com.itangcent.api.UserCtrl", "get", null, 1))
        )

        assertEquals("Should resolve exactly one endpoint", 1, result.endpoints.size)
        assertEquals("Endpoint should belong to the listed method", "get", result.endpoints.single().sourceMethod?.name)
        assertTrue("Resolved selector should not produce errors", result.errors.isEmpty())
    }

    fun testResolvesSignatureQualifiedMethod() = runTest {
        val result = resolver.resolve(
            listOf(
                ControllerMethodSelector(
                    "com.itangcent.api.UserCtrl",
                    "create",
                    listOf("com.itangcent.model.UserInfo"),
                    1
                )
            )
        )

        assertTrue("Signature-qualified method should produce endpoints", result.endpoints.isNotEmpty())
        assertTrue(
            "Every endpoint should belong to the signature-qualified method",
            result.endpoints.all { it.sourceMethod?.name == "create" }
        )
        assertTrue("Resolved selector should not produce errors", result.errors.isEmpty())
    }

    fun testRejectsAmbiguousOverloadedMethodWithoutSignature() = runTest {
        val result = resolver.resolve(
            listOf(ControllerMethodSelector("com.itangcent.api.OverloadedCtrl", "find", null, 1))
        )

        assertTrue("Ambiguous selector must not produce endpoints", result.endpoints.isEmpty())
        assertEquals(
            "Should explain how to disambiguate the overload",
            "line 1: method 'find' is overloaded; specify parameter types",
            result.errors.single().message
        )
    }

    fun testIgnoresListedMethodWhenClassIsNotAnApiController() = runTest {
        val result = resolver.resolve(
            listOf(ControllerMethodSelector("com.itangcent.api.UserService", "find", null, 1))
        )

        assertTrue("Non-controller selector must not produce endpoints", result.endpoints.isEmpty())
        assertEquals(
            "Should identify the non-controller class",
            "line 1: class 'com.itangcent.api.UserService' is not an API controller",
            result.errors.single().message
        )
    }
}
