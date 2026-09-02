package com.itangcent.easyapi.core.ide.action

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.itangcent.easyapi.testFramework.EasyApiLightCodeInsightFixtureTestCase
import java.nio.file.Path

class AppendLocalChangesToSyncListActionTest : EasyApiLightCodeInsightFixtureTestCase() {

    private lateinit var action: AppendLocalChangesToSyncListAction

    override fun setUp() {
        super.setUp()
        action = AppendLocalChangesToSyncListAction()
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

    fun testManifestUsesDedicatedSyncDirectory() {
        assertEquals(
            "Manifest should not be loaded as a top-level EasyApi rule file",
            Path.of("project", ".easyapi", "sync", "sync-apis.txt"),
            action.manifestPath("project")
        )
    }

    fun testActionIsRegisteredInPluginXml() {
        assertTrue(
            "Append Local Changes action should be registered",
            ActionManager.getInstance().getAction(ACTION_ID) is AppendLocalChangesToSyncListAction
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
        const val ACTION_ID = "com.itangcent.idea.easy_api.actions.AppendLocalChangesToSyncListAction"
    }
}
