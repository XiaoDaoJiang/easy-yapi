package com.itangcent.easyapi.core.ide.sync

import com.itangcent.easyapi.testFramework.EasyApiLightCodeInsightFixtureTestCase
import com.itangcent.easyapi.testFramework.TestConfigReader

class ChangedApiCandidateResolverTest : EasyApiLightCodeInsightFixtureTestCase() {

    override fun setUp() {
        super.setUp()
        loadFile("spring/RequestMapping.java")
        loadFile("spring/GetMapping.java")
        loadFile("spring/RestController.java")
    }

    override fun createConfigReader() = TestConfigReader.empty(project)

    fun testMethodBodyChangeSelectsOnlyChangedMethod() = runTest {
        val before = controllerSource("return \"before\";")
        val currentFile = myFixture.configureByText(
            "ChangedController.java",
            controllerSource("return \"after\";")
        )

        val result = ChangedApiCandidateResolver(project).resolve(
            listOf(ChangedSourceFile(currentFile, before))
        )

        val selector = result.candidates.single().selector as ControllerMethodSelector
        assertEquals("Changed method should be selected", "demo.ChangedController", selector.className)
        assertEquals("Changed method should be selected", "changed", selector.methodName)
        assertTrue("Exact method change should not produce warnings", result.warnings.isEmpty())
    }

    fun testClassMappingChangeSelectsWholeController() = runTest {
        val before = controllerSource("return \"value\";", "/before")
        val currentFile = myFixture.configureByText(
            "ChangedController.java",
            controllerSource("return \"value\";", "/after")
        )

        val result = ChangedApiCandidateResolver(project).resolve(
            listOf(ChangedSourceFile(currentFile, before))
        )

        val selector = result.candidates.single().selector as ControllerSelector
        assertEquals("Class-level mapping should select the Controller", "demo.ChangedController", selector.className)
    }

    fun testDeletedClassMappingSelectsWholeController() = runTest {
        val before = controllerSource("return \"value\";")
        val currentFile = myFixture.configureByText(
            "ChangedController.java",
            before.replace("@RequestMapping(\"/changed\")\n", "")
        )

        val result = ChangedApiCandidateResolver(project).resolve(
            listOf(ChangedSourceFile(currentFile, before))
        )

        val selector = result.candidates.single().selector as ControllerSelector
        assertEquals("Deleted class mapping should select the Controller", "demo.ChangedController", selector.className)
    }

    fun testNewControllerSelectsWholeController() = runTest {
        val currentFile = myFixture.configureByText(
            "ChangedController.java",
            controllerSource("return \"value\";")
        )

        val result = ChangedApiCandidateResolver(project).resolve(
            listOf(ChangedSourceFile(currentFile, null))
        )

        val selector = result.candidates.single().selector as ControllerSelector
        assertEquals("New Controller should be selected as a whole", "demo.ChangedController", selector.className)
    }

    fun testDeletedMethodBodyLineMapsBackToCurrentMethod() = runTest {
        val before = controllerSource(
            "String removed = \"removed\";\n                return \"value\";"
        )
        val currentFile = myFixture.configureByText(
            "ChangedController.java",
            controllerSource("return \"value\";")
        )

        val result = ChangedApiCandidateResolver(project).resolve(
            listOf(ChangedSourceFile(currentFile, before))
        )

        val selector = result.candidates.single().selector as ControllerMethodSelector
        assertEquals("Deleted body line should map to the surviving method", "changed", selector.methodName)
    }

    fun testWhitespaceOnlyChangeIsIgnored() = runTest {
        val before = controllerSource("return \"value\";")
        val currentFile = myFixture.configureByText(
            "ChangedController.java",
            controllerSource("return  \"value\";")
        )

        val result = ChangedApiCandidateResolver(project).resolve(
            listOf(ChangedSourceFile(currentFile, before))
        )

        assertTrue("Whitespace-only changes should not select APIs", result.candidates.isEmpty())
    }

    fun testWhitespaceInsideStringLiteralIsARealMethodChange() = runTest {
        val before = controllerSource("return \"ab\";")
        val currentFile = myFixture.configureByText(
            "ChangedController.java",
            controllerSource("return \"a b\";")
        )

        val result = ChangedApiCandidateResolver(project).resolve(
            listOf(ChangedSourceFile(currentFile, before))
        )

        assertEquals("String literal change should select the method", "changed", methodSelector(result).methodName)
    }

    fun testMethodMappingChangeSelectsOnlyMethod() = runTest {
        val before = controllerSource("return \"value\";", methodPath = "/before")
        val currentFile = myFixture.configureByText(
            "ChangedController.java",
            controllerSource("return \"value\";", methodPath = "/after")
        )

        val result = ChangedApiCandidateResolver(project).resolve(
            listOf(ChangedSourceFile(currentFile, before))
        )

        assertEquals("Method mapping change should select only the method", "changed", methodSelector(result).methodName)
    }

    fun testNonControllerChangeIsIgnored() = runTest {
        val before = "package demo; public class UserService { String value() { return \"before\"; } }"
        val currentFile = myFixture.configureByText(
            "UserService.java",
            "package demo; public class UserService { String value() { return \"after\"; } }"
        )

        val result = ChangedApiCandidateResolver(project).resolve(
            listOf(ChangedSourceFile(currentFile, before))
        )

        assertTrue("Non-Controller changes should not select APIs", result.candidates.isEmpty())
    }

    private fun methodSelector(result: ChangedApiCandidateResolution) =
        result.candidates.single().selector as ControllerMethodSelector

    private fun controllerSource(
        changedBody: String,
        classPath: String = "/changed",
        methodPath: String = "/changed"
    ) =
        """
        package demo;

        import org.springframework.web.bind.annotation.GetMapping;
        import org.springframework.web.bind.annotation.RequestMapping;
        import org.springframework.web.bind.annotation.RestController;

        @RestController
        @RequestMapping("$classPath")
        public class ChangedController {
            @GetMapping("$methodPath")
            public String changed() {
                $changedBody
            }

            @GetMapping("/unchanged")
            public String unchanged() {
                return "unchanged";
            }
        }
        """.trimIndent()
}
