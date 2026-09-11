package com.itangcent.easyapi.core.ide.action

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.itangcent.easyapi.testFramework.EasyApiLightCodeInsightFixtureTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

class SyncApiActionGroupTest : EasyApiLightCodeInsightFixtureTestCase() {

    private val actionManager: ActionManager
        get() = ActionManager.getInstance()

    fun testSyncApiGroupIsPopupAndContainsActionsInOrder() {
        val group = actionManager.getAction(SYNC_API_GROUP_ID) as? ActionGroup
        assertNotNull("Sync APIs group should be registered", group)
        assertTrue("Sync APIs group should be a popup", group!!.isPopup)

        assertEquals(
            "Sync APIs actions should keep their declared order",
            SYNC_ACTION_IDS,
            actionIds(group)
        )
    }

    fun testParentMenusContainTheSyncApiGroupWithoutDirectSyncActions() {
        PARENT_MENU_IDS.forEach { menuId ->
            val menu = actionManager.getAction(menuId) as? ActionGroup
            assertNotNull("Parent menu '$menuId' should be registered", menu)

            val childIds = actionIds(menu!!)
            assertEquals(
                "Parent menu '$menuId' should contain Sync APIs exactly once",
                1,
                childIds.count { it == SYNC_API_GROUP_ID }
            )
            SYNC_ACTION_IDS.forEach { actionId ->
                assertTrue(
                    "Parent menu '$menuId' should not contain '$actionId' directly",
                    actionId !in childIds
                )
            }
        }
    }

    private fun actionIds(group: ActionGroup): List<String> =
        group.getChildren(null).mapNotNull(actionManager::getId)

    private companion object {
        const val SYNC_API_GROUP_ID = "com.itangcent.idea.easy_api.actions.ApiSyncGroup"
        val SYNC_ACTION_IDS = listOf(
            "com.itangcent.idea.easy_api.actions.SyncChangedApisAction",
            "com.itangcent.idea.easy_api.actions.AppendLocalChangesToSyncListAction",
            "com.itangcent.idea.easy_api.actions.SyncListedApisAction"
        )
        val PARENT_MENU_IDS = listOf(
            "EasyApiGenerateMenu",
            "EasyApiEditorLangPopupMenu",
            "EasyApiProjectViewPopupMenu"
        )
    }
}
