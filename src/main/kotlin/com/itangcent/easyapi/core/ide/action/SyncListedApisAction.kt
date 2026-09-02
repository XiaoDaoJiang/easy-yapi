package com.itangcent.easyapi.core.ide.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.itangcent.easyapi.core.ide.DumbModeHelper
import com.itangcent.easyapi.core.ide.support.NotificationUtils
import com.itangcent.easyapi.core.ide.sync.ControllerApiManifest
import com.itangcent.easyapi.core.ide.sync.resolveAndExportApis
import com.itangcent.easyapi.core.internal.threading.backgroundAsync
import com.itangcent.easyapi.core.logging.IdeaLog
import com.itangcent.easyapi.core.logging.console
import kotlinx.coroutines.CancellationException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class SyncListedApisAction : AnAction(), IdeaLog {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath ?: run {
            NotificationUtils.notifyWarning(project, TITLE, "Project base path is unavailable")
            return
        }
        val manifestPath = manifestPath(basePath)
        val console = project.console
        console.info("SyncListedApisAction.actionPerformed: manifest=$manifestPath")

        backgroundAsync {
            try {
                if (!DumbModeHelper.waitForSmartModeOrNotify(project)) return@backgroundAsync

                val manifest = readManifest(manifestPath)
                if (manifest == null) {
                    NotificationUtils.notifyWarning(
                        project,
                        TITLE,
                        "Manifest not found: $manifestPath"
                    )
                    return@backgroundAsync
                }

                val parsed = ControllerApiManifest.parse(manifest)
                if (parsed.errors.isNotEmpty()) {
                    NotificationUtils.notifyWarning(
                        project,
                        TITLE,
                        parsed.errors.joinToString("; ") { "line ${it.lineNumber}: ${it.message}" }
                    )
                    return@backgroundAsync
                }
                if (parsed.selectors.isEmpty()) {
                    NotificationUtils.notifyWarning(project, TITLE, "Manifest contains no API method selectors: $manifestPath")
                    return@backgroundAsync
                }

                console.info("Sync listed APIs: selectors=${parsed.selectors.size}")
                resolveAndExportApis(project, TITLE, parsed.selectors)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                console.error("Sync listed APIs failed", e)
                NotificationUtils.notifyError(project, TITLE, e.message ?: "Unexpected error", e)
            }
        }
    }

    private fun readManifest(path: Path): String? {
        if (!Files.isRegularFile(path)) return null
        return Files.readString(path, StandardCharsets.UTF_8)
    }

    internal fun manifestPath(basePath: String): Path =
        Path.of(basePath, MANIFEST_DIRECTORY, MANIFEST_SUBDIRECTORY, MANIFEST_FILE)

    private companion object {
        const val TITLE = "Sync Listed APIs"
        const val MANIFEST_DIRECTORY = ".easyapi"
        const val MANIFEST_SUBDIRECTORY = "sync"
        const val MANIFEST_FILE = "sync-apis.txt"
    }
}
