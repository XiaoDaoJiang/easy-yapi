package com.itangcent.easyapi.core.ide.sync

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.itangcent.easyapi.core.export.ExportOrchestrator
import com.itangcent.easyapi.core.export.ExportResult
import com.itangcent.easyapi.core.ide.dialog.ExportDialog
import com.itangcent.easyapi.core.ide.support.NotificationUtils
import com.itangcent.easyapi.core.ide.support.runWithProgress
import com.itangcent.easyapi.core.internal.threading.swing
import com.itangcent.easyapi.core.logging.console

internal suspend fun resolveAndExportApis(
    project: Project,
    title: String,
    selectors: List<ControllerApiSelector>
) {
    val console = project.console
    val resolution = runWithProgress(project, "Resolving APIs...") { indicator ->
        ListedApiEndpointResolver(project).resolve(selectors, indicator)
    }
    resolution.errors.forEach { console.warn(it.message) }
    if (resolution.endpoints.isEmpty()) {
        NotificationUtils.notifyWarning(
            project,
            title,
            resolution.errors.joinToString("; ") { it.message }.ifEmpty { "No API endpoints found" }
        )
        return
    }

    val dialogResult = swing {
        ExportDialog.show(project, resolution.endpoints.size, resolution.endpoints)
    }
    if (dialogResult == null) {
        console.info("$title cancelled in export dialog")
        return
    }

    val selectedEndpoints = dialogResult.selectedEndpoints.map { it.endpoint }
    if (selectedEndpoints.isEmpty()) {
        NotificationUtils.notifyWarning(project, title, "No API endpoints selected")
        return
    }

    val result = runWithProgress(project, "Exporting APIs...") { indicator ->
        ExportOrchestrator.getInstance(project).exportViaChannel(
            dialogResult.channelId,
            selectedEndpoints,
            dialogResult.channelConfig,
            indicator
        )
    }
    if (result is ExportResult.Error) {
        swing { Messages.showErrorDialog(project, result.message, "Export Failed") }
    }
}
