package com.itangcent.easyapi.core.ide.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.vfs.LocalFileSystem
import com.itangcent.easyapi.core.ide.support.NotificationUtils
import com.itangcent.easyapi.core.ide.sync.ControllerApiManifest
import com.itangcent.easyapi.core.ide.sync.resolveLocalChangesApiCandidates
import com.itangcent.easyapi.core.internal.threading.backgroundAsync
import com.itangcent.easyapi.core.logging.IdeaLog
import com.itangcent.easyapi.core.logging.console
import kotlinx.coroutines.CancellationException
import java.nio.file.Path

class AppendLocalChangesToSyncListAction : AnAction(), IdeaLog {

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
        console.info("AppendLocalChangesToSyncListAction.actionPerformed: manifest=$manifestPath")

        backgroundAsync {
            try {
                val candidates = resolveLocalChangesApiCandidates(project, TITLE) ?: return@backgroundAsync
                val result = ControllerApiManifest.append(manifestPath, candidates)
                if (result.errors.isNotEmpty()) {
                    val message = result.errors.joinToString("; ") { "line ${it.lineNumber}: ${it.message}" }
                    console.warn("Sync manifest is invalid: $message")
                    NotificationUtils.notifyWarning(project, TITLE, message)
                    return@backgroundAsync
                }
                result.rejection?.let { message ->
                    console.error("Append local changes to sync list failed: $message")
                    NotificationUtils.notifyError(project, TITLE, message)
                    return@backgroundAsync
                }

                val added = result.appendedSelectors.size
                val skipped = candidates.size - added
                if (result.written) {
                    LocalFileSystem.getInstance().refreshAndFindFileByIoFile(manifestPath.toFile())
                }
                console.info("Sync manifest updated: added=$added, skipped=$skipped")
                NotificationUtils.notifyInfo(project, TITLE, "Added $added API selector(s); skipped $skipped already listed selector(s)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                console.error("Append local changes to sync list failed", e)
                NotificationUtils.notifyError(project, TITLE, e.message ?: "Unexpected error", e)
            }
        }
    }

    internal fun manifestPath(basePath: String): Path =
        Path.of(basePath, MANIFEST_DIRECTORY, MANIFEST_SUBDIRECTORY, MANIFEST_FILE)

    private companion object {
        const val TITLE = "Append Local Changes to Sync List"
        const val MANIFEST_DIRECTORY = ".easyapi"
        const val MANIFEST_SUBDIRECTORY = "sync"
        const val MANIFEST_FILE = "sync-apis.txt"
    }
}
