# Sync Listed APIs Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 从当前项目固定清单文件列出的 Controller API 方法生成 endpoints，并经现有导出选择窗口导出到用户选择的 EasyYapi 通道。

**Architecture:** Action 只读取 `<project>/.easyapi/sync-apis.txt`，将每行的 Controller 方法选择器解析为 `PsiMethod`，通过现有 `ApiScanner.scanClasses` 生成最新 endpoint 后按 `ApiEndpoint.sourceMethod` 过滤。它不读取 Git diff、不维护 `ApiIndex`、不改变任何通道；候选确认、通道选择和通道配置完全复用 `ExportDialog`。

**Tech Stack:** Kotlin、IntelliJ Platform PSI/VFS、协程、JUnit 4。

---

## Fixed manifest contract

**Location:** `<project root>/.easyapi/sync-apis.txt`。

这个路径适合项目级、可提交的迭代 API 清单：它不污染项目根目录，也不需要新增设置或让用户在 Action 中选文件。若团队不希望提交迭代清单，可自行把 `.easyapi/` 加入目标项目的 `.gitignore`；插件不自动写入或创建该文件。

**Format:** UTF-8 纯文本，每行一个方法选择器；空行和首个非空字符为 `#` 的行忽略。

```text
# 本迭代需要同步的接口
com.gyenno.pdms.modules.selfassessment.controller.SelfAssessmentController#taskQrCode
com.acme.user.UserController#createUser(com.acme.user.CreateUserRequest)
com.acme.order.OrderController#cancel(java.lang.Long)
```

主格式是 `<Controller 全限定类名>#<方法名>(<参数类型规范名>,...)`，并兼容简写 `<Controller 全限定类名>#<方法名>`。简写仅在同名方法唯一时接受；存在重载时该行报错并要求补全参数列表，绝不猜测。选择纯文本而非 YAML/JSON：这里只有有序方法列表和注释，不值得引入层级结构、字段名和额外解析规则。

### Task 1: Parse the fixed manifest format

**Files:**
- Create: `src/main/kotlin/com/itangcent/easyapi/core/ide/sync/ControllerApiManifest.kt`
- Test: `src/test/kotlin/com/itangcent/easyapi/core/ide/sync/ControllerApiManifestTest.kt`

**Step 1: Select the test pattern before writing tests**

Invoke `@write-test-case` for `ControllerApiManifest`. Use plain JUnit because parsing is pure Kotlin and does not need a project fixture.

**Step 2: Write the failing parser tests**

```kotlin
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
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `reports the original line number for malformed selector`() {
        val result = ControllerApiManifest.parse("\ncom.acme.UserController\n")
        assertEquals(2, result.errors.single().lineNumber)
    }
}
```

**Step 3: Run the test to verify it fails**

Run: `./gradlew test --tests "com.itangcent.easyapi.core.ide.sync.ControllerApiManifestTest"`

Expected: FAIL because the manifest parser does not exist.

**Step 4: Implement the minimal parser**

Create the public data needed by the resolver and no more:

```kotlin
data class ControllerMethodSelector(
    val className: String,
    val methodName: String,
    val parameterTypeNames: List<String>?,
    val lineNumber: Int
)

data class ManifestParseError(val lineNumber: Int, val message: String)

data class ManifestParseResult(
    val selectors: List<ControllerMethodSelector>,
    val errors: List<ManifestParseError>
)

object ControllerApiManifest {
    fun parse(content: String): ManifestParseResult
}
```

Split input with `lineSequence()`, trim each line, skip comments/blank lines, and match the complete selector syntax. Trim parameter tokens and reject blank parameter entries, a missing `#`, a missing class/method name, and unbalanced parentheses. Preserve all parse errors with one-based line numbers; do not silently discard malformed lines.

**Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests "com.itangcent.easyapi.core.ide.sync.ControllerApiManifestTest"`

Expected: PASS.

**Step 6: Commit**

```bash
git add src/main/kotlin/com/itangcent/easyapi/core/ide/sync/ControllerApiManifest.kt src/test/kotlin/com/itangcent/easyapi/core/ide/sync/ControllerApiManifestTest.kt
git commit -m "feat: parse listed controller API methods"
```

### Task 2: Resolve manifest methods and generate current endpoints

**Files:**
- Create: `src/main/kotlin/com/itangcent/easyapi/core/ide/sync/ListedApiEndpointResolver.kt`
- Test: `src/test/kotlin/com/itangcent/easyapi/core/ide/sync/ListedApiEndpointResolverTest.kt`
- Reference: `src/main/kotlin/com/itangcent/easyapi/core/dashboard/ApiScanner.kt:118-145`
- Reference: `src/main/kotlin/com/itangcent/easyapi/core/export/recognizer/CompositeApiClassRecognizer.kt:61-65`
- Reference: `src/main/kotlin/com/itangcent/easyapi/core/psi/type/ResolvedTypes.kt`

**Step 1: Write the failing fixture tests**

Invoke `@write-test-case` and choose `EasyApiLightCodeInsightFixtureTestCase`: the resolver must use real PSI classes and methods.

Configure a controller with `getUser(Long)`, `createUser(CreateUserRequest)`, and overloaded `find(String)` / `find(Long)` methods. Assert the following:

```kotlin
fun testResolvesListedMethodToItsEndpoint() = runBlocking {
    val result = resolver.resolve(listOf(ControllerMethodSelector("demo.UserController", "getUser", null, 1)))
    assertEquals(listOf("/users/{id}"), result.endpoints.map { it.path })
    assertTrue(result.errors.isEmpty())
}

fun testRejectsAmbiguousOverloadedMethodWithoutSignature() = runBlocking {
    val result = resolver.resolve(listOf(ControllerMethodSelector("demo.UserController", "find", null, 1)))
    assertEquals("line 1: method 'find' is overloaded; specify parameter types", result.errors.single().message)
}

fun testIgnoresListedMethodWhenClassIsNotAnApiController() = runBlocking {
    val result = resolver.resolve(listOf(ControllerMethodSelector("demo.UserService", "find", null, 1)))
    assertTrue(result.endpoints.isEmpty())
    assertEquals("line 1: class 'demo.UserService' is not an API controller", result.errors.single().message)
}
```

**Step 2: Run the fixture test to verify it fails**

Run: `./gradlew test --tests "com.itangcent.easyapi.core.ide.sync.ListedApiEndpointResolverTest"`

Expected: FAIL because the resolver does not exist.

**Step 3: Implement the minimal resolver**

Create a stateless `ListedApiEndpointResolver(project)` with a suspend `resolve(selectors, indicator?)` returning `ListedApiResolution(endpoints, errors)`. All PSI and index access belongs inside existing `read { ... }`; Action code must never inspect PSI directly.

For every selector, use `JavaPsiFacade.getInstance(project).findClass(className, GlobalSearchScope.projectScope(project))`, then `CompositeApiClassRecognizer.getInstance(project).isApiClass(psiClass)`. Match methods by name; for signatures, compare each `PsiParameter.type.canonicalText` to the declared parameter type. A selector without parameters is valid only for one match. Emit a line-numbered error for a missing class, non-controller, missing method, ambiguous method, or signature mismatch.

Collect the valid methods and their containing classes, call the existing API pipeline once:

```kotlin
val scanned = ApiScanner.getInstance(project).scanClasses(classes, indicator).toList()
val endpoints = scanned.filter { endpoint ->
    val sourceMethod = endpoint.sourceMethod ?: return@filter false
    selectedMethods.any { selected -> areMethodsRelated(sourceMethod, selected) }
}.distinctBy { it.metadata.protocol to it.path }
```

`areMethodsRelated` is the existing relationship check used by scanning logic; use it instead of relying on reference equality, which can be wrong for Kotlin light methods. Log resolved/skipped selector counts through the project console. Do not update or wait for `ApiIndex`.

**Step 4: Run the fixture test to verify it passes**

Run: `./gradlew test --tests "com.itangcent.easyapi.core.ide.sync.ListedApiEndpointResolverTest"`

Expected: PASS.

**Step 5: Commit**

```bash
git add src/main/kotlin/com/itangcent/easyapi/core/ide/sync/ListedApiEndpointResolver.kt src/test/kotlin/com/itangcent/easyapi/core/ide/sync/ListedApiEndpointResolverTest.kt
git commit -m "feat: resolve listed API endpoints"
```

### Task 3: Add the manifest-driven export Action

**Files:**
- Create: `src/main/kotlin/com/itangcent/easyapi/core/ide/action/SyncListedApisAction.kt`
- Modify: `src/main/resources/META-INF/plugin.xml:actions`
- Test: `src/test/kotlin/com/itangcent/easyapi/core/ide/action/SyncListedApisActionTest.kt`
- Modify: `docs/knowledge-base/usage-guide.md`

**Step 1: Write failing Action tests**

```kotlin
fun testActionUsesBackgroundUpdateThread() {
    assertEquals(ActionUpdateThread.BGT, SyncListedApisAction().actionUpdateThread)
}

fun testActionDisablesWithoutProject() {
    val event = AnActionEvent.createEvent(DataContext { null }, Presentation(), "test", ActionUiKind.NONE, null)
    SyncListedApisAction().update(event)
    assertFalse(event.presentation.isEnabled)
}
```

**Step 2: Run the Action test to verify it fails**

Run: `./gradlew test --tests "com.itangcent.easyapi.core.ide.action.SyncListedApisActionTest"`

Expected: FAIL because the Action does not exist.

**Step 3: Implement the Action and register it**

Use `Path.of(project.basePath ?: return, ".easyapi", "sync-apis.txt")` and `LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)` to load exactly one manifest. If the file is absent, unreadable, empty, or has parse errors, show one `NotificationUtils.notifyWarning` containing the absolute path and line-numbered errors, then stop before opening `ExportDialog`.

On a valid manifest, call `DumbModeHelper.waitForSmartModeOrNotify`, then use `runWithProgress(project, "Resolving listed APIs...")` for `ListedApiEndpointResolver.resolve`. If all selectors fail or generate no endpoints, report a warning and do not export. Otherwise call the existing dialog and use its result unchanged:

```kotlin
val result = ExportDialog.show(project, resolution.endpoints.size, resolution.endpoints) ?: return@backgroundAsync
val selectedEndpoints = result.selectedEndpoints.map { it.endpoint }
if (selectedEndpoints.isEmpty()) {
    NotificationUtils.notifyWarning(project, "Sync Listed APIs", "No API endpoints selected")
    return@backgroundAsync
}
runWithProgress(project, "Exporting listed APIs...") { indicator ->
    ExportOrchestrator.getInstance(project).exportViaChannel(
        result.channelId, selectedEndpoints, result.channelConfig, indicator
    )
}
```

Add `console.info` for entry, manifest path, valid selector count, skipped selector count, endpoint count, and cancellation. Log file I/O or unexpected exceptions with the throwable and use exactly one terminal error notification. Do not alter `ExportDialog`, `ExportOrchestrator`, any export channel, or `plugin.xml` channel declarations.

Register the Action under existing EasyApi groups:

```xml
<action id="com.itangcent.idea.easy_api.actions.SyncListedApisAction"
        class="com.itangcent.easyapi.core.ide.action.SyncListedApisAction"
        text="Sync Listed APIs..."
        description="Export Controller APIs listed in .easyapi/sync-apis.txt">
    <keyboard-shortcut first-keystroke="alt shift Y" keymap="$default"/>
    <add-to-group group-id="EasyApiGenerateMenu" anchor="last"/>
    <add-to-group group-id="EasyApiEditorLangPopupMenu" anchor="last"/>
    <add-to-group group-id="EasyApiProjectViewPopupMenu" anchor="last"/>
</action>
```

Document the path, “类全名 + 方法名”兼容简写、“类全名 + 方法参数”主格式、重载规则、read-only file behavior, and that users choose the final export channel in the existing dialog.

**Step 4: Run focused verification**

Run:

```bash
./gradlew test --tests "com.itangcent.easyapi.core.ide.sync.ControllerApiManifestTest" --tests "com.itangcent.easyapi.core.ide.sync.ListedApiEndpointResolverTest" --tests "com.itangcent.easyapi.core.ide.action.SyncListedApisActionTest"
./gradlew verifyPlugin
```

Expected: all focused tests and plugin XML verification PASS.

**Step 5: Manually validate in a sandbox IDE**

Run: `./gradlew runIde`

Create `.easyapi/sync-apis.txt` in a sample project, list one non-overloaded Controller method and one signature-qualified overload, invoke `Sync Listed APIs...`, deselect one candidate, choose YApi and then another enabled channel in separate runs. Verify the Action does not create or rewrite the manifest; invalid or missing selectors must stop before the export dialog.

**Step 6: Commit**

```bash
git add src/main/kotlin/com/itangcent/easyapi/core/ide/action/SyncListedApisAction.kt src/main/resources/META-INF/plugin.xml src/test/kotlin/com/itangcent/easyapi/core/ide/action/SyncListedApisActionTest.kt docs/knowledge-base/usage-guide.md
git commit -m "feat: export APIs listed in project manifest"
```
