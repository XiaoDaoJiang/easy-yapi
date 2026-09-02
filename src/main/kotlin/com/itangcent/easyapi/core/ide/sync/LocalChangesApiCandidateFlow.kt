package com.itangcent.easyapi.core.ide.sync

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.psi.PsiDocumentManager
import com.itangcent.easyapi.core.ide.DumbModeHelper
import com.itangcent.easyapi.core.ide.support.NotificationUtils
import com.itangcent.easyapi.core.ide.support.runWithProgress
import com.itangcent.easyapi.core.internal.threading.swing
import com.itangcent.easyapi.core.logging.console

internal suspend fun resolveLocalChangesApiCandidates(project: Project, title: String): List<ChangedApiCandidate>? {
    if (!DumbModeHelper.waitForSmartModeOrNotify(project)) return null
    swing { PsiDocumentManager.getInstance(project).commitAllDocuments() }

    val console = project.console
    val changeListManager = ChangeListManager.getInstance(project)
    val sources = runWithProgress(project, "Reading local changes...") {
        LocalChangesSourceCollector(project).collect(
            changeListManager.allChanges,
            changeListManager.unversionedFilesPaths
        )
    }
    sources.warnings.forEach(console::warn)
    if (sources.files.isEmpty()) {
        NotificationUtils.notifyWarning(project, title, "No changed Java source files found")
        return null
    }

    val candidates = runWithProgress(project, "Finding changed APIs...") {
        ChangedApiCandidateResolver(project).resolve(sources.files)
    }
    candidates.warnings.forEach(console::warn)
    if (candidates.candidates.isEmpty()) {
        NotificationUtils.notifyWarning(project, title, "No changed Controller APIs found")
        return null
    }

    candidates.candidates.forEach { candidate ->
        console.info("Changed API candidate: ${candidate.selector}; ${candidate.reason}")
    }
    return candidates.candidates
}
