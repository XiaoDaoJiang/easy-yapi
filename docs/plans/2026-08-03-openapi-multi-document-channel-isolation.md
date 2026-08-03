# OpenAPI Multi-Document Channel Isolation Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Move Controller-grouped OpenAPI multi-document export into an independently enabled channel, add semantic schema names with stable collision hashes, and restore the original single-file OpenAPI channel unchanged.

**Architecture:** `OpenApiMultiDocumentChannel` builds an endpoint ownership/type index, delegates formatting once to `OpenApiChannel`, and transforms the returned `OpenApiDocument` in memory. Channel-local transformer, schema namer, wire DTOs, serializer, and directory writer own all multi-document behavior; the original OpenAPI models, config, options, metadata, serializer, and handler return to their pre-feature state.

**Tech Stack:** Kotlin 2.1, JDK 17, IntelliJ Platform Channel EP, Kotlin coroutines, Gson, Jackson YAML, JUnit 4, Mockito-Kotlin, IntelliJ light fixture tests, Gradle

---

## Working environment

Run every command from:

```text
E:\IdeaProjects\easy-yapi\.worktrees\openapi-multi-document
```

Initialize each new PowerShell session with JDK 17:

```powershell
$env:JAVA_HOME='E:\Program Files\Java\temurin-17.0.10'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
```

The 2026-08-03 baseline run reached the test task and found one unrelated
environment assertion:

```text
DefaultRepositoriesTest.testGradleCachePath
```

`GRADLE_USER_HOME` is `F:\DevCache\Gradle`, while that test performs a
case-sensitive `contains("gradle")` check. The user explicitly chose not to
fix this unrelated test. Use targeted OpenAPI tests as the feature gate and
report this known failure if a final full `test` run is attempted.

Before writing any test in Task 1, invoke the repository-local
`.skills/write-test-case` skill and follow its selected patterns. Before every
commit, invoke `.skills/git-commit` and inspect the staged diff.

### Task 1: Isolate the existing document splitter

**Files:**

- Create: `src/main/kotlin/com/itangcent/easyapi/channel/openapi/multidocument/OpenApiMultiDocumentTransformer.kt`
- Create: `src/test/kotlin/com/itangcent/easyapi/channel/openapi/multidocument/OpenApiMultiDocumentTransformerTest.kt`
- Reference: `src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiMultiDocumentSplitter.kt`
- Reference: `src/test/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiMultiDocumentSplitterTest.kt`

**Step 1: Invoke the test-pattern skill**

Read and apply:

```text
.skills/write-test-case/SKILL.md
```

Select the plain JUnit pattern because the transformer consumes only
`ApiEndpoint` and OpenAPI data classes; it does not require PSI or a Project.

**Step 2: Write the failing transformer test**

Start by moving the existing splitter test cases into the new package. Keep
the existing cases for ownership conflicts, folder/unresolved fallback,
filename allocation, URI encoding, JSON Pointer encoding, schema extraction,
and full `$ref` rewriting.

Add a focused assertion proving the root wire model does not require a
`$ref` field on the original `PathItemObject`:

```kotlin
@Test
fun testBuildsExternalRootPathReferencesWithoutChangingPathItemObject() {
    val transformer = OpenApiMultiDocumentTransformer(
        listOf(endpoint("/users", HttpMethod.GET, "com.acme.UserController"))
    )

    val result = transformer.transform(documentWithGet("/users"), OpenApiOutputFormat.YAML)

    assertEquals(
        "./paths/UserController.yaml#/paths/~1users",
        result.rootDocument.paths.getValue("/users").ref
    )
}
```

The new test helper should construct the same minimal `OpenApiDocument` and
`ApiEndpoint` objects already used by `OpenApiMultiDocumentSplitterTest`.

**Step 3: Run the test and verify it fails**

Run:

```powershell
.\gradlew.bat test --tests "com.itangcent.easyapi.channel.openapi.multidocument.OpenApiMultiDocumentTransformerTest"
```

Expected: FAIL at compilation because `OpenApiMultiDocumentTransformer` and
its channel-local root DTO do not exist.

**Step 4: Create channel-local output DTOs**

Define the minimum wire model in `OpenApiMultiDocumentTransformer.kt`:

```kotlin
@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class ExternalReference(
    @SerializedName("\$ref")
    @get:JsonProperty("\$ref")
    val ref: String,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class MultiDocumentRoot(
    val openapi: String,
    val info: InfoObject,
    val servers: List<ServerObject>?,
    val tags: List<TagObject>?,
    val paths: LinkedHashMap<String, ExternalReference>,
    val components: ComponentsObject?,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class PathsFragment(
    @SerializedName("x-java-controller")
    @get:JsonProperty("x-java-controller")
    val javaController: String? = null,
    @SerializedName("x-easyapi-folder")
    @get:JsonProperty("x-easyapi-folder")
    val easyApiFolder: String? = null,
    @SerializedName("x-easyapi-unresolved")
    @get:JsonProperty("x-easyapi-unresolved")
    val easyApiUnresolved: Boolean? = null,
    val paths: LinkedHashMap<String, PathItemObject>,
)

internal data class MultiDocumentResult(
    val rootDocument: MultiDocumentRoot,
    val additionalDocuments: LinkedHashMap<String, Any>,
    val pathFragmentCount: Int,
    val schemaCount: Int,
    val unresolvedPathCount: Int,
    val warnings: List<String>,
)
```

Keep the schema document wrapper local to this file. Do not add `$ref` to the
original `PathItemObject`.

**Step 5: Adapt the existing splitter logic**

Move, rather than redesign, the validated behavior from
`OpenApiMultiDocumentSplitter`:

- normalize and validate ownership in the constructor;
- preserve Controller → folder → unresolved precedence;
- keep shortest unique package suffixes and Windows-safe filenames;
- keep separate URI-path and JSON-Pointer escaping;
- rewrite all path-level internal schema refs to `../schemas/schemas.<ext>`;
- emit root component refs to `./schemas/schemas.<ext>`;
- create `MultiDocumentRoot` instead of copying `OpenApiDocument` with a
  modified `PathItemObject`.

Expose only:

```kotlin
internal class OpenApiMultiDocumentTransformer(
    endpoints: List<ApiEndpoint>,
) {
    fun transform(
        document: OpenApiDocument,
        outputFormat: OpenApiOutputFormat,
    ): MultiDocumentResult
}
```

Do not introduce an owner SPI or grouping strategy interface.

**Step 6: Run transformer tests**

Run the command from Step 3.

Expected: all transformer tests PASS.

**Step 7: Commit**

Stage only the new transformer and its tests, inspect the staged diff, then
commit with:

```text
refactor(openapi): Isolate document splitting
```

### Task 2: Add semantic schema naming

**Files:**

- Create: `src/main/kotlin/com/itangcent/easyapi/channel/openapi/multidocument/OpenApiSemanticSchemaNamer.kt`
- Create: `src/test/kotlin/com/itangcent/easyapi/channel/openapi/multidocument/OpenApiSemanticSchemaNamerTest.kt`
- Modify: `src/main/kotlin/com/itangcent/easyapi/channel/openapi/multidocument/OpenApiMultiDocumentTransformer.kt`
- Modify: `src/test/kotlin/com/itangcent/easyapi/channel/openapi/multidocument/OpenApiMultiDocumentTransformerTest.kt`

**Step 1: Write failing semantic-name tests**

Use plain JUnit. Cover these four cases:

```kotlin
@Test
fun testNamesGenericResponseFromQualifiedEndpointType() {
    val result = rename(
        responseType = "com.acme.BaseResponse<java.util.List<com.acme.SelfAssessmentTemplateVO>>",
        oldComponent = "BaseResponse",
    )

    assertTrue(result.document.components!!.schemas!!.containsKey(
        "BaseResponse_List_SelfAssessmentTemplateVO"
    ))
    assertResponseRef(result.document, "BaseResponse_List_SelfAssessmentTemplateVO")
}

@Test
fun testAddsStableHashOnlyForDifferentShapesWithSameSemanticName() {
    val first = renameSameSemanticNameInEndpointOrder(listOf("a", "b"))
    val second = renameSameSemanticNameInEndpointOrder(listOf("b", "a"))

    assertEquals(first.document.components!!.schemas!!.keys,
        second.document.components!!.schemas!!.keys)
    assertTrue(first.document.components!!.schemas!!.keys.any {
        it.matches(Regex("SameResponse__[0-9a-f]{8}"))
    })
}

@Test
fun testNamesGeneratedCycleFromRootAndFieldPath() {
    val result = renameGeneratedCycleAt("SelfAssessmentTaskVO", "questions")

    assertTrue(result.document.components!!.schemas!!.containsKey(
        "SelfAssessmentTaskVO_questions_Item"
    ))
}

@Test
fun testKeepsLegacyNameAndWarnsWhenEndpointCannotBeMapped() {
    val result = renameWithoutMatchingEndpoint("BaseResponse_2")

    assertTrue(result.document.components!!.schemas!!.containsKey("BaseResponse_2"))
    assertTrue(result.warnings.any { it.contains("BaseResponse_2") })
}
```

Also assert that serialized schema DTOs contain no `x-java-type` or schema
origin index.

**Step 2: Run the namer test and verify it fails**

Run:

```powershell
.\gradlew.bat test --tests "com.itangcent.easyapi.channel.openapi.multidocument.OpenApiSemanticSchemaNamerTest"
```

Expected: FAIL at compilation because the namer does not exist.

**Step 3: Build the endpoint operation index**

Inside the transformer file, add small internal values rather than a new
abstraction:

```kotlin
internal data class EndpointOperationKey(
    val path: String,
    val method: HttpMethod,
)

internal data class EndpointOperationInfo(
    val controller: String?,
    val folder: String?,
    val methodName: String?,
    val responseType: String?,
)
```

Build the map once from HTTP endpoints using the same `PathNormalizer` used
for ownership validation. Pass the immutable map to the schema namer. Do not
read PSI.

**Step 4: Implement semantic type parsing**

Implement a small tokenizer in `OpenApiSemanticSchemaNamer` that:

- keeps generic nesting order;
- strips package qualifiers from identifier tokens;
- converts `<`, `>`, comma, arrays, wildcard bounds, and Kotlin/Java nested
  class separators into single underscores;
- collapses repeated underscores and trims them.

The required result is:

```text
com.acme.BaseResponse<java.util.List<com.acme.UserVO>>
-> BaseResponse_List_UserVO
```

Do not add a Java type parser dependency.

**Step 5: Allocate names and rewrite refs**

For every indexed operation:

1. find the formatted path and HTTP method;
2. inspect the `200` response content schema;
3. map its internal component ref to the endpoint `responseType`;
4. allocate the semantic component name;
5. clone the old component when different endpoint response types shared it;
6. rewrite that operation's response refs.

Traverse reachable schema properties to collect candidates for
`GeneratedSchemaN`. Add property names to the path, `Item` for array items,
and `Value` for additional properties. When several roots reach the same
component, select the lexicographically first candidate.

Allocate plain semantic names first. Only when the same semantic name maps to
different canonical structures, append:

```kotlin
"__" + sha256(canonicalStructure).take(8)
```

Canonicalization must sort map keys, sort `required`, follow refs with a
stable cycle marker, and omit `description`, `example`, and
`xEnumDescriptions`. Rewrite the complete component graph after final names
are known.

Return:

```kotlin
internal data class SchemaRenameResult(
    val document: OpenApiDocument,
    val warnings: List<String>,
)
```

Unmapped components retain their old names. Add one warning for unresolved
legacy collision/generated names; do not guess.

**Step 6: Apply naming before physical splitting**

At the start of `transform`, run the namer against the full document and then
perform the existing paths/schema split against the renamed document. Merge
namer warnings with ownership/split warnings while preserving insertion
order and removing duplicates.

**Step 7: Run naming and transformer tests**

Run:

```powershell
.\gradlew.bat test `
  --tests "com.itangcent.easyapi.channel.openapi.multidocument.OpenApiSemanticSchemaNamerTest" `
  --tests "com.itangcent.easyapi.channel.openapi.multidocument.OpenApiMultiDocumentTransformerTest"
```

Expected: all tests PASS.

**Step 8: Commit**

Stage the namer, transformer, and their tests. Commit with:

```text
enhance(openapi): Add semantic schema names
```

### Task 3: Add the independent channel facade

**Files:**

- Create: `src/main/kotlin/com/itangcent/easyapi/channel/openapi/multidocument/OpenApiMultiDocumentChannel.kt`
- Create: `src/test/kotlin/com/itangcent/easyapi/channel/openapi/multidocument/OpenApiMultiDocumentChannelTest.kt`

**Step 1: Write failing channel tests**

Use the action-mock/light-fixture pattern selected by `write-test-case`. Test
the channel contract and one delegate call:

```kotlin
fun testChannelContract() {
    val channel = OpenApiMultiDocumentChannel(mock())

    assertEquals("openapi-multi", channel.id)
    assertEquals("OpenAPI Multi-Document (Beta)", channel.displayName)
    assertFalse(channel.enabledByDefault)
    assertTrue(channel.exposeAsAction)
    assertEquals("Export to OpenAPI Multi-Document", channel.actionText)
    assertNull(channel.settingsType)
    assertNull(channel.createSettingsPanel(project))
    assertTrue(channel.createOptionsPanel(project) is OpenApiOptionsPanel)
}

fun testExportDelegatesOnceAndBuildsMultiDocumentMetadata() = runTest {
    val delegate = mock<Channel>()
    whenever(delegate.export(any())).thenReturn(
        ExportResult.Success(
            count = 1,
            target = "OpenAPI",
            metadata = OpenApiExportMetadata(document(), OpenApiOutputFormat.YAML, "unused")
        )
    )
    val channel = OpenApiMultiDocumentChannel(delegate)

    val result = channel.export(context(endpoint("/users")))

    verify(delegate, times(1)).export(check {
        assertEquals("openapi", it.channelId)
    })
    assertTrue(result is ExportResult.Success)
    assertTrue((result as ExportResult.Success).metadata is OpenApiMultiDocumentExportMetadata)
}
```

Add tests that `ExportResult.Error` and `ExportResult.Cancelled` pass through,
and that foreign/missing delegate metadata produces an explicit error.

**Step 2: Run the channel test and verify it fails**

Run:

```powershell
.\gradlew.bat test --tests "com.itangcent.easyapi.channel.openapi.multidocument.OpenApiMultiDocumentChannelTest"
```

Expected: FAIL at compilation because the channel does not exist.

**Step 3: Implement the channel contract**

Use an explicit public no-arg constructor for the IntelliJ EP and an internal
test constructor; reuse the existing `Channel` interface as the delegate seam:

```kotlin
class OpenApiMultiDocumentChannel : Channel, IdeaLog {
    private val delegate: Channel

    constructor() {
        delegate = OpenApiChannel()
    }

    internal constructor(delegate: Channel) {
        this.delegate = delegate
    }

    override val id = "openapi-multi"
    override val displayName = "OpenAPI Multi-Document (Beta)"
    override val supportsGrpc = false
    override val exposeAsAction = true
    override val actionText = "Export to OpenAPI Multi-Document"
    override val enabledByDefault = false
    override val beta = true

    override fun createOptionsPanel(project: Project): ChannelOptionsPanel =
        OpenApiOptionsPanel(project)
}
```

Do not add a settings type, settings panel, rule-key copy, or config type.

**Step 4: Delegate and transform exactly once**

In `export`:

1. construct `OpenApiMultiDocumentTransformer` before formatting so ownership
   conflicts fail before the original formatter merges operations;
2. call `delegate.export(context.withChannel("openapi", context.channelConfig))`
   exactly once;
3. return non-success results unchanged;
4. require `OpenApiExportMetadata` on success;
5. transform `metadata.document` using the resolved `outputFormat`;
6. serialize root and additional DTOs in memory;
7. return `OpenApiMultiDocumentExportMetadata` with counts and warnings.

Keep the metadata data class in this file:

```kotlin
internal data class OpenApiMultiDocumentExportMetadata(
    val outputFormat: OpenApiOutputFormat,
    val content: String,
    val additionalFiles: LinkedHashMap<String, String>,
    val pathFragmentCount: Int,
    val schemaCount: Int,
    val unresolvedPathCount: Int,
    val warnings: List<String>,
) : ExportMetadata
```

Add one private serializer object in the same file. Use the already-installed
Gson/Jackson YAML configuration matching `OpenApiSerializer`; accept `Any`
because root, paths, and schemas use different local DTOs. Do not change the
original serializer.

**Step 5: Run channel and transformer tests**

Run:

```powershell
.\gradlew.bat test `
  --tests "com.itangcent.easyapi.channel.openapi.multidocument.*"
```

Expected: all tests PASS.

**Step 6: Commit**

Stage the new channel and test. Commit with:

```text
feat(openapi): Add multi-document channel
```

### Task 4: Move safe directory writing to the new channel

**Files:**

- Modify: `src/main/kotlin/com/itangcent/easyapi/channel/openapi/multidocument/OpenApiMultiDocumentChannel.kt`
- Modify: `src/test/kotlin/com/itangcent/easyapi/channel/openapi/multidocument/OpenApiMultiDocumentChannelTest.kt`
- Reference: `src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiChannel.kt`
- Reference: `src/test/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiChannelTest.kt`

**Step 1: Move the existing failing-first writer tests**

Move only the current multi-document handler tests from `OpenApiChannelTest`
to `OpenApiMultiDocumentChannelTest`. Preserve the existing cases for:

- YAML/JSON root filenames;
- one overwrite confirmation for all current targets;
- declined overwrite leaving all targets untouched;
- stale unrelated files remaining untouched;
- path traversal, absolute paths, duplicate normalized targets, root
  collisions, Windows backslash traversal, and symlink escape;
- per-file temporary replacement and temporary-file cleanup;
- same-directory locking, different-directory concurrency, and symlink aliases;
- warning dialog content;
- foreign metadata returning `false`.

Run the test before moving production writer code.

**Step 2: Verify writer tests fail against the new channel**

Run:

```powershell
.\gradlew.bat test --tests "com.itangcent.easyapi.channel.openapi.multidocument.OpenApiMultiDocumentChannelTest"
```

Expected: FAIL because the new channel has no `handleResult` implementation.

**Step 3: Move the existing writer implementation**

Move the validated multi-document methods from `OpenApiChannel` into
`OpenApiMultiDocumentChannel` with names unchanged where practical:

```text
handleMultiDocumentResult
withMultiDocumentDirectoryLock
resolveTargetDirectory
resolveMultiDocumentTargets
validateMultiDocumentTargets
resolveFutureRealPath
writeOutputFile
defaultFileName
```

`handleResult` should return `false` for metadata other than
`OpenApiMultiDocumentExportMetadata`. Keep:

- one canonical-directory mutex per process;
- target validation before any write;
- one overwrite confirmation;
- temporary sibling files plus atomic move fallback;
- target plus throwable logging on failure;
- warning/success dialogs on the Swing dispatcher;
- background dispatcher for filesystem work.

Do not add generated-file cleanup, a manifest, rollback journal, or another
writer abstraction.

**Step 4: Run writer and transformer tests**

Run:

```powershell
.\gradlew.bat test --tests "com.itangcent.easyapi.channel.openapi.multidocument.*"
```

Expected: all tests PASS.

**Step 5: Commit**

Stage the channel and its tests. Commit with:

```text
feat(openapi): Write multi-document exports
```

### Task 5: Restore the original channel and register the new one

**Files:**

- Modify: `src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiChannel.kt`
- Modify: `src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiConfig.kt`
- Modify: `src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiDocument.kt`
- Modify: `src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiExportMetadata.kt`
- Modify: `src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiOptionsPanel.kt`
- Modify: `src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiSerializer.kt`
- Delete: `src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiMultiDocumentSplitter.kt`
- Modify: corresponding original tests under `src/test/kotlin/com/itangcent/easyapi/channel/openapi/`
- Delete: `src/test/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiMultiDocumentSplitterTest.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Modify: `src/test/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiChannelRegistrationTest.kt`

**Step 1: Add the failing registration test**

Extend the registration test:

```kotlin
fun testMultiDocumentChannelIsRegisteredAndDefaultOff() {
    val channel = ChannelRegistry.getInstance(project).getChannel("openapi-multi")

    assertNotNull(channel)
    assertTrue(channel is OpenApiMultiDocumentChannel)
    assertFalse(channel!!.enabledByDefault)
    assertTrue(channel.exposeAsAction)
}
```

Also assert that the original `openapi` channel still has its original id,
display name, action, settings type, and options panel.

**Step 2: Run the registration test and verify it fails**

Run:

```powershell
.\gradlew.bat test --tests "com.itangcent.easyapi.channel.openapi.OpenApiChannelRegistrationTest"
```

Expected: FAIL because `plugin.xml` does not register `openapi-multi`.

**Step 3: Restore the six original production files**

Use commit `d65d1796` as the branch-local single-file baseline while applying
edits through `apply_patch`. Restore these exact behaviors:

- `OpenApiConfig` contains only `outputFormat`; remove `OpenApiDocumentMode`.
- `OpenApiOptionsPanel` contains only JSON/YAML controls.
- `PathItemObject` contains HTTP methods only; remove its `$ref` property.
- `OpenApiExportMetadata` contains only `document`, `outputFormat`, `content`.
- `OpenApiSerializer.toJson/toYaml` accept `OpenApiDocument`.
- `OpenApiChannel.export` always returns one document and
  `handleResult` always writes one file.

Remove every multi-document branch, helper, import, KDoc statement, and lock
from `OpenApiChannel`. Delete the old splitter after the new transformer tests
cover its behavior.

Restore the original test expectations from the same baseline. Multi-document
assertions now belong only to the new package tests.

**Step 4: Register the new channel**

Add exactly one EP entry after the original OpenAPI channel:

```xml
<channel implementation="com.itangcent.easyapi.channel.openapi.multidocument.OpenApiMultiDocumentChannel"/>
```

Do not add an explicit Action declaration; `ChannelQuickActionGroup` derives
it from `exposeAsAction`.

**Step 5: Prove original files match the feature baseline**

Run:

```powershell
git diff --exit-code d65d1796 -- `
  src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiChannel.kt `
  src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiConfig.kt `
  src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiDocument.kt `
  src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiExportMetadata.kt `
  src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiOptionsPanel.kt `
  src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiSerializer.kt
```

Expected: exit code 0 and no diff.

Repeat the comparison for the corresponding original test files after their
multi-document cases are moved.

**Step 6: Run original and new OpenAPI tests**

Run:

```powershell
.\gradlew.bat test --tests "com.itangcent.easyapi.channel.openapi.*"
```

Expected: original single-file and new multi-document tests PASS.

**Step 7: Commit**

Stage only the restored original files/tests, deleted splitter files,
`plugin.xml`, and registration test. Inspect `git diff --cached` to ensure no
unrelated changes. Commit with:

```text
refactor(openapi): Restore single-file channel
```

### Task 6: Document the separate export choice

**Files:**

- Modify: `README.md`
- Reference: `docs/plans/2026-08-03-openapi-multi-document-channel-isolation-design.md`

**Step 1: Update user documentation**

Replace the current “document mode” description with two channels:

```text
OpenAPI (Beta)
  - existing single-file JSON/YAML export

OpenAPI Multi-Document (Beta)
  - independently enabled, default off
  - one Paths file per Controller
  - shared schemas/schemas.yaml or schemas.json
  - semantic schema names; collision-only stable short hash
```

Document the three multi-document-only extensions and the conservative
no-cleanup behavior. Do not advertise package/tag/schema grouping or a source
index.

**Step 2: Check documentation and registration text**

Run:

```powershell
rg -n "OpenAPI Multi-Document|openapi-multi|x-java-controller|x-easyapi-folder|x-easyapi-unresolved" README.md src/main/resources/META-INF/plugin.xml
git diff --check
```

Expected: the channel name/id and all three extensions are documented, and no
whitespace errors are reported.

**Step 3: Commit**

Stage `README.md` and commit with:

```text
docs(openapi): Document multi-document channel
```

### Task 7: Verify packaging and configure upstream tracking

**Files:**

- No source changes expected.
- Local Git config only: add the `upstream` remote if absent.

**Step 1: Run focused feature tests**

Run:

```powershell
.\gradlew.bat test `
  --tests "com.itangcent.easyapi.channel.openapi.*" `
  --tests "com.itangcent.easyapi.core.ide.dialog.ExportDialogTest" `
  --tests "com.itangcent.easyapi.core.export.ExportOrchestratorTest"
```

Expected: PASS.

**Step 2: Build the distributable plugin**

Run:

```powershell
.\gradlew.bat buildPlugin
```

Expected: BUILD SUCCESSFUL and a plugin ZIP under
`build/distributions/`.

**Step 3: Run static repository checks**

Run:

```powershell
git diff --check
git status --short
git diff --exit-code d65d1796 -- `
  src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiChannel.kt `
  src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiConfig.kt `
  src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiDocument.kt `
  src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiExportMetadata.kt `
  src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiOptionsPanel.kt `
  src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiSerializer.kt
```

Expected: no whitespace errors, no uncommitted implementation files, and no
diff in the six original OpenAPI production files.

**Step 4: Record the known full-suite baseline limitation**

Optionally rerun `test` once. If the only failure remains
`DefaultRepositoriesTest.testGradleCachePath`, report it as the known
case-sensitive environment assertion and do not change it in this feature.
Any additional failure is a blocker and must be investigated before pushing.

**Step 5: Configure and fetch upstream without rewriting history**

Run:

```powershell
if (-not (git remote get-url upstream 2>$null)) {
    git remote add upstream https://github.com/tangcent/easy-yapi.git
}
git fetch upstream
git remote -v
git rev-list --left-right --count HEAD...upstream/master
```

Expected: `origin` remains `XiaoDaoJiang/easy-yapi`, `upstream` points to
`tangcent/easy-yapi`, and divergence is reported. Do not rebase or force-push
in this task; history rewriting requires a separate explicit confirmation
after reviewing the divergence.

**Step 6: Review final commits**

Run:

```powershell
git log --oneline --decorate origin/codex/openapi-multi-document..HEAD
git diff --stat origin/codex/openapi-multi-document...HEAD
```

Expected: only the approved design/plan, new isolated channel package,
registration/documentation, original-file restoration, and tests are present.
