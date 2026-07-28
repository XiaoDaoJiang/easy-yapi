# Grouped Controller API Selectors Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Resolve manifest selectors once per Controller and support explicit whole-Controller export with `#*` and `.*`.

**Architecture:** Parse whole-Controller and method entries into a sealed selector hierarchy. Group selectors by class name, resolve each `PsiClass` once, scan all resolved classes in one `ApiScanner.scanClasses` call, and route endpoints back to groups through `ApiEndpoint.sourceClass`.

**Tech Stack:** Kotlin, IntelliJ PSI/read actions, JUnit 4, IntelliJ light fixture tests, Gradle

---

### Task 1: Parse whole-Controller selectors

**Files:**
- Modify: `src/test/kotlin/com/itangcent/easyapi/core/ide/sync/ControllerApiManifestTest.kt`
- Modify: `src/main/kotlin/com/itangcent/easyapi/core/ide/sync/ControllerApiManifest.kt`

**Step 1: Write the failing parser test**

Add a plain JUnit test:

```kotlin
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
```

**Step 2: Run the parser test and verify it fails**

Run:

```powershell
$env:JAVA_HOME='E:\Program Files\Java\temurin-17.0.10'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat test --tests "com.itangcent.easyapi.core.ide.sync.ControllerApiManifestTest"
```

Expected: FAIL because `ControllerSelector` and wildcard parsing do not exist.

**Step 3: Add the sealed selector model and wildcard parsing**

Replace the single selector data class with:

```kotlin
internal sealed interface ControllerApiSelector {
    val className: String
    val lineNumber: Int
}

internal data class ControllerSelector(
    override val className: String,
    override val lineNumber: Int
) : ControllerApiSelector

internal data class ControllerMethodSelector(
    override val className: String,
    val methodName: String,
    val parameterTypeNames: List<String>?,
    override val lineNumber: Int
) : ControllerApiSelector
```

Change `ManifestParseResult.selectors` and `parseLine` to use
`ControllerApiSelector`. After validating `className` and `methodSpec`, return:

```kotlin
if (methodSpec == "*") {
    return@runCatching ControllerSelector(className, lineNumber)
}
```

Update the format error to include `[<method>|*]`. Keep method parsing
unchanged.

**Step 4: Run the parser test and verify it passes**

Run the command from Step 2.

Expected: all parser tests PASS.

**Step 5: Commit**

```powershell
git add -- src/main/kotlin/com/itangcent/easyapi/core/ide/sync/ControllerApiManifest.kt src/test/kotlin/com/itangcent/easyapi/core/ide/sync/ControllerApiManifestTest.kt
git commit -m "feat(sync): Select whole Controllers"
```

### Task 2: Resolve selectors once per Controller

**Files:**
- Modify: `src/test/kotlin/com/itangcent/easyapi/core/ide/sync/ListedApiEndpointResolverTest.kt`
- Modify: `src/main/kotlin/com/itangcent/easyapi/core/ide/sync/ListedApiEndpointResolver.kt`

**Step 1: Write failing resolver tests**

Add fixture tests:

```kotlin
fun testResolvesWholeControllerToAllEndpoints() = runTest {
    val result = resolver.resolve(
        listOf(ControllerSelector("com.itangcent.api.UserCtrl", 1))
    )

    assertTrue("Whole Controller should produce multiple endpoints", result.endpoints.size > 1)
    assertTrue("Whole Controller selector should not produce errors", result.errors.isEmpty())
}

fun testWholeControllerSelectorOverridesMethodsInSameGroup() = runTest {
    val result = resolver.resolve(
        listOf(
            ControllerMethodSelector("com.itangcent.api.UserCtrl", "missing", null, 1),
            ControllerSelector("com.itangcent.api.UserCtrl", 2)
        )
    )

    assertTrue("Whole Controller should produce multiple endpoints", result.endpoints.size > 1)
    assertTrue("Wildcard should suppress method lookup errors", result.errors.isEmpty())
}
```

**Step 2: Run the resolver test and verify it fails**

Run:

```powershell
.\gradlew.bat test --tests "com.itangcent.easyapi.core.ide.sync.ListedApiEndpointResolverTest"
```

Expected: compilation or behavior failure because the resolver accepts only
method selectors.

**Step 3: Group resolution by Controller**

Change `resolve` to accept `List<ControllerApiSelector>`. Group selectors by
`className`, then resolve each group in its own `read` block. Resolve and
recognize the class once; if the group contains `ControllerSelector`, return a
whole-class group without looking up methods:

```kotlin
private data class ResolvedController(
    val psiClass: PsiClass,
    val allMethods: Boolean,
    val methods: List<ResolvedMethod>
)

private data class ResolvedMethod(
    val selector: ControllerMethodSelector,
    val method: PsiMethod
)
```

For failed class lookup or recognition, add the same line-numbered error for
each selector in the group. For method-only groups, reuse the current overload
and signature checks against the already-resolved `PsiClass`.

**Step 4: Route scanned endpoints to their groups**

Scan all `ResolvedController.psiClass` values once. Build:

```kotlin
val controllersByClass = resolvedControllers.associateBy { it.psiClass }
```

For each scanned endpoint:

```kotlin
val controller = endpoint.sourceClass?.let(controllersByClass::get) ?: continue
if (controller.allMethods) {
    endpoints += endpoint
    continue
}

val sourceMethod = endpoint.sourceMethod ?: continue
val matched = controller.methods.firstOrNull {
    areMethodsRelated(sourceMethod, it.method)
}
if (matched != null) {
    endpoints += endpoint
    matchedMethods += matched.method
}
```

Report missing endpoint errors only for resolved method selectors. Whole-class
groups keep endpoints even when an endpoint has no `sourceMethod`.

**Step 5: Run the resolver test and verify it passes**

Run the command from Step 2.

Expected: all resolver tests PASS.

**Step 6: Commit**

```powershell
git add -- src/main/kotlin/com/itangcent/easyapi/core/ide/sync/ListedApiEndpointResolver.kt src/test/kotlin/com/itangcent/easyapi/core/ide/sync/ListedApiEndpointResolverTest.kt
git commit -m "perf(sync): Group selectors by Controller"
```

### Task 3: Document and verify whole-Controller selection

**Files:**
- Modify: `docs/knowledge-base/usage-guide.md`
- Generated: `src/main/resources/docs/knowledge-base/usage-guide.md`
- Generated: `skills/easy-yapi-assistant/docs/usage-guide.md`

**Step 1: Update the canonical guide**

Add:

```text
# Whole Controller forms
com.acme.UserController#*
com.acme.UserController.*
```

Explain that a whole-Controller selector wins over method selectors for the
same class.

**Step 2: Sync documentation mirrors**

Run:

```powershell
.\gradlew.bat syncKnowledgeBase
```

Expected: both tracked copies match the canonical guide.

**Step 3: Run focused verification**

Run:

```powershell
.\gradlew.bat test --tests "com.itangcent.easyapi.core.ide.sync.ControllerApiManifestTest" --tests "com.itangcent.easyapi.core.ide.sync.ListedApiEndpointResolverTest" --tests "com.itangcent.easyapi.core.ide.action.SyncListedApisActionTest"
```

Expected: all focused tests PASS.

**Step 4: Commit**

```powershell
git add -- docs/knowledge-base/usage-guide.md src/main/resources/docs/knowledge-base/usage-guide.md skills/easy-yapi-assistant/docs/usage-guide.md
git commit -m "docs(sync): Document whole Controller selectors"
```

**Step 5: Push**

```powershell
git push origin master
```

Expected: `origin/master` advances to the final commit.
