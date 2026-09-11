# Sync API Actions Submenu Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 将三个同步 API 动作合并到名为 Sync APIs 的二级弹出菜单中，并保持现有行为、ID、顺序和快捷键不变。

**Architecture:** 复用 IntelliJ Platform 的声明式 ActionGroup，不新增 Kotlin 组类。在 plugin.xml 中定义一个 popup 组，将三个现有 action 作为子项，再由三个现有父菜单引用该组。

**Tech Stack:** IntelliJ Platform Action System、plugin.xml、Kotlin/JUnit 4 IDE fixture tests、Gradle。

---

## 当前结构与目标结构

当前三个 action 各自挂到相同的三个父菜单，因此并列显示。目标层级：

~~~text
EasyApi
├─ Call
├─ Export
├─ Export to...
└─ Sync APIs >
   ├─ Sync Changed APIs...
   ├─ Append Local Changes to Sync List
   └─ Sync Listed APIs...
~~~

三个使用场景都要保持一致：

- Generate 菜单
- Editor Language Popup 菜单
- Project View Popup 菜单

不要修改现有 action 的实现类、action ID、显示文本、描述或 Alt+Shift+Y 快捷键。工作区中已有的 .github/workflows/release.yml 修改也必须保持未提交。

### Task 1: 为菜单层级增加失败测试

**Files:**

- Create: src/test/kotlin/com/itangcent/easyapi/core/ide/action/SyncApiActionGroupTest.kt

按项目 @write-test-case 的 Action Test / IDE fixture 模式编写测试，验证真实 ActionManager 中的注册结果。

**Step 1: Write the failing test**

~~~kotlin
package com.itangcent.easyapi.core.ide.action

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.itangcent.easyapi.testFramework.EasyApiLightCodeInsightFixtureTestCase

class SyncApiActionGroupTest : EasyApiLightCodeInsightFixtureTestCase() {

    private val actionManager: ActionManager
        get() = ActionManager.getInstance()

    fun testSyncActionsAreChildrenOfPopupGroup() {
        val group = actionManager.getAction(SYNC_GROUP_ID) as? ActionGroup
        assertNotNull("Sync API popup group should be registered", group)
        assertTrue("Sync API group should be a popup", group!!.isPopup)

        val childIds = group.getChildren(null)
            .mapNotNull { actionManager.getId(it) }

        assertEquals(
            "Sync actions should remain in their current order",
            SYNC_ACTION_IDS,
            childIds
        )
    }

    fun testParentMenusContainTheGroupInsteadOfDirectActions() {
        val parentIds = listOf(
            "EasyApiGenerateMenu",
            "EasyApiEditorLangPopupMenu",
            "EasyApiProjectViewPopupMenu"
        )

        parentIds.forEach { parentId ->
            val parent = actionManager.getAction(parentId) as? ActionGroup
            assertNotNull("Parent menu should be registered: $parentId", parent)

            val childIds = parent!!.getChildren(null)
                .mapNotNull { actionManager.getId(it) }

            assertEquals(
                "Parent menu should contain the sync group once: $parentId",
                1,
                childIds.count { it == SYNC_GROUP_ID }
            )
            assertTrue(
                "Parent menu should not contain sync actions directly: $parentId",
                childIds.intersect(SYNC_ACTION_IDS.toSet()).isEmpty()
            )
        }
    }

    private companion object {
        const val SYNC_GROUP_ID = "com.itangcent.idea.easy_api.actions.ApiSyncGroup"
        val SYNC_ACTION_IDS = listOf(
            "com.itangcent.idea.easy_api.actions.SyncChangedApisAction",
            "com.itangcent.idea.easy_api.actions.AppendLocalChangesToSyncListAction",
            "com.itangcent.idea.easy_api.actions.SyncListedApisAction"
        )
    }
}
~~~

**Step 2: Run the test to verify it fails**

Run:

~~~powershell
.\gradlew.bat test --tests "com.itangcent.easyapi.core.ide.action.SyncApiActionGroupTest"
~~~

Expected: FAIL because ApiSyncGroup does not exist and the three actions are still direct children of the parent menus.

### Task 2: 在 plugin.xml 中声明二级菜单

**Files:**

- Modify: src/main/resources/META-INF/plugin.xml:183-245

**Step 1: Add one popup group before the parent menu declarations**

将现有三个 action 定义移动到同一个组中，保留原有 action 属性和顺序：

~~~xml
<group id="com.itangcent.idea.easy_api.actions.ApiSyncGroup"
       text="Sync APIs"
       description="Synchronize Controller APIs"
       popup="true">
    <action id="com.itangcent.idea.easy_api.actions.SyncChangedApisAction"
            class="com.itangcent.easyapi.core.ide.action.SyncChangedApisAction"
            text="Sync Changed APIs..."
            description="Export Controller APIs changed in IDEA Local Changes"/>

    <action id="com.itangcent.idea.easy_api.actions.AppendLocalChangesToSyncListAction"
            class="com.itangcent.easyapi.core.ide.action.AppendLocalChangesToSyncListAction"
            text="Append Local Changes to Sync List"
            description="Append changed Controller APIs to .easyapi/sync/sync-apis.txt"/>

    <action id="com.itangcent.idea.easy_api.actions.SyncListedApisAction"
            class="com.itangcent.easyapi.core.ide.action.SyncListedApisAction"
            text="Sync Listed APIs..."
            description="Export Controller APIs listed in .easyapi/sync/sync-apis.txt">
        <keyboard-shortcut first-keystroke="alt shift Y" keymap="$default"/>
    </action>
</group>
~~~

**Step 2: Reference the group from all three parent menus**

在 EasyApiGenerateMenu、EasyApiEditorLangPopupMenu、EasyApiProjectViewPopupMenu 中各添加一次：

~~~xml
<reference ref="com.itangcent.idea.easy_api.actions.ApiSyncGroup"/>
~~~

每个引用使用当前动作所在位置的 anchor="last"，确保新二级菜单仍位于现有 EasyApi 菜单末尾。

**Step 3: Remove the old direct registrations**

删除三个独立 action 定义及其所有 add-to-group 节点，避免动作同时出现在父菜单和 Sync APIs 子菜单中。不要重复声明任何 action ID。

### Task 3: 更新用户文档并同步镜像

**Files:**

- Modify: docs/knowledge-base/usage-guide.md:81-110
- Generated: src/main/resources/docs/knowledge-base/usage-guide.md
- Generated: skills/easy-yapi-assistant/docs/usage-guide.md

**Step 1: Update the canonical guide**

将涉及菜单入口的描述改为包含二级菜单路径：

~~~text
EasyYapi → Sync APIs → Append Local Changes to Sync List
EasyYapi → Sync APIs → Sync Listed APIs...
~~~

保留现有 manifest 路径、文件格式和工作流说明，不改写功能语义。

**Step 2: Regenerate both mirrors**

Run:

~~~powershell
.\gradlew.bat syncKnowledgeBase
~~~

Expected: 两个镜像文档与 docs/knowledge-base/usage-guide.md 内容完全一致。

### Task 4: 验证菜单注册、现有 action 和文档镜像

**Step 1: Run focused action tests**

Run:

~~~powershell
.\gradlew.bat test --tests "com.itangcent.easyapi.core.ide.action.SyncApiActionGroupTest" --tests "com.itangcent.easyapi.core.ide.action.SyncChangedApisActionTest" --tests "com.itangcent.easyapi.core.ide.action.AppendLocalChangesToSyncListActionTest" --tests "com.itangcent.easyapi.core.ide.action.SyncListedApisActionTest" --tests "com.itangcent.easyapi.skills.EasyYapiAssistantSkillTest"
~~~

Expected: all focused tests PASS；三个动作仍可注册，更新线程和项目启用逻辑不变，父菜单不再直接包含它们。

**Step 2: Validate plugin packaging and formatting**

Run:

~~~powershell
.\gradlew.bat build
git diff --check
~~~

Expected: build 成功，plugin.xml 可被 IntelliJ Platform 正常加载，无空白差异错误。

**Step 3: Manual acceptance**

Run .\gradlew.bat runIde and verify in Generate、Editor Language Popup、Project View Popup:

1. 只显示一个 Sync APIs 二级菜单。
2. 子菜单内按原顺序显示三个 action。
3. Sync Listed APIs... 的 Alt+Shift+Y 快捷键仍然可用。
4. 三个 action 的执行行为和通知文本没有变化。

### Task 5: 提交实现

确认仅包含 plugin.xml、菜单测试和三份同步文档修改后提交：

~~~powershell
git add -- src/main/resources/META-INF/plugin.xml src/test/kotlin/com/itangcent/easyapi/core/ide/action/SyncApiActionGroupTest.kt docs/knowledge-base/usage-guide.md src/main/resources/docs/knowledge-base/usage-guide.md skills/easy-yapi-assistant/docs/usage-guide.md
git commit -m "feat(ui): group sync actions in submenu"
~~~

不要将已有的 .github/workflows/release.yml 修改加入提交。
