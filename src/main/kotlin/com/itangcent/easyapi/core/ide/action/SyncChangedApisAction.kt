package com.itangcent.easyapi.core.ide.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.psi.PsiDocumentManager
import com.itangcent.easyapi.core.ide.DumbModeHelper
import com.itangcent.easyapi.core.ide.support.NotificationUtils
import com.itangcent.easyapi.core.ide.support.runWithProgress
import com.itangcent.easyapi.core.ide.sync.ChangedApiCandidateResolver
import com.itangcent.easyapi.core.ide.sync.LocalChangesSourceCollector
import com.itangcent.easyapi.core.ide.sync.resolveAndExportApis
import com.itangcent.easyapi.core.internal.threading.backgroundAsync
import com.itangcent.easyapi.core.internal.threading.swing
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
                if (!DumbModeHelper.waitForSmartModeOrNotify(project)) return@backgroundAsync
                swing { PsiDocumentManager.getInstance(project).commitAllDocuments() }

                val changeListManager = ChangeListManager.getInstance(project)
                val sources = runWithProgress(project, "Reading local changes...") {
                    LocalChangesSourceCollector(project).collect(
                        changeListManager.allChanges,
                        changeListManager.unversionedFilesPaths
                    )
                }
                sources.warnings.forEach(console::warn)
                if (sources.files.isEmpty()) {
                    NotificationUtils.notifyWarning(project, TITLE, "No changed Java source files found")
                    return@backgroundAsync
                }

                val candidates = runWithProgress(project, "Finding changed APIs...") {
                    ChangedApiCandidateResolver(project).resolve(sources.files)
                }
                candidates.warnings.forEach(console::warn)
                if (candidates.candidates.isEmpty()) {
                    NotificationUtils.notifyWarning(project, TITLE, "No changed Controller APIs found")
                    return@backgroundAsync
                }

                candidates.candidates.forEach { candidate ->
                    console.info("Changed API candidate: ${candidate.selector}; ${candidate.reason}")
                }
                resolveAndExportApis(
                    project,
                    TITLE,
                    candidates.candidates.map { it.selector }
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
