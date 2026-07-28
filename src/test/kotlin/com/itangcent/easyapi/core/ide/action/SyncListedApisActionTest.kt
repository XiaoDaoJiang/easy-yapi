package com.itangcent.easyapi.core.ide.action

import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.itangcent.easyapi.testFramework.EasyApiLightCodeInsightFixtureTestCase

class SyncListedApisActionTest : EasyApiLightCodeInsightFixtureTestCase() {

    private lateinit var action: SyncListedApisAction

    override fun setUp() {
        super.setUp()
        action = SyncListedApisAction()
    }

    fun testActionUsesBackgroundUpdateThread() {
        assertEquals(
            "Action update should run on a background thread",
            com.intellij.openapi.actionSystem.ActionUpdateThread.BGT,
            action.actionUpdateThread
        )
    }

    fun testActionEnablesWithProject() {
        val event = eventWithProject(project)

        action.update(event)

        assertTrue("Action should be enabled when a project exists", event.presentation.isEnabled)
    }

    fun testActionDisablesWithoutProject() {
        val event = eventWithProject(null)

        action.update(event)

        assertFalse("Action should be disabled without a project", event.presentation.isEnabled)
    }

    fun testActionPerformedReturnsWithoutProject() {
        action.actionPerformed(eventWithProject(null))
    }

    private fun eventWithProject(eventProject: com.intellij.openapi.project.Project?): AnActionEvent =
        AnActionEvent.createEvent(
            DataContext { eventProject },
            Presentation(),
            "test",
            ActionUiKind.NONE,
            null
        )
}
