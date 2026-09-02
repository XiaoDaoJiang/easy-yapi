package com.itangcent.easyapi.core.ide.sync

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.vcsUtil.VcsUtil
import com.itangcent.easyapi.testFramework.EasyApiLightCodeInsightFixtureTestCase
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class LocalChangesSourceCollectorTest : EasyApiLightCodeInsightFixtureTestCase() {

    fun testCollectsModifiedSourceWithBeforeContent() = runTest {
        val currentFile = myFixture.addFileToProject(
            "src/demo/ChangedController.java",
            "package demo; public class ChangedController { String value = \"after\"; }"
        )
        val filePath = VcsUtil.getFilePath(currentFile.virtualFile)
        val change = object : Change(
            TestRevision(filePath, "package demo; public class ChangedController { String value = \"before\"; }"),
            TestRevision(filePath, currentFile.text)
        ) {
            override fun getVirtualFile() = currentFile.virtualFile
        }

        val result = LocalChangesSourceCollector(project).collect(listOf(change), emptyList())

        assertEquals("Modified Java source should be collected", 1, result.files.size)
        assertTrue("Collected file should be the current PSI file", result.files.single().currentFile.isEquivalentTo(currentFile))
        assertTrue("Before content should come from VCS", result.files.single().beforeContent!!.contains("before"))
        assertTrue("Valid source should not produce warnings", result.warnings.isEmpty())
    }

    fun testCollectsUnversionedJavaSource() = runTest {
        val currentFile = myFixture.addFileToProject(
            "src/demo/NewController.java",
            "package demo; public class NewController {}"
        )
        val path = mock<FilePath> {
            on { name } doReturn "NewController.java"
            on { this.path } doReturn currentFile.virtualFile.path
            on { virtualFile } doReturn currentFile.virtualFile
        }

        val result = LocalChangesSourceCollector(project).collect(emptyList(), listOf(path))

        assertEquals("Unversioned Java source should be collected", 1, result.files.size)
        assertNull("Unversioned source should have no previous content", result.files.single().beforeContent)
    }

    fun testSkipsModifiedSourceWhenBeforeContentIsUnavailable() = runTest {
        val currentFile = myFixture.addFileToProject(
            "src/demo/ChangedController.java",
            "package demo; public class ChangedController {}"
        )
        val filePath = VcsUtil.getFilePath(currentFile.virtualFile)
        val change = object : Change(
            TestRevision(filePath, null),
            TestRevision(filePath, currentFile.text)
        ) {
            override fun getVirtualFile() = currentFile.virtualFile
        }

        val result = LocalChangesSourceCollector(project).collect(listOf(change), emptyList())

        assertTrue("Unknown previous content must not be treated as a new file", result.files.isEmpty())
        assertEquals("Unavailable previous content should be reported", 1, result.warnings.size)
    }

    private data class TestRevision(
        private val path: FilePath,
        private val text: String?
    ) : ContentRevision {
        override fun getContent(): String? = text
        override fun getFile(): FilePath = path
        override fun getRevisionNumber(): VcsRevisionNumber = VcsRevisionNumber.NULL
    }
}
