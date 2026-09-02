package com.itangcent.easyapi.core.ide.sync

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiWhiteSpace
import com.itangcent.easyapi.core.export.recognizer.CompositeApiClassRecognizer
import com.itangcent.easyapi.core.internal.threading.read

internal data class ChangedSourceFile(
    val currentFile: PsiFile,
    val beforeContent: String?
)

internal data class ChangedApiCandidate(
    val selector: ControllerApiSelector,
    val reason: String
)

internal data class ChangedApiCandidateResolution(
    val candidates: List<ChangedApiCandidate>,
    val warnings: List<String>
)

internal class ChangedApiCandidateResolver(private val project: Project) {

    suspend fun resolve(changes: List<ChangedSourceFile>): ChangedApiCandidateResolution {
        val candidates = mutableListOf<ChangedApiCandidate>()
        val warnings = mutableListOf<String>()

        for (change in changes) {
            val current = read { change.currentFile.text }
            val before = change.beforeContent
            if (before == null) {
                candidates += read {
                    val classOwner = change.currentFile as? PsiClassOwner ?: return@read emptyList()
                    val recognizer = CompositeApiClassRecognizer.getInstance(project)
                    classOwner.classes.mapNotNull { psiClass ->
                        if (!recognizer.isApiClass(psiClass)) return@mapNotNull null
                        val className = psiClass.qualifiedName ?: return@mapNotNull null
                        ChangedApiCandidate(
                            ControllerSelector(className, current.lineNumber(psiClass.textOffset)),
                            "Controller added"
                        )
                    }
                }
                continue
            }
            val oldFile = read {
                PsiFileFactory.getInstance(project)
                    .createFileFromText(change.currentFile.name, change.currentFile.fileType, before)
            }
            val diffs = read { sourceDiffs(before, current, oldFile, change.currentFile) }
            val changedRanges = diffs.mapNotNull { it.currentRange }

            val fileCandidates = read {
                val classOwner = change.currentFile as? PsiClassOwner ?: return@read emptyList()
                val recognizer = CompositeApiClassRecognizer.getInstance(project)
                classOwner.classes.flatMap { psiClass ->
                    if (!recognizer.isApiClass(psiClass)) return@flatMap emptyList()
                    val className = psiClass.qualifiedName ?: return@flatMap emptyList()
                    if (classHeaderIntersects(psiClass, changedRanges)) {
                        return@flatMap listOf(
                            ChangedApiCandidate(
                                ControllerSelector(className, current.lineNumber(psiClass.textOffset)),
                                "Controller declaration changed"
                            )
                        )
                    }
                    psiClass.methods.mapNotNull { method ->
                        if (changedRanges.none(method.textRange::intersects)) return@mapNotNull null
                        ChangedApiCandidate(
                            ControllerMethodSelector(
                                className,
                                method.name,
                                method.parameterList.parameters.map { it.type.canonicalText },
                                current.lineNumber(method.textOffset)
                            ),
                            "method source changed"
                        )
                    }
                }
            }
            candidates += fileCandidates

            val deletedRanges = diffs.mapNotNull { it.beforeRange }
            if (deletedRanges.isNotEmpty()) {
                val deletedCandidates = read {
                    val currentOwner = change.currentFile as? PsiClassOwner ?: return@read emptyList()
                    val oldOwner = oldFile as? PsiClassOwner ?: return@read emptyList()
                    val currentClasses = currentOwner.classes.associateBy { it.qualifiedName }
                    val recognizer = CompositeApiClassRecognizer.getInstance(project)

                    oldOwner.classes.flatMap { oldClass ->
                        val className = oldClass.qualifiedName ?: return@flatMap emptyList()
                        val currentClass = currentClasses[className] ?: return@flatMap emptyList()
                        if (!recognizer.isApiClass(currentClass)) return@flatMap emptyList()
                        if (classHeaderIntersects(oldClass, deletedRanges)) {
                            return@flatMap listOf(
                                ChangedApiCandidate(
                                    ControllerSelector(
                                        className,
                                        current.lineNumber(currentClass.textOffset)
                                    ),
                                    "Controller declaration deleted"
                                )
                            )
                        }
                        oldClass.methods.mapNotNull { oldMethod ->
                            if (deletedRanges.none(oldMethod.textRange::intersects)) return@mapNotNull null
                            val parameterTypes = oldMethod.parameterList.parameters.map { it.type.canonicalText }
                            val currentMethod = currentClass.findMethodsByName(oldMethod.name, false).singleOrNull { method ->
                                method.parameterList.parameters.map { it.type.canonicalText } == parameterTypes
                            }
                            if (currentMethod == null) {
                                warnings += "$className#${oldMethod.name} was deleted or changed signature"
                                return@mapNotNull null
                            }
                            ChangedApiCandidate(
                                ControllerMethodSelector(
                                    className,
                                    currentMethod.name,
                                    parameterTypes,
                                    current.lineNumber(currentMethod.textOffset)
                                ),
                                "method source deleted"
                            )
                        }
                    }
                }
                candidates += deletedCandidates
            }
        }

        return ChangedApiCandidateResolution(candidates.distinctBy { it.selector }, warnings)
    }

    /** @requires ReadAction context */
    private fun sourceDiffs(
        before: String,
        current: String,
        oldFile: PsiFile,
        currentFile: PsiFile
    ): List<SourceDiff> {
        val indicator = EmptyProgressIndicator()
        return ComparisonManager.getInstance()
            .compareLines(before, current, ComparisonPolicy.DEFAULT, indicator)
            .flatMap { lineFragment ->
                val beforeLines = lineRange(before, lineFragment.startLine1, lineFragment.endLine1)
                val currentLines = lineRange(current, lineFragment.startLine2, lineFragment.endLine2)
                val beforeChunk = before.substring(beforeLines.startOffset, beforeLines.endOffset)
                val currentChunk = current.substring(currentLines.startOffset, currentLines.endOffset)

                ComparisonManager.getInstance()
                    .compareChars(beforeChunk, currentChunk, ComparisonPolicy.DEFAULT, indicator)
                    .mapNotNull { fragment ->
                        val beforeRange = fragment.toRange1(beforeLines.startOffset)
                        val currentRange = fragment.toRange2(currentLines.startOffset)
                        val whitespaceOnly = isPsiWhitespaceOnly(oldFile, before, beforeRange) &&
                            isPsiWhitespaceOnly(currentFile, current, currentRange)
                        if (whitespaceOnly) null else SourceDiff(beforeRange, currentRange)
                    }
            }
    }

    /** @requires ReadAction context */
    private fun isPsiWhitespaceOnly(file: PsiFile, text: String, range: TextRange?): Boolean {
        if (range == null) return true
        return (range.startOffset until range.endOffset).all { offset ->
            text[offset].isWhitespace() && file.findElementAt(offset) is PsiWhiteSpace
        }
    }

    private fun lineRange(text: String, startLine: Int, endLine: Int): TextRange {
        val starts = buildList {
            add(0)
            text.forEachIndexed { index, char -> if (char == '\n') add(index + 1) }
        }
        return TextRange(starts.getOrElse(startLine) { text.length }, starts.getOrElse(endLine) { text.length })
    }

    private fun String.lineNumber(offset: Int): Int = take(offset).count { it == '\n' } + 1

    private fun classHeaderIntersects(psiClass: PsiClass, ranges: List<TextRange>): Boolean =
        listOfNotNull(
            psiClass.docComment,
            psiClass.modifierList,
            psiClass.nameIdentifier,
            psiClass.extendsList,
            psiClass.implementsList
        ).any { element -> ranges.any(element.textRange::intersects) }

    private fun com.intellij.diff.fragments.DiffFragment.toRange1(baseOffset: Int): TextRange? =
        if (startOffset1 == endOffset1) null else TextRange(baseOffset + startOffset1, baseOffset + endOffset1)

    private fun com.intellij.diff.fragments.DiffFragment.toRange2(baseOffset: Int): TextRange? =
        if (startOffset2 == endOffset2) null else TextRange(baseOffset + startOffset2, baseOffset + endOffset2)

    private data class SourceDiff(
        val beforeRange: TextRange?,
        val currentRange: TextRange?
    )
}
