package com.itangcent.easyapi.core.ide.action

import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.itangcent.easyapi.testFramework.EasyApiLightCodeInsightFixtureTestCase

class SyncChangedApisActionTest : EasyApiLightCodeInsightFixtureTestCase() {

    private lateinit var action: SyncChangedApisAction

    override fun setUp() {
        super.setUp()
        action = SyncChangedApisAction()
    }

    fun testActionUsesBackgroundUpdateThread() {
        assertEquals(
            "Action update should run on a background thread",
            com.intellij.openapi.actionSystem.ActionUpdateThread.BGT,
            action.actionUpdateThread
        )
    }

    fun testActionEnablesOnlyWithProject() {
        val withProject = eventWithProject(project)
        val withoutProject = eventWithProject(null)

        action.update(withProject)
        action.update(withoutProject)

        assertTrue("Action should be enabled when a project exists", withProject.presentation.isEnabled)
        assertFalse("Action should be disabled without a project", withoutProject.presentation.isEnabled)
    }

    fun testActionPerformedReturnsWithoutProject() {
        action.actionPerformed(eventWithProject(null))
    }

    fun testActionIsRegisteredInPluginXml() {
        assertTrue(
            "Sync Changed APIs action should be registered",
            ActionManager.getInstance().getAction(ACTION_ID) is SyncChangedApisAction
        )
    }

    private fun eventWithProject(eventProject: com.intellij.openapi.project.Project?): AnActionEvent =
        AnActionEvent.createEvent(
            DataContext { eventProject },
            Presentation(),
            "test",
            ActionUiKind.NONE,
            null
        )

    private companion object {
        const val ACTION_ID = "com.itangcent.idea.easy_api.actions.SyncChangedApisAction"
    }
}
