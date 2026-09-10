package com.itangcent.easyapi.core.ide.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.itangcent.easyapi.core.ide.support.NotificationUtils
import com.itangcent.easyapi.core.ide.sync.resolveAndExportApis
import com.itangcent.easyapi.core.ide.sync.resolveLocalChangesApiCandidates
import com.itangcent.easyapi.core.internal.threading.backgroundAsync
import com.itangcent.easyapi.core.logging.IdeaLog
import com.itangcent.easyapi.core.logging.console
import kotlinx.coroutines.CancellationException

class SyncChangedApisAction : AnAction(), IdeaLog {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val console = project.console
        console.info("SyncChangedApisAction.actionPerformed: project=${project.name}")

        backgroundAsync {
            try {
                val candidates = resolveLocalChangesApiCandidates(project, TITLE) ?: return@backgroundAsync
                resolveAndExportApis(
                    project,
                    TITLE,
                    candidates.map { it.selector }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                console.error("Sync changed APIs failed", e)
                NotificationUtils.notifyError(project, TITLE, e.message ?: "Unexpected error", e)
            }
        }
    }

    private companion object {
        const val TITLE = "Sync Changed APIs"
    }
}
