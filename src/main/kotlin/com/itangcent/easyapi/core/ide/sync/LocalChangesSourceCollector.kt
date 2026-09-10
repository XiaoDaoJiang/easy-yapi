package com.itangcent.easyapi.core.ide.sync

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.itangcent.easyapi.core.internal.threading.read
import com.itangcent.easyapi.core.logging.console
import kotlinx.coroutines.CancellationException

internal data class LocalChangesSourceResolution(
    val files: List<ChangedSourceFile>,
    val warnings: List<String>
)

internal class LocalChangesSourceCollector(private val project: Project) {

    suspend fun collect(
        changes: Collection<Change>,
        unversionedPaths: Collection<FilePath>
    ): LocalChangesSourceResolution {
        val files = linkedMapOf<String, ChangedSourceFile>()
        val warnings = mutableListOf<String>()

        for (change in changes) {
            val path = change.afterRevision?.file ?: change.beforeRevision?.file ?: continue
            if (!isSource(path.name)) continue
            val virtualFile = change.virtualFile
                ?: LocalFileSystem.getInstance().findFileByPath(path.path)
            if (virtualFile == null) {
                warnings += "Deleted source is not synchronized: ${path.path}"
                continue
            }
            val beforeRevision = change.beforeRevision
            val beforeContent = try {
                beforeRevision?.content
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                project.console.warn("Failed to read VCS revision: ${path.path}", e)
                warnings += "Failed to read previous source: ${path.path}"
                continue
            }
            if (beforeRevision != null && beforeContent == null) {
                warnings += "Previous source content is unavailable: ${path.path}"
                continue
            }
            resolve(virtualFile, beforeContent)?.let { files[virtualFile.path] = it }
        }

        for (path in unversionedPaths) {
            if (!isSource(path.name) || path.path in files) continue
            val virtualFile = path.virtualFile
                ?: LocalFileSystem.getInstance().findFileByPath(path.path)
                ?: continue
            resolve(virtualFile, null)?.let { files[virtualFile.path] = it }
        }

        return LocalChangesSourceResolution(files.values.toList(), warnings)
    }

    private suspend fun resolve(virtualFile: VirtualFile, beforeContent: String?): ChangedSourceFile? = read {
        if (!virtualFile.isValid || virtualFile.isDirectory) return@read null
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return@read null
        ChangedSourceFile(psiFile, beforeContent)
    }

    private fun isSource(name: String): Boolean =
        name.endsWith(".java", ignoreCase = true)
}
