# Sync Listed API Selector Compatibility Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Accept Java-style `class.method` API selectors and prevent background resolution from reading PSI outside a read action.

**Architecture:** Extend only `ControllerApiManifest` so both `#` and the final dot before the parameter list produce the existing `ControllerMethodSelector`. Keep the resolver pipeline unchanged except for wrapping `PsiMethod.containingClass` access in the existing `read` helper.

**Tech Stack:** Kotlin, IntelliJ PSI/read actions, JUnit 4, IntelliJ light fixture tests, Gradle

---

### Task 1: Parse Java-style dot selectors

**Files:**
- Modify: `src/test/kotlin/com/itangcent/easyapi/core/ide/sync/ControllerApiManifestTest.kt`
- Modify: `src/main/kotlin/com/itangcent/easyapi/core/ide/sync/ControllerApiManifest.kt`

**Step 1: Write the failing parser test**

Add a plain JUnit test covering both dot forms:

```kotlin
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
```

Change the malformed-selector line-number test input from a bare qualified name
to `com.acme.UserController#`, because a bare qualified name is
indistinguishable from `class.method` at parse time.

**Step 2: Run the parser test and verify it fails**

Run:

```powershell
$env:JAVA_HOME='E:\Program Files\Java\temurin-17.0.10'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat test --tests "com.itangcent.easyapi.core.ide.sync.ControllerApiManifestTest"
```

Expected: FAIL because dot selectors currently require `#`.

**Step 3: Implement the minimum separator selection**

In `ControllerApiManifest.parseLine`, prefer the existing single `#`; when it
is absent, find the final dot before the optional parameter list:

```kotlin
val hashSeparator = line.indexOf('#')
val separator = if (hashSeparator >= 0) {
    require(hashSeparator > 0 && hashSeparator == line.lastIndexOf('#')) { FORMAT_ERROR }
    hashSeparator
} else {
    val parametersStart = line.indexOf('(').takeIf { it >= 0 } ?: line.length
    line.lastIndexOf('.', parametersStart - 1)
}
require(separator > 0) { FORMAT_ERROR }
```

Update `FORMAT_ERROR` to mention both separators. Do not add a second selector
type or change endpoint resolution.

**Step 4: Run the parser test and verify it passes**

Run the command from Step 2.

Expected: all `ControllerApiManifestTest` tests PASS.

**Step 5: Commit**

```powershell
git add -- src/main/kotlin/com/itangcent/easyapi/core/ide/sync/ControllerApiManifest.kt src/test/kotlin/com/itangcent/easyapi/core/ide/sync/ControllerApiManifestTest.kt
git commit -m "feat(sync): Accept Java-style API selectors"
```

### Task 2: Reproduce and fix background PSI read access

**Files:**
- Modify: `src/test/kotlin/com/itangcent/easyapi/core/ide/sync/ListedApiEndpointResolverTest.kt`
- Modify: `src/main/kotlin/com/itangcent/easyapi/core/ide/sync/ListedApiEndpointResolver.kt`

**Step 1: Write the failing regression test**

Import the existing background dispatcher helper and resolve an API from a
background context:

```kotlin
import com.itangcent.easyapi.core.internal.threading.background

fun testResolvesOnBackgroundThreadWithoutReadAccessViolation() = runTest {
    val result = background {
        resolver.resolve(
            listOf(ControllerMethodSelector("com.itangcent.api.UserCtrl", "get", null, 1))
        )
    }

    assertEquals("Background resolution should find the endpoint", 1, result.endpoints.size)
}
```

**Step 2: Run the resolver test and verify it fails**

Run:

```powershell
.\gradlew.bat test --tests "com.itangcent.easyapi.core.ide.sync.ListedApiEndpointResolverTest"
```

Expected: FAIL with `RuntimeExceptionWithAttachments` at
`PsiMethodImpl.getContainingClass` from `ListedApiEndpointResolver.resolve`.

**Step 3: Wrap the PSI read at its source**

Change class extraction to:

```kotlin
val classes = read {
    resolvedMethods.mapNotNull { it.method.containingClass }.distinct()
}
```

Do not wrap scanning or export in a long-lived read action.

**Step 4: Run the resolver test and verify it passes**

Run the command from Step 2.

Expected: all `ListedApiEndpointResolverTest` tests PASS.

**Step 5: Commit**

```powershell
git add -- src/main/kotlin/com/itangcent/easyapi/core/ide/sync/ListedApiEndpointResolver.kt src/test/kotlin/com/itangcent/easyapi/core/ide/sync/ListedApiEndpointResolverTest.kt
git commit -m "fix(sync): Prevent PSI read access failure"
```

### Task 3: Document and verify the accepted formats

**Files:**
- Modify: `docs/knowledge-base/usage-guide.md`
- Generated: `src/main/resources/docs/knowledge-base/usage-guide.md`
- Generated: `skills/easy-yapi-assistant/docs/usage-guide.md`

**Step 1: Update the canonical usage guide**

Show the two equivalent separator styles and their signature-qualified forms.
Keep `docs/knowledge-base/usage-guide.md` as the source of truth.

**Step 2: Sync the tracked documentation mirrors**

Run:

```powershell
.\gradlew.bat syncKnowledgeBase
```

Expected: the resource and skill copies match the canonical guide.

**Step 3: Run focused verification**

Run:

```powershell
.\gradlew.bat test --tests "com.itangcent.easyapi.core.ide.sync.ControllerApiManifestTest" --tests "com.itangcent.easyapi.core.ide.sync.ListedApiEndpointResolverTest"
```

Expected: all focused tests PASS.

**Step 4: Commit**

```powershell
git add -- docs/knowledge-base/usage-guide.md src/main/resources/docs/knowledge-base/usage-guide.md skills/easy-yapi-assistant/docs/usage-guide.md
git commit -m "docs(sync): Document API selector formats"
```

**Step 5: Push the completed commits**

```powershell
git push origin master
```

Expected: `origin/master` advances to the final local commit.
