# OpenAPI Controller-Grouped Multi-Document Export Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在保留现有 OpenAPI 单文件导出的前提下，新增按 Controller 拆分 Paths、集中保存 Schemas 的 JSON/YAML 多文档导出模式。

**Architecture:** 继续只运行一次 `OpenApiFormatter` 和一次 `openapi.format.after`。多文档模式在格式化前根据 `ApiEndpoint.className` 建立并校验 path 所有权，在格式化后由一个 channel-local 纯 Kotlin 拆分器生成入口文档、Controller Paths 文档和可选的 Schemas 文档；`OpenApiChannel` 只负责序列化、一次覆盖确认和临时文件替换。

**Tech Stack:** Kotlin 2.1、JDK 17、IntelliJ Platform 2023.1、Gson、Jackson YAMLMapper、JUnit 4、IntelliJ Light Fixture、Gradle。

---

## 执行约束

- 先调用仓库内 `.skills/write-test-case/SKILL.md`，然后按下面的 TDD 步骤执行。
- 建议在基于提交 `804aacce` 的独立 worktree 中执行。当前主工作区存在与本功能无关的未提交修改，禁止暂存或改写：
  - `docs/knowledge-base/usage-guide.md`
  - `skills/easy-yapi-assistant/docs/usage-guide.md`
  - `src/main/kotlin/com/itangcent/easyapi/core/ide/action/SyncListedApisAction.kt`
  - `src/main/resources/META-INF/plugin.xml`
  - `src/main/resources/docs/knowledge-base/usage-guide.md`
  - `src/test/kotlin/com/itangcent/easyapi/core/ide/action/SyncListedApisActionTest.kt`
- 不修改 `OpenApiFormatter`、`OpenApiSchemaConverter`、共享 `core/export` 模型或 `plugin.xml`。
- 单文件模式必须保持默认值、保存文件对话框、序列化内容和成功提示行为不变。
- 每次提交只使用计划中列出的精确 `git add` 路径。

### Task 1: 增加文档模式配置和导出面板选项

**Files:**
- Modify: `src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiConfig.kt:19-57`
- Modify: `src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiOptionsPanel.kt:43-88`
- Modify: `src/test/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiConfigTest.kt:24-66`
- Modify: `src/test/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiOptionsPanelTest.kt:40-139`

**Step 1: 写失败的配置测试**

在 `OpenApiConfigTest` 中新增：

```kotlin
@Test
fun `default documentMode is SINGLE_FILE`() {
    assertEquals(OpenApiDocumentMode.SINGLE_FILE, OpenApiConfig().documentMode)
}

@Test
fun `constructed config round-trips documentMode`() {
    val config = OpenApiConfig(
        outputFormat = OpenApiOutputFormat.YAML,
        documentMode = OpenApiDocumentMode.MULTI_FILE_BY_CONTROLLER,
    )

    assertEquals(OpenApiOutputFormat.YAML, config.outputFormat)
    assertEquals(OpenApiDocumentMode.MULTI_FILE_BY_CONTROLLER, config.documentMode)
}
```

把原来“只有 `outputFormat`”的反射断言改为：

```kotlin
assertEquals(
    setOf("outputFormat", "documentMode"),
    OpenApiConfig::class.memberProperties.map { it.name }.toSet(),
)
```

在 `OpenApiOptionsPanelTest` 中新增：

```kotlin
fun testDefaultBuildConfigUsesSingleFileMode() {
    val config = panel.buildConfig() as OpenApiConfig
    assertEquals(OpenApiDocumentMode.SINGLE_FILE, config.documentMode)
}

fun testBuildConfigUsesMultiFileModeWhenSelected() {
    panel.setDocumentMode(OpenApiDocumentMode.MULTI_FILE_BY_CONTROLLER)
    val config = panel.buildConfig() as OpenApiConfig
    assertEquals(OpenApiDocumentMode.MULTI_FILE_BY_CONTROLLER, config.documentMode)
}

fun testApplyConfigRestoresDocumentMode() {
    panel.applyConfig(
        OpenApiConfig(
            outputFormat = OpenApiOutputFormat.YAML,
            documentMode = OpenApiDocumentMode.MULTI_FILE_BY_CONTROLLER,
        )
    )

    val rebuilt = panel.buildConfig() as OpenApiConfig
    assertEquals(OpenApiOutputFormat.YAML, rebuilt.outputFormat)
    assertEquals(OpenApiDocumentMode.MULTI_FILE_BY_CONTROLLER, rebuilt.documentMode)
}
```

同步把 `OpenApiOptionsPanelTest` 中属性集合断言改为两个属性。

**Step 2: 运行测试确认失败**

Run:

```powershell
.\gradlew.bat test --tests "com.itangcent.easyapi.channel.openapi.OpenApiConfigTest" --tests "com.itangcent.easyapi.channel.openapi.OpenApiOptionsPanelTest"
```

Expected: FAIL，错误包含 `Unresolved reference 'OpenApiDocumentMode'` 或缺少 `documentMode`。

**Step 3: 实现最小配置和 UI**

在 `OpenApiConfig.kt` 增加：

```kotlin
enum class OpenApiDocumentMode {
    SINGLE_FILE,
    MULTI_FILE_BY_CONTROLLER,
}

data class OpenApiConfig(
    val outputFormat: OpenApiOutputFormat = OpenApiOutputFormat.ALWAYS_ASK,
    val documentMode: OpenApiDocumentMode = OpenApiDocumentMode.SINGLE_FILE,
) : ChannelConfig() {
    // companion object 保持不变
}
```

在 `OpenApiOptionsPanel` 中加入第二组单选按钮：

```kotlin
private val singleFileRadio = JRadioButton("Single file", true)
private val multiFileRadio = JRadioButton("Multiple files by Controller", false)

init {
    ButtonGroup().apply {
        add(jsonRadio)
        add(yamlRadio)
    }
    ButtonGroup().apply {
        add(singleFileRadio)
        add(multiFileRadio)
    }
}
```

把 `component` 调整为两个现有风格的 `FormBuilder` 行：

```kotlin
override val component: JComponent = FormBuilder.createFormBuilder()
    .addLabeledComponent(
        "Format:",
        JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(jsonRadio)
            add(yamlRadio)
        },
    )
    .addLabeledComponent(
        "Document:",
        JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(singleFileRadio)
            add(multiFileRadio)
        },
    )
    .addComponentFillVertically(JPanel(), 0)
    .panel
```

配置往返逻辑：

```kotlin
override fun buildConfig(): OpenApiConfig = OpenApiConfig(
    outputFormat = if (yamlRadio.isSelected) OpenApiOutputFormat.YAML
    else OpenApiOutputFormat.JSON,
    documentMode = if (multiFileRadio.isSelected) {
        OpenApiDocumentMode.MULTI_FILE_BY_CONTROLLER
    } else {
        OpenApiDocumentMode.SINGLE_FILE
    },
)

fun applyConfig(cfg: OpenApiConfig) {
    jsonRadio.isSelected = cfg.outputFormat != OpenApiOutputFormat.YAML
    yamlRadio.isSelected = cfg.outputFormat == OpenApiOutputFormat.YAML
    singleFileRadio.isSelected = cfg.documentMode == OpenApiDocumentMode.SINGLE_FILE
    multiFileRadio.isSelected =
        cfg.documentMode == OpenApiDocumentMode.MULTI_FILE_BY_CONTROLLER
}

internal fun setDocumentMode(mode: OpenApiDocumentMode) {
    applyConfig(buildConfig().copy(documentMode = mode))
}
```

更新这两个类的 KDoc，删除“配置只有一个属性”“面板只提供两项格式选择”等过期描述。

**Step 4: 运行测试确认通过**

Run: 同 Step 2。

Expected: PASS，Gradle 输出 `BUILD SUCCESSFUL`。

**Step 5: 提交**

```powershell
git add src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiConfig.kt src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiOptionsPanel.kt src/test/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiConfigTest.kt src/test/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiOptionsPanelTest.kt
git commit -m "feat(openapi): add multi-document mode option"
```

### Task 2: 让现有 OpenAPI 模型和序列化器支持引用文档

**Files:**
- Modify: `src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiDocument.kt:51-79`
- Modify: `src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiSerializer.kt:49-53`
- Modify: `src/test/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiDocumentTest.kt:57-94`
- Modify: `src/test/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiSerializerTest.kt`

**Step 1: 写失败的 `$ref` 和通用序列化测试**

在 `OpenApiDocumentTest` 中新增：

```kotlin
@Test
fun pathItemRefSerializesAsDollarRef() {
    val json = gson.toJson(
        PathItemObject(`$ref` = "./paths/UserController.yaml#/paths/~1users")
    )
    val parsed = JsonParser.parseString(json).asJsonObject

    assertEquals(
        "./paths/UserController.yaml#/paths/~1users",
        parsed.get("\$ref").asString,
    )
    assertFalse(parsed.has("ref"))
}
```

在 `OpenApiSerializerTest` 中新增一个仅供测试的简单对象，并验证 JSON/YAML 都能序列化非根文档对象：

```kotlin
private data class FragmentFixture(
    val paths: LinkedHashMap<String, PathItemObject>,
)

@Test
fun `serializes path fragments in JSON and YAML`() {
    val fragment = FragmentFixture(
        paths = linkedMapOf("/users" to PathItemObject(get = operation("listUsers")))
    )

    assertTrue(OpenApiSerializer.toJson(fragment).contains("\"/users\""))
    assertTrue(OpenApiSerializer.toYaml(fragment).contains("/users:"))
}
```

复用该测试文件已有的 `OperationObject` 构造方式；不要新增 golden 文件。

**Step 2: 运行测试确认失败**

Run:

```powershell
.\gradlew.bat test --tests "com.itangcent.easyapi.channel.openapi.OpenApiDocumentTest" --tests "com.itangcent.easyapi.channel.openapi.OpenApiSerializerTest"
```

Expected: FAIL，`PathItemObject` 没有 `$ref`，且 `toJson`/`toYaml` 只接受 `OpenApiDocument`。

**Step 3: 实现最小模型变化**

在 `PathItemObject` 的第一个字段增加与 `SchemaObject` 相同的双序列化注解：

```kotlin
data class PathItemObject(
    @SerializedName("\$ref")
    @get:JsonProperty("\$ref")
    val `$ref`: String? = null,
    val get: OperationObject? = null,
    val post: OperationObject? = null,
    val put: OperationObject? = null,
    val delete: OperationObject? = null,
    val patch: OperationObject? = null,
    val head: OperationObject? = null,
    val options: OperationObject? = null,
)
```

`withMethod` 继续使用 `copy`，会自然保留 `$ref`，无需新增分支。

把序列化器参数从根文档收窄类型改为任意 channel-local 输出对象：

```kotlin
fun toJson(value: Any): String = gson.toJson(value)

fun toYaml(value: Any): String = yamlMapper.writeValueAsString(value)
```

不新增 mapper、不新增依赖，也不改变现有 JSON/YAML 配置。

**Step 4: 运行测试确认通过**

Run: 同 Step 2。

Expected: PASS，现有 serializer golden tests 也保持通过。

**Step 5: 提交**

```powershell
git add src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiDocument.kt src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiSerializer.kt src/test/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiDocumentTest.kt src/test/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiSerializerTest.kt
git commit -m "feat(openapi): serialize external path references"
```

### Task 3: 建立 path 所有权并拒绝跨 Controller 冲突

**Files:**
- Create: `src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiMultiDocumentSplitter.kt`
- Create: `src/test/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiMultiDocumentSplitterTest.kt`

**Step 1: 写失败的纯单元测试**

创建 plain JUnit 4 测试类，不使用 IDEA fixture：

```kotlin
class OpenApiMultiDocumentSplitterTest {

    @Test
    fun `rejects one normalized path owned by different controllers`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            OpenApiMultiDocumentSplitter(
                listOf(
                    endpoint(
                        path = "/users/{id}",
                        method = HttpMethod.GET,
                        className = "com.acme.UserController",
                    ),
                    endpoint(
                        path = "users/:id",
                        method = HttpMethod.POST,
                        className = "com.acme.AdminController",
                    ),
                )
            )
        }

        assertTrue(exception.message.orEmpty().contains("/users/{id}"))
        assertTrue(exception.message.orEmpty().contains("GET com.acme.UserController"))
        assertTrue(exception.message.orEmpty().contains("POST com.acme.AdminController"))
    }

    @Test
    fun `accepts multiple methods on one path from the same controller`() {
        OpenApiMultiDocumentSplitter(
            listOf(
                endpoint("/users/{id}", HttpMethod.GET, "com.acme.UserController"),
                endpoint("/users/{id}", HttpMethod.DELETE, "com.acme.UserController"),
            )
        )
    }

    private fun endpoint(
        path: String,
        method: HttpMethod,
        className: String? = null,
        folder: String? = null,
    ): ApiEndpoint = ApiEndpoint(
        className = className,
        folder = folder,
        metadata = httpMetadata(path = path, method = method),
    )
}
```

**Step 2: 运行测试确认失败**

Run:

```powershell
.\gradlew.bat test --tests "com.itangcent.easyapi.channel.openapi.OpenApiMultiDocumentSplitterTest"
```

Expected: FAIL，缺少 `OpenApiMultiDocumentSplitter`。

**Step 3: 实现所有权索引**

在新文件中定义纯 Kotlin 拆分器的最小骨架：

```kotlin
internal class OpenApiMultiDocumentSplitter(
    endpoints: List<ApiEndpoint>,
) {
    private val ownerByPath: LinkedHashMap<String, PathOwner> =
        resolveOwners(endpoints)

    private fun resolveOwners(
        endpoints: List<ApiEndpoint>,
    ): LinkedHashMap<String, PathOwner> {
        val observations = linkedMapOf<String, MutableList<PathObservation>>()

        endpoints.forEach { endpoint ->
            val metadata = endpoint.httpMetadata ?: return@forEach
            val path = PathNormalizer.normalize(metadata.path) ?: return@forEach
            observations.getOrPut(path) { mutableListOf() }
                .add(
                    PathObservation(
                        method = metadata.method,
                        owner = ownerOf(endpoint),
                    )
                )
        }

        val conflicts = observations.filterValues { entries ->
            entries.map { it.owner }.distinct().size > 1
        }
        require(conflicts.isEmpty()) {
            buildString {
                appendLine("OpenAPI multi-document path ownership conflict:")
                conflicts.forEach { (path, entries) ->
                    appendLine(path)
                    entries.forEach {
                        appendLine("  ${it.method} ${it.owner.displayName}")
                    }
                }
            }.trimEnd()
        }

        return observations.mapValuesTo(linkedMapOf()) { (_, entries) ->
            entries.first().owner
        }
    }

    private fun ownerOf(endpoint: ApiEndpoint): PathOwner {
        endpoint.className?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return PathOwner(OwnerKind.CONTROLLER, it)
        }
        endpoint.folder?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return PathOwner(OwnerKind.FOLDER, it)
        }
        return UNRESOLVED_OWNER
    }

    private enum class OwnerKind { CONTROLLER, FOLDER, UNRESOLVED }

    private data class PathOwner(
        val kind: OwnerKind,
        val value: String,
    ) {
        val displayName: String
            get() = when (kind) {
                OwnerKind.CONTROLLER -> value
                OwnerKind.FOLDER -> "folder:$value"
                OwnerKind.UNRESOLVED -> "Unresolved"
            }
    }

    private data class PathObservation(
        val method: HttpMethod,
        val owner: PathOwner,
    )

    private companion object {
        val UNRESOLVED_OWNER = PathOwner(OwnerKind.UNRESOLVED, "Unresolved")
    }
}
```

导入 `ApiEndpoint`、`HttpMethod` 和 `httpMetadata`。构造器完成冲突校验，所以 `OpenApiChannel` 可在调用 formatter 前创建它并提前失败。

**Step 4: 运行测试确认通过**

Run: 同 Step 2。

Expected: PASS。

**Step 5: 提交**

```powershell
git add src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiMultiDocumentSplitter.kt src/test/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiMultiDocumentSplitterTest.kt
git commit -m "feat(openapi): validate controller path ownership"
```

### Task 4: 拆分入口、Paths 和 Schemas 文档并重写引用

**Files:**
- Modify: `src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiMultiDocumentSplitter.kt`
- Modify: `src/test/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiMultiDocumentSplitterTest.kt`

**Step 1: 写失败的拆分行为测试**

在纯单测中按以下行为逐个添加测试：

```kotlin
@Test
fun `splits one paths file per controller and keeps root paths`() {
    val splitter = OpenApiMultiDocumentSplitter(
        listOf(
            endpoint("/users", HttpMethod.GET, "com.acme.UserController"),
            endpoint("/orders", HttpMethod.GET, "com.acme.OrderController"),
        )
    )

    val result = splitter.split(
        document(
            "/users" to pathItem("listUsers"),
            "/orders" to pathItem("listOrders"),
        ),
        OpenApiOutputFormat.YAML,
    )

    assertEquals(
        setOf("paths/UserController.yaml", "paths/OrderController.yaml"),
        result.additionalDocuments.keys,
    )
    assertEquals(
        "./paths/UserController.yaml#/paths/~1users",
        result.rootDocument.paths.getValue("/users").`$ref`,
    )
    assertEquals(
        "./paths/OrderController.yaml#/paths/~1orders",
        result.rootDocument.paths.getValue("/orders").`$ref`,
    )
}

@Test
fun `escapes slash and tilde in path JSON pointers`() {
    val path = "/users/~draft/{id}"
    val result = OpenApiMultiDocumentSplitter(
        listOf(endpoint(path, HttpMethod.GET, "com.acme.UserController"))
    ).split(document(path to pathItem("getDraft")), OpenApiOutputFormat.JSON)

    assertEquals(
        "./paths/UserController.json#/paths/~1users~1~0draft~1{id}",
        result.rootDocument.paths.getValue(path).`$ref`,
    )
}

@Test
fun `uses shortest unique package suffix for duplicate simple class names`() {
    val result = OpenApiMultiDocumentSplitter(
        listOf(
            endpoint("/patients", HttpMethod.GET, "com.acme.patient.UserController"),
            endpoint("/admins", HttpMethod.GET, "com.acme.admin.UserController"),
        )
    ).split(
        document(
            "/patients" to pathItem("listPatients"),
            "/admins" to pathItem("listAdmins"),
        ),
        OpenApiOutputFormat.YAML,
    )

    assertEquals(
        setOf(
            "paths/patient-UserController.yaml",
            "paths/admin-UserController.yaml",
        ),
        result.additionalDocuments.keys,
    )
}

@Test
fun `falls back to folder and unresolved fragments`() {
    val result = OpenApiMultiDocumentSplitter(
        listOf(
            endpoint("/folder", HttpMethod.GET, folder = "用户管理"),
            endpoint("/unknown", HttpMethod.GET),
        )
    ).split(
        document(
            "/folder" to pathItem("folderApi"),
            "/unknown" to pathItem("unknownApi"),
        ),
        OpenApiOutputFormat.YAML,
    )

    val folder = result.additionalDocuments
        .getValue("paths/用户管理.yaml") as OpenApiPathsFragment
    val unresolved = result.additionalDocuments
        .getValue("paths/Unresolved.yaml") as OpenApiPathsFragment

    assertEquals("用户管理", folder.easyApiFolder)
    assertEquals(true, unresolved.easyApiUnresolved)
    assertEquals(1, result.unresolvedPathCount)
    assertTrue(result.warnings.isNotEmpty())
}

@Test
fun `places paths added after formatting into unresolved fragment`() {
    val pathItem = pathItem("generated")
    val formatted = document("/generated" to pathItem)
    val result = OpenApiMultiDocumentSplitter(emptyList())
        .split(formatted, OpenApiOutputFormat.YAML)

    assertTrue(result.additionalDocuments.containsKey("paths/Unresolved.yaml"))
    assertEquals(
        "./paths/Unresolved.yaml#/paths/~1generated",
        result.rootDocument.paths.getValue("/generated").`$ref`,
    )
}

@Test
fun `extracts schemas and rebases schema refs by document location`() {
    val responseSchema = SchemaObject(`$ref` = "#/components/schemas/User")
    val operation = OperationObject(
        operationId = "getUser",
        responses = linkedMapOf(
            "200" to ResponseObject(
                description = "OK",
                content = linkedMapOf(
                    "application/json" to MediaTypeObject(responseSchema)
                ),
            )
        ),
    )
    val document = OpenApiDocument(
        info = InfoObject("API", "1.0.0"),
        paths = linkedMapOf("/users" to PathItemObject(get = operation)),
        components = ComponentsObject(
            schemas = linkedMapOf(
                "User" to SchemaObject(
                    type = "object",
                    properties = linkedMapOf(
                        "manager" to SchemaObject(
                            `$ref` = "#/components/schemas/User"
                        )
                    ),
                )
            )
        ),
    )

    val result = OpenApiMultiDocumentSplitter(
        listOf(endpoint("/users", HttpMethod.GET, "com.acme.UserController"))
    ).split(document, OpenApiOutputFormat.YAML)

    assertEquals(
        "./schemas/schemas.yaml#/components/schemas/User",
        result.rootDocument.components!!.schemas!!.getValue("User").`$ref`,
    )
    val pathsDoc = result.additionalDocuments
        .getValue("paths/UserController.yaml") as OpenApiPathsFragment
    val fragmentRef = pathsDoc.paths.getValue("/users").get!!
        .responses.getValue("200").content!!
        .getValue("application/json").schema.`$ref`
    assertEquals(
        "../schemas/schemas.yaml#/components/schemas/User",
        fragmentRef,
    )
    val schemasDoc = result.additionalDocuments
        .getValue("schemas/schemas.yaml") as OpenApiSchemasDocument
    assertEquals(
        "#/components/schemas/User",
        schemasDoc.components.schemas!!.getValue("User")
            .properties!!.getValue("manager").`$ref`,
    )
}
```

测试辅助方法应完整放在测试类末尾：

```kotlin
private fun document(
    vararg paths: Pair<String, PathItemObject>,
): OpenApiDocument = OpenApiDocument(
    info = InfoObject("API", "1.0.0"),
    paths = linkedMapOf(*paths),
)

private fun pathItem(operationId: String): PathItemObject =
    PathItemObject(
        get = OperationObject(
            operationId = operationId,
            responses = linkedMapOf("200" to ResponseObject("OK")),
        )
    )
```

再添加文件名清洗测试：Windows 保留名 `CON`、字符 `<>:"/\|?*`、结尾点和空格都不能出现在最终相对路径中。

**Step 2: 运行测试确认失败**

Run:

```powershell
.\gradlew.bat test --tests "com.itangcent.easyapi.channel.openapi.OpenApiMultiDocumentSplitterTest"
```

Expected: FAIL，缺少 `split`、输出 DTO 和引用重写。

**Step 3: 增加最小输出 DTO**

放在 `OpenApiMultiDocumentSplitter.kt` 内，不创建额外文件：

```kotlin
@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class OpenApiPathsFragment(
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

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class OpenApiSchemasDocument(
    val components: ComponentsObject,
)

internal data class OpenApiMultiDocument(
    val rootDocument: OpenApiDocument,
    val additionalDocuments: LinkedHashMap<String, Any>,
    val pathFragmentCount: Int,
    val schemaCount: Int,
    val unresolvedPathCount: Int,
    val warnings: List<String>,
)
```

**Step 4: 实现拆分主流程**

在拆分器中增加：

```kotlin
internal fun split(
    document: OpenApiDocument,
    outputFormat: OpenApiOutputFormat,
): OpenApiMultiDocument {
    val extension = when (outputFormat) {
        OpenApiOutputFormat.JSON -> "json"
        OpenApiOutputFormat.YAML -> "yaml"
        OpenApiOutputFormat.ALWAYS_ASK ->
            error("ALWAYS_ASK must be resolved before document splitting")
    }
    val actualOwners = document.paths.keys.map { ownerByPath[it] ?: UNRESOLVED_OWNER }
    val fileStemByOwner = assignFileStems(actualOwners.distinct())
    val fragments = linkedMapOf<PathOwner, OpenApiPathsFragment>()
    val rootPaths = linkedMapOf<String, PathItemObject>()
    val warnings = linkedSetOf<String>()
    var unresolvedPathCount = 0

    document.paths.forEach { (path, pathItem) ->
        val hasOriginalOwner = ownerByPath.containsKey(path)
        val owner = ownerByPath[path] ?: UNRESOLVED_OWNER
        when (owner.kind) {
            OwnerKind.FOLDER ->
                warnings += "Path '$path' used folder fallback '${owner.value}'."
            OwnerKind.UNRESOLVED -> {
                unresolvedPathCount++
                warnings += if (hasOriginalOwner) {
                    "Path '$path' has no Controller or folder and was written to Unresolved."
                } else {
                    "Path '$path' has no endpoint owner after formatting and was written to Unresolved."
                }
            }
            OwnerKind.CONTROLLER -> Unit
        }

        val stem = fileStemByOwner.getValue(owner)
        val relativeFile = "paths/$stem.$extension"
        val fragment = fragments.getOrPut(owner) {
            fragmentFor(owner)
        }
        fragment.paths[path] = rewritePathItemSchemaRefs(
            pathItem,
            "../schemas/schemas.$extension#/components/schemas/",
        )
        rootPaths[path] = PathItemObject(
            `$ref` = "./$relativeFile#/paths/${escapePointerToken(path)}"
        )
    }

    val schemas = document.components?.schemas
    val rootComponents = schemas?.takeIf { it.isNotEmpty() }?.let {
        ComponentsObject(
            schemas = it.keys.associateWithTo(linkedMapOf()) { schemaName ->
                SchemaObject(
                    `$ref` = "./schemas/schemas.$extension#/components/schemas/" +
                        escapePointerToken(schemaName)
                )
            }
        )
    }
    val additionalDocuments = linkedMapOf<String, Any>()
    fragments.forEach { (owner, fragment) ->
        additionalDocuments[
            "paths/${fileStemByOwner.getValue(owner)}.$extension"
        ] = fragment
    }
    schemas?.takeIf { it.isNotEmpty() }?.let {
        additionalDocuments["schemas/schemas.$extension"] =
            OpenApiSchemasDocument(ComponentsObject(schemas = it))
    }

    return OpenApiMultiDocument(
        rootDocument = document.copy(
            paths = rootPaths,
            components = rootComponents,
        ),
        additionalDocuments = additionalDocuments,
        pathFragmentCount = fragments.size,
        schemaCount = schemas?.size ?: 0,
        unresolvedPathCount = unresolvedPathCount,
        warnings = warnings.toList(),
    )
}
```

`fragmentFor` 必须只设置对应标识：

```kotlin
private fun fragmentFor(owner: PathOwner): OpenApiPathsFragment = when (owner.kind) {
    OwnerKind.CONTROLLER -> OpenApiPathsFragment(
        javaController = owner.value,
        paths = linkedMapOf(),
    )
    OwnerKind.FOLDER -> OpenApiPathsFragment(
        easyApiFolder = owner.value,
        paths = linkedMapOf(),
    )
    OwnerKind.UNRESOLVED -> OpenApiPathsFragment(
        easyApiUnresolved = true,
        paths = linkedMapOf(),
    )
}
```

**Step 5: 实现文件名和 JSON Pointer 规则**

实现以下私有方法，不新增公共工具类：

```kotlin
private fun escapePointerToken(value: String): String =
    value.replace("~", "~0").replace("/", "~1")

private fun controllerStem(owner: PathOwner, packageDepth: Int): String {
    val segments = owner.value.split('.')
    val simpleName = segments.last()
    val packages = segments.dropLast(1)
    val prefix = packages.takeLast(packageDepth)
    return (prefix + simpleName).joinToString("-")
}
```

`assignFileStems` 先逐层增加发生冲突的 Controller 包名，再为清洗后仍冲突的 owner 加稳定后缀：

```kotlin
private fun assignFileStems(
    owners: List<PathOwner>,
): Map<PathOwner, String> {
    val ordered = owners.distinct().sortedWith(
        compareBy<PathOwner>({ it.kind.name }, { it.value })
    )
    val packageDepth = ordered.associateWith { 0 }.toMutableMap()

    fun candidates(): Map<PathOwner, String> = ordered.associateWith { owner ->
        sanitizeFileStem(
            when (owner.kind) {
                OwnerKind.CONTROLLER ->
                    controllerStem(owner, packageDepth.getValue(owner))
                OwnerKind.FOLDER -> owner.value
                OwnerKind.UNRESOLVED -> "Unresolved"
            }
        )
    }

    while (true) {
        val collisions = candidates().entries
            .groupBy { it.value.lowercase() }
            .values
            .filter { it.size > 1 }
        if (collisions.isEmpty()) return candidates()

        var advanced = false
        collisions.flatten().map { it.key }.distinct().forEach { owner ->
            if (owner.kind != OwnerKind.CONTROLLER) return@forEach
            val maxDepth = owner.value.split('.').size - 1
            val currentDepth = packageDepth.getValue(owner)
            if (currentDepth < maxDepth) {
                packageDepth[owner] = currentDepth + 1
                advanced = true
            }
        }
        if (!advanced) break
    }

    val baseCandidates = candidates()
    val result = linkedMapOf<PathOwner, String>()
    val used = mutableSetOf<String>()
    val finalOrder = ordered.sortedWith(
        compareBy<PathOwner>(
            { it.kind != OwnerKind.UNRESOLVED },
            { it.kind.name },
            { it.value },
        )
    )
    finalOrder.forEach { owner ->
        val base = baseCandidates.getValue(owner)
        var candidate = base
        var attempt = 0
        while (!used.add(candidate.lowercase())) {
            attempt++
            val hash = Integer.toUnsignedString(
                "${owner.kind}:${owner.value}".hashCode(),
                16,
            ).padStart(8, '0')
            val suffix = if (attempt == 1) hash else "$hash-$attempt"
            candidate = sanitizeFileStem("$base-$suffix")
        }
        result[owner] = candidate
    }
    return result
}
```

这里使用忽略大小写的冲突键以覆盖 Windows 文件系统；Unresolved 先占用固定名称，其他冲突项才追加后缀。排序和字符串 `hashCode()` 都是确定性的，同一 owner 集合不会因 endpoint 输入顺序改变文件名。

`sanitizeFileStem` 使用一个 channel-local 函数完成：

```kotlin
private fun sanitizeFileStem(raw: String): String {
    val cleaned = raw
        .replace(Regex("""[<>:"/\\|?*\u0000-\u001F]"""), "-")
        .replace(Regex("-+"), "-")
        .trim()
        .trimEnd('.', ' ')
        .ifBlank { "Unresolved" }
    val safeReserved = if (
        Regex("""(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$""").matches(cleaned)
    ) {
        "_$cleaned"
    } else {
        cleaned
    }
    if (safeReserved.length <= 120) return safeReserved
    val suffix = Integer.toUnsignedString(raw.hashCode(), 16).padStart(8, '0')
    return "${safeReserved.take(111)}-$suffix"
}
```

不使用 `UniqueFileNameUtils`：它根据目标目录现有文件追加序号，会让 `$ref` 随目录状态变化，并且不解决本次导出内部的 owner 冲突。

**Step 6: 实现递归 Schema 引用重写**

只重写以 `#/components/schemas/` 开头的内部引用；已有外部引用保持原样。完整遍历：

- `PathItemObject` 的七个 HTTP method；
- `OperationObject.parameters[].schema`；
- `OperationObject.requestBody.content[].schema`；
- `OperationObject.responses[].content[].schema`；
- `SchemaObject.properties`、`additionalProperties`、`items`。

核心 Schema 复制函数：

```kotlin
private fun rewriteSchemaRef(
    schema: SchemaObject,
    targetPrefix: String,
): SchemaObject = schema.copy(
    `$ref` = schema.`$ref`?.let { ref ->
        if (ref.startsWith(SCHEMA_REF_PREFIX)) {
            targetPrefix + ref.removePrefix(SCHEMA_REF_PREFIX)
        } else {
            ref
        }
    },
    properties = schema.properties?.mapValuesTo(linkedMapOf()) { (_, value) ->
        rewriteSchemaRef(value, targetPrefix)
    },
    additionalProperties = schema.additionalProperties?.let {
        rewriteSchemaRef(it, targetPrefix)
    },
    items = schema.items?.let {
        rewriteSchemaRef(it, targetPrefix)
    },
)

private companion object {
    const val SCHEMA_REF_PREFIX = "#/components/schemas/"
    val UNRESOLVED_OWNER = PathOwner(OwnerKind.UNRESOLVED, "Unresolved")
}
```

其余遍历函数完整实现为：

```kotlin
private fun rewritePathItemSchemaRefs(
    pathItem: PathItemObject,
    targetPrefix: String,
): PathItemObject = pathItem.copy(
    get = pathItem.get?.let { rewriteOperationSchemaRefs(it, targetPrefix) },
    post = pathItem.post?.let { rewriteOperationSchemaRefs(it, targetPrefix) },
    put = pathItem.put?.let { rewriteOperationSchemaRefs(it, targetPrefix) },
    delete = pathItem.delete?.let {
        rewriteOperationSchemaRefs(it, targetPrefix)
    },
    patch = pathItem.patch?.let {
        rewriteOperationSchemaRefs(it, targetPrefix)
    },
    head = pathItem.head?.let { rewriteOperationSchemaRefs(it, targetPrefix) },
    options = pathItem.options?.let {
        rewriteOperationSchemaRefs(it, targetPrefix)
    },
)

private fun rewriteOperationSchemaRefs(
    operation: OperationObject,
    targetPrefix: String,
): OperationObject = operation.copy(
    parameters = operation.parameters?.map { parameter ->
        parameter.copy(
            schema = rewriteSchemaRef(parameter.schema, targetPrefix)
        )
    },
    requestBody = operation.requestBody?.let { requestBody ->
        requestBody.copy(
            content = requestBody.content.mapValuesTo(linkedMapOf()) {
                (_, mediaType) ->
                mediaType.copy(
                    schema = rewriteSchemaRef(mediaType.schema, targetPrefix)
                )
            }
        )
    },
    responses = operation.responses.mapValuesTo(linkedMapOf()) {
        (_, response) ->
        response.copy(
            content = response.content?.mapValuesTo(linkedMapOf()) {
                (_, mediaType) ->
                mediaType.copy(
                    schema = rewriteSchemaRef(mediaType.schema, targetPrefix)
                )
            }
        )
    },
)
```

这些函数只替换包含 Schema 的字段，不改变 operationId、responses 顺序、examples 或 tags。

**Step 7: 运行拆分器测试确认通过**

Run: 同 Step 2。

Expected: PASS；测试输出无临时文件、PSI 或 IDEA Application 依赖。

**Step 8: 提交**

```powershell
git add src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiMultiDocumentSplitter.kt src/test/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiMultiDocumentSplitterTest.kt
git commit -m "feat(openapi): split documents by controller"
```

### Task 5: 在 OpenAPI 通道中生成多文档元数据

**Files:**
- Modify: `src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiExportMetadata.kt:5-35`
- Modify: `src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiChannel.kt:107-167`
- Modify: `src/test/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiExportMetadataTest.kt`
- Modify: `src/test/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiChannelTest.kt:151-223`

**Step 1: 写失败的通道测试**

扩展 `OpenApiChannelTest.httpEndpoint`，增加可选 `className` 和 `folder` 参数并传入 `ApiEndpoint`。

新增：

```kotlin
fun testMultiDocumentExportBuildsRootAndAdditionalFiles() = runTest {
    val context = ExportContext(
        project = project,
        endpoints = listOf(
            httpEndpoint(
                name = "List users",
                path = "/users",
                method = HttpMethod.GET,
                methodName = "listUsers",
                className = "com.acme.UserController",
            )
        ),
        channelId = "openapi",
        channelConfig = OpenApiConfig(
            outputFormat = OpenApiOutputFormat.YAML,
            documentMode = OpenApiDocumentMode.MULTI_FILE_BY_CONTROLLER,
        ),
    )

    val result = channel.export(context) as ExportResult.Success
    val metadata = result.metadata as OpenApiExportMetadata

    assertEquals(
        OpenApiDocumentMode.MULTI_FILE_BY_CONTROLLER,
        metadata.documentMode,
    )
    assertEquals(
        setOf("paths/UserController.yaml"),
        metadata.additionalFiles.keys,
    )
    assertTrue(
        metadata.content.contains(
            "./paths/UserController.yaml#/paths/~1users"
        )
    )
    assertTrue(
        metadata.additionalFiles.getValue("paths/UserController.yaml")
            .contains("x-java-controller: com.acme.UserController")
    )
    assertEquals(1, metadata.pathFragmentCount)
}

fun testMultiDocumentConflictReturnsErrorBeforeSerialization() = runTest {
    val context = ExportContext(
        project = project,
        endpoints = listOf(
            httpEndpoint(
                path = "/users",
                method = HttpMethod.GET,
                methodName = "listUsers",
                className = "com.acme.UserController",
            ),
            httpEndpoint(
                path = "/users",
                method = HttpMethod.POST,
                methodName = "createUser",
                className = "com.acme.AdminController",
            ),
        ),
        channelId = "openapi",
        channelConfig = OpenApiConfig(
            outputFormat = OpenApiOutputFormat.JSON,
            documentMode = OpenApiDocumentMode.MULTI_FILE_BY_CONTROLLER,
        ),
    )

    val result = channel.export(context)
    assertTrue(result is ExportResult.Error)
    assertTrue((result as ExportResult.Error).message.contains("/users"))
}
```

在 `OpenApiExportMetadataTest` 新增默认单文件兼容断言和多文件字段保存断言。

**Step 2: 运行测试确认失败**

Run:

```powershell
.\gradlew.bat test --tests "com.itangcent.easyapi.channel.openapi.OpenApiExportMetadataTest" --tests "com.itangcent.easyapi.channel.openapi.OpenApiChannelTest"
```

Expected: FAIL，元数据没有多文档字段，通道仍只序列化完整单文件。

**Step 3: 扩展元数据但保留兼容默认值**

```kotlin
data class OpenApiExportMetadata(
    val document: OpenApiDocument,
    val outputFormat: OpenApiOutputFormat,
    val content: String,
    val documentMode: OpenApiDocumentMode = OpenApiDocumentMode.SINGLE_FILE,
    val additionalFiles: LinkedHashMap<String, String> = linkedMapOf(),
    val pathFragmentCount: Int = 0,
    val schemaCount: Int = 0,
    val unresolvedPathCount: Int = 0,
    val warnings: List<String> = emptyList(),
) : ExportMetadata {
    // formatDisplay() 保持现状
}
```

`content` 在两种模式下始终表示入口文档内容；`document` 继续保留执行 hook 后、拆分前的完整文档。

**Step 4: 只在多文档模式创建拆分器**

在 `OpenApiChannel.export` 解析 `typed` 和 `effectiveFormat` 后、调用 `OpenApiFormatter` 前增加：

```kotlin
val splitter = if (
    typed.documentMode == OpenApiDocumentMode.MULTI_FILE_BY_CONTROLLER
) {
    try {
        OpenApiMultiDocumentSplitter(httpEndpoints)
    } catch (e: IllegalArgumentException) {
        LOG.warn("OpenAPI multi-document ownership validation failed", e)
        return ExportResult.Error(
            e.message ?: "OpenAPI multi-document ownership validation failed"
        )
    }
} else {
    null
}
```

保留原有 formatter 与 hook 调用顺序。hook 后分支：

```kotlin
if (splitter == null) {
    val content = serialize(document, effectiveFormat)
    return ExportResult.Success(
        count = httpEndpoints.size,
        target = "OpenAPI",
        metadata = OpenApiExportMetadata(
            document = document,
            outputFormat = effectiveFormat,
            content = content,
        ),
    )
}

val multi = splitter.split(document, effectiveFormat)
val additionalFiles = multi.additionalDocuments.mapValuesTo(linkedMapOf()) {
    (_, value) -> serialize(value, effectiveFormat)
}
multi.warnings.forEach { LOG.warn(it) }
return ExportResult.Success(
    count = httpEndpoints.size,
    target = "OpenAPI",
    metadata = OpenApiExportMetadata(
        document = document,
        outputFormat = effectiveFormat,
        content = serialize(multi.rootDocument, effectiveFormat),
        documentMode = OpenApiDocumentMode.MULTI_FILE_BY_CONTROLLER,
        additionalFiles = additionalFiles,
        pathFragmentCount = multi.pathFragmentCount,
        schemaCount = multi.schemaCount,
        unresolvedPathCount = multi.unresolvedPathCount,
        warnings = multi.warnings,
    ),
)
```

提取一个不含状态的私有序列化函数，替代原有 `when`：

```kotlin
private fun serialize(
    value: Any,
    format: OpenApiOutputFormat,
): String = when (format) {
    OpenApiOutputFormat.JSON -> OpenApiSerializer.toJson(value)
    OpenApiOutputFormat.YAML -> OpenApiSerializer.toYaml(value)
    OpenApiOutputFormat.ALWAYS_ASK ->
        error("ALWAYS_ASK must be resolved before serialization")
}
```

先序列化所有对象再返回 `Success`，因此任意对象序列化失败时尚未发生文件写入。

**Step 5: 运行测试确认通过**

Run: 同 Step 2。

Expected: PASS；原有单文件内容断言不变。

**Step 6: 提交**

```powershell
git add src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiExportMetadata.kt src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiChannel.kt src/test/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiExportMetadataTest.kt src/test/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiChannelTest.kt
git commit -m "feat(openapi): build multi-document export metadata"
```

### Task 6: 选择目录、一次确认并安全写入全部文档

**Files:**
- Modify: `src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiChannel.kt:295-377`
- Modify: `src/test/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiChannelTest.kt:355-403`

**Step 1: 写失败的多文件写盘测试**

新增 fixture tests：

```kotlin
fun testHandleResultWritesMultiDocumentTree() = runTest {
    val tempDir = createTempDir(prefix = "openapi-multi")
    try {
        val exportResult = channel.export(
            ExportContext(
                project = project,
                endpoints = listOf(
                    httpEndpoint(
                        path = "/users",
                        method = HttpMethod.GET,
                        methodName = "listUsers",
                        className = "com.acme.UserController",
                    )
                ),
                channelId = "openapi",
                channelConfig = OpenApiConfig(
                    outputFormat = OpenApiOutputFormat.YAML,
                    documentMode =
                        OpenApiDocumentMode.MULTI_FILE_BY_CONTROLLER,
                ),
            )
        ) as ExportResult.Success

        val handled = channel.handleResult(
            project,
            exportResult,
            ChannelConfig.FileConfig(outputDir = tempDir.absolutePath),
        )

        assertTrue(handled)
        assertTrue(File(tempDir, "openapi.yaml").isFile)
        assertTrue(File(tempDir, "paths/UserController.yaml").isFile)
        assertFalse(
            tempDir.walkTopDown().any { it.name.endsWith(".tmp") }
        )
    } finally {
        tempDir.deleteRecursively()
    }
}

fun testHandleResultCancelsAllWritesWhenOverwriteIsRejected() = runTest {
    val tempDir = createTempDir(prefix = "openapi-overwrite")
    val root = File(tempDir, "openapi.json").apply {
        writeText("keep")
    }
    try {
        TestDialogManager.setTestDialog(TestDialog { Messages.NO })
        val result = multiDocumentExportResult(
            format = OpenApiOutputFormat.JSON
        )

        var cancelled = false
        try {
            channel.handleResult(
                project,
                result,
                ChannelConfig.FileConfig(outputDir = tempDir.absolutePath),
            )
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue("Rejecting overwrite should cancel export", cancelled)
        assertEquals("keep", root.readText())
        assertFalse(File(tempDir, "paths/UserController.json").exists())
    } finally {
        tempDir.deleteRecursively()
    }
}
```

提取测试内 `multiDocumentExportResult`，只复用现有 `channel.export` 和 endpoint helper；不要 mock 私有方法。

再增加一个已有目标文件数量测试：预先创建 root 和一个 fragment，`TestDialog` 只被询问一次并返回 YES，最终两个文件都更新。

**Step 2: 运行测试确认失败**

Run:

```powershell
.\gradlew.bat test --tests "com.itangcent.easyapi.channel.openapi.OpenApiChannelTest"
```

Expected: FAIL，`handleResult` 仍只写入口文件。

**Step 3: 保留单文件分支，新增多文件分支**

`handleResult` 的开头保持元数据类型检查，然后：

```kotlin
if (metadata.documentMode == OpenApiDocumentMode.SINGLE_FILE) {
    return handleSingleFileResult(project, result, config, metadata)
}
return handleMultiFileResult(project, result, config, metadata)
```

把当前 302-318 行原样移动到 `handleSingleFileResult`，不更改其目标文件解析、写入和提示文本。

**Step 4: 复用现有目录选择器**

导入 `FileSelectHelper`，新增：

```kotlin
private suspend fun resolveTargetDirectory(
    project: Project,
    config: ChannelConfig,
): File {
    val configured = (config as? ChannelConfig.FileConfig)
        ?.outputDir
        ?.takeIf { it.isNotBlank() }
    if (configured != null) return File(configured)

    val selected = swing {
        FileSelectHelper.getInstance(project)
            .selectDirectory("Select OpenAPI Output Directory", project)
    } ?: throw CancellationException("User cancelled directory selection")
    return File(selected.path)
}
```

不在这个方法中创建目录，确保覆盖确认取消前没有文件系统写操作。

**Step 5: 构造并校验全部目标路径**

```kotlin
private fun resolveMultiFileTargets(
    rootDirectory: File,
    metadata: OpenApiExportMetadata,
): LinkedHashMap<Path, String> {
    val root = rootDirectory.toPath().toAbsolutePath().normalize()
    val result = linkedMapOf<Path, String>()

    // 先写被引用文件，最后写入口文件。
    metadata.additionalFiles.forEach { (relative, content) ->
        val target = root.resolve(relative).normalize()
        require(target.startsWith(root)) {
            "OpenAPI output path escapes selected directory: $relative"
        }
        result[target] = content
    }
    result[root.resolve(defaultFileName(metadata.outputFormat))] =
        metadata.content
    return result
}
```

相对路径由拆分器生成，但仍保留 `startsWith(root)` 边界检查，避免未来修改造成目录穿越。

**Step 6: 一次覆盖确认**

```kotlin
private suspend fun confirmOverwrite(
    project: Project,
    targets: Set<Path>,
) {
    val existingCount = background {
        targets.count { Files.exists(it) }
    }
    if (existingCount == 0) return

    val choice = swing {
        Messages.showYesNoDialog(
            project,
            "$existingCount OpenAPI output file(s) already exist. Overwrite them?",
            "Overwrite OpenAPI Files",
            Messages.getWarningIcon(),
        )
    }
    if (choice != Messages.YES) {
        throw CancellationException("User cancelled OpenAPI overwrite")
    }
}
```

只确认当前将要生成且已经存在的目标；不扫描、不删除 stale 或无关文件。

**Step 7: 使用临时同级文件替换目标**

```kotlin
private fun writeAtomically(target: Path, content: String) {
    Files.createDirectories(target.parent)
    val temporary = Files.createTempFile(
        target.parent,
        ".easyapi-openapi-",
        ".tmp",
    )
    try {
        Files.writeString(temporary, content, Charsets.UTF_8)
        try {
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            LOG.info(
                "Atomic replace is not supported for $target; " +
                    "falling back to regular replace",
                e,
            )
            Files.move(
                temporary,
                target,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}
```

多文件处理函数：

```kotlin
private suspend fun handleMultiFileResult(
    project: Project,
    result: ExportResult.Success,
    config: ChannelConfig,
    metadata: OpenApiExportMetadata,
): Boolean {
    val directory = resolveTargetDirectory(project, config)
    val targets = resolveMultiFileTargets(directory, metadata)
    confirmOverwrite(project, targets.keys)

    background {
        targets.forEach { (target, content) ->
            try {
                writeAtomically(target, content)
            } catch (e: Exception) {
                LOG.warn("Failed to write OpenAPI output file: $target", e)
                throw IllegalStateException(
                    "Failed to write OpenAPI output file: $target",
                    e,
                )
            }
        }
    }

    val message = buildString {
        append("Successfully exported ${result.count} endpoints to ")
        append(directory.absolutePath)
        append(" (${metadata.pathFragmentCount} Paths files, ")
        append("${metadata.schemaCount} schemas, ")
        append("${metadata.unresolvedPathCount} unresolved paths)")
        if (metadata.warnings.isNotEmpty()) {
            append("\nWarnings:\n")
            metadata.warnings.forEach { appendLine("- $it") }
        }
    }.trimEnd()
    swing {
        if (metadata.warnings.isEmpty()) {
            Messages.showInfoMessage(project, message, "Export API")
        } else {
            Messages.showWarningDialog(project, message, "Export API")
        }
    }
    LOG.info("OpenAPI multi-document export completed: ${directory.absolutePath}")
    return true
}
```

按 `additionalFiles` 后 root 的顺序写入，降低入口文件先发布却引用未完成文件的风险。发生中途失败时报告精确 target，不删除目录、不回滚已经替换的文件。

**Step 8: 运行通道测试确认通过**

Run: 同 Step 2。

Expected: PASS；取消覆盖时 root 内容仍为 `keep`，且未创建 Paths 文件。

**Step 9: 提交**

```powershell
git add src/main/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiChannel.kt src/test/kotlin/com/itangcent/easyapi/channel/openapi/OpenApiChannelTest.kt
git commit -m "feat(openapi): write multi-document exports safely"
```

### Task 7: 补充用户说明并做完整回归

**Files:**
- Modify: `README.md:30`
- Modify: `README.md:142`

**Step 1: 更新可发现性说明**

把 OpenAPI 能力表说明改为：

```markdown
| **OpenAPI** *(Beta)* | ✓ | ✓ | Single `.json` / `.yaml` file, or Controller-grouped multi-document directory |
```

在导出步骤后补充：

```markdown
For OpenAPI, choose **Single file** (default) or
**Multiple files by Controller**. Multi-document export writes `openapi.*`,
one file per Controller under `paths/`, and an optional
`schemas/schemas.*`; all references are relative to the selected directory.
```

不新增 `.easyapi/openapi.yaml`，不描述尚未实现的 folder/tag/schema 分组策略。

**Step 2: 运行 OpenAPI 全套测试**

Run:

```powershell
.\gradlew.bat test --tests "com.itangcent.easyapi.channel.openapi.*"
```

Expected: PASS，`BUILD SUCCESSFUL`。

**Step 3: 运行项目完整测试和构建**

Run:

```powershell
.\gradlew.bat test
.\gradlew.bat build
```

Expected: 两条命令均 `BUILD SUCCESSFUL`。

**Step 4: 做静态质量检查**

Run:

```powershell
git diff --check
rg -n "LOG\.error|LOG\.debug|LOG\.trace|println\(|printStackTrace\(" src/main/kotlin/com/itangcent/easyapi/channel/openapi
git status --short
```

Expected:

- `git diff --check` 无输出；
- anti-pattern 搜索不出现本次新增代码；
- `git status --short` 只包含计划内文件，不包含执行约束中列出的无关文件。

**Step 5: 手工 smoke test**

Run:

```powershell
.\gradlew.bat runIde
```

在沙箱 IDE 中验证：

1. OpenAPI 导出面板默认仍为 `Single file`。
2. 单文件 JSON/YAML 保存行为与现状一致。
3. 选择 `Multiple files by Controller` 后出现目录选择器。
4. 导出目录直接包含 `openapi.yaml`、`paths/*.yaml` 和可选 `schemas/schemas.yaml`。
5. 用支持外部 `$ref` 的 OpenAPI 查看器打开 `openapi.yaml`，Controller Paths 和 Schema 引用均可解析。
6. 同一规范化 path 来自两个 Controller 时，导出在任何写盘前失败并列出 path、method、Controller。
7. 再次导出到同一目录只弹出一次覆盖确认；拒绝后所有原文件保持不变。

结束 `runIde` 后再执行一次 `git status --short`，确认没有意外生成仓库内文件。

**Step 6: 提交文档**

```powershell
git add README.md
git commit -m "docs(openapi): document multi-document export"
```

**Step 7: 最终提交审计**

Run:

```powershell
git log --oneline 804aacce..HEAD
git diff --stat 804aacce..HEAD
git status --short
```

Expected:

- 提交历史按计划包含配置、引用模型、所有权、拆分、元数据、写盘和文档提交；
- 代码变化局限于 `channel/openapi`、对应测试和 `README.md`；
- 执行 worktree 干净。

## 明确不实现

- 不新增 OpenAPI channel、SPI、规则键或项目配置文件。
- 不按 folder/tag/package/URL 分组 Paths。
- 不拆分 Schemas；第一阶段始终集中到一个 `schemas/schemas.*`。
- 不升级 OpenAPI 3.1，不使用 `components.pathItems`。
- 不对目标目录执行清理，不删除本次没有生成的旧文件。
- 不对整个目录做事务回滚；只保证每个输出文件不会留下半写内容。
