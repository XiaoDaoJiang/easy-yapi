package com.itangcent.easyapi.core.ide.sync

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE

internal sealed interface ControllerApiSelector {
    val className: String
    val lineNumber: Int
}

internal data class ControllerSelector(
    override val className: String,
    override val lineNumber: Int
) : ControllerApiSelector

internal data class ControllerMethodSelector(
    override val className: String,
    val methodName: String,
    val parameterTypeNames: List<String>?,
    override val lineNumber: Int
) : ControllerApiSelector

internal data class ManifestParseError(
    val lineNumber: Int,
    val message: String
)

internal data class ManifestParseResult(
    val selectors: List<ControllerApiSelector>,
    val errors: List<ManifestParseError>
)

internal data class ManifestAppendResult(
    val appendedSelectors: List<String> = emptyList(),
    val errors: List<ManifestParseError> = emptyList(),
    val rejection: String? = null
) {
    val written: Boolean get() = appendedSelectors.isNotEmpty()
}

internal object ControllerApiManifest {

    private val identifier = Regex("[A-Za-z_$][\\w$]*")
    private val qualifiedName = Regex("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*")
    private val parameterType = Regex(
        "[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*" +
            "(?:<[\\w$?.,<>\\[\\] ]+>)?(?:\\[\\])*(?:\\.\\.\\.)?"
    )

    fun parse(content: String): ManifestParseResult {
        val selectors = mutableListOf<ControllerApiSelector>()
        val errors = mutableListOf<ManifestParseError>()

        content.lineSequence().forEachIndexed { index, sourceLine ->
            val lineNumber = index + 1
            val line = sourceLine.trim()
            if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed

            parseLine(line, lineNumber)
                .onSuccess(selectors::add)
                .onFailure { errors += ManifestParseError(lineNumber, it.message ?: "Invalid selector") }
        }

        return ManifestParseResult(selectors, errors)
    }

    fun append(path: Path, candidates: Collection<ChangedApiCandidate>): ManifestAppendResult {
        if (candidates.isEmpty()) return ManifestAppendResult()

        val missingTarget = !Files.exists(path, NOFOLLOW_LINKS)
        val existingContent = when {
            missingTarget -> ""
            Files.isRegularFile(path, NOFOLLOW_LINKS) -> Files.readString(path, UTF_8)
            else -> return ManifestAppendResult(rejection = "Manifest target is not a regular file: $path")
        }
        val existing = parse(existingContent)
        if (existing.errors.isNotEmpty()) return ManifestAppendResult(errors = existing.errors)

        val missing = mergeCandidates(existing.selectors, candidates.map { it.selector })
        if (missing.isEmpty()) return ManifestAppendResult()

        val lines = missing.map(::format)
        if (missingTarget) path.parent?.let(Files::createDirectories)
        val separator = if (existingContent.isEmpty() || existingContent.endsWith('\n')) "" else "\n"
        Files.writeString(path, separator + lines.joinToString("\n", postfix = "\n"), UTF_8, CREATE, APPEND)
        return ManifestAppendResult(appendedSelectors = lines)
    }

    private fun mergeCandidates(
        existing: List<ControllerApiSelector>,
        candidates: List<ControllerApiSelector>
    ): List<ControllerApiSelector> {
        val incoming = candidates.groupBy { it.className }.flatMap { (_, selectors) ->
            selectors.filterIsInstance<ControllerSelector>().firstOrNull()?.let(::listOf)
                ?: selectors.distinctBy(::selectorKey)
        }
        return incoming.filterNot { candidate -> existing.any { it.covers(candidate) } }
    }

    private fun format(selector: ControllerApiSelector): String = when (selector) {
        is ControllerSelector -> "${selector.className}#*"
        is ControllerMethodSelector -> "${selector.className}#${selector.methodName}" +
            "(${selector.parameterTypeNames.orEmpty().joinToString(",")})"
    }

    private fun selectorKey(selector: ControllerApiSelector): Any = when (selector) {
        is ControllerSelector -> selector.className
        is ControllerMethodSelector -> listOf(selector.className, selector.methodName, selector.parameterTypeNames)
    }

    private fun ControllerApiSelector.covers(candidate: ControllerApiSelector): Boolean = when {
        className != candidate.className -> false
        this is ControllerSelector -> true
        this !is ControllerMethodSelector -> false
        candidate !is ControllerMethodSelector -> false
        else -> methodName == candidate.methodName &&
            parameterTypeNames == candidate.parameterTypeNames
    }

    private fun parseLine(line: String, lineNumber: Int): Result<ControllerApiSelector> = runCatching {
        val hashSeparator = line.indexOf('#')
        val separator = if (hashSeparator >= 0) {
            require(hashSeparator > 0 && hashSeparator == line.lastIndexOf('#')) { FORMAT_ERROR }
            hashSeparator
        } else {
            val parametersStart = line.indexOf('(').takeIf { it >= 0 } ?: line.length
            line.lastIndexOf('.', parametersStart - 1)
        }
        require(separator > 0) { FORMAT_ERROR }

        val className = line.substring(0, separator).trim()
        val methodSpec = line.substring(separator + 1).trim()
        require(qualifiedName.matches(className)) { "Invalid Controller class name: '$className'" }
        require(methodSpec.isNotEmpty()) { FORMAT_ERROR }

        if (methodSpec == "*") {
            return@runCatching ControllerSelector(className, lineNumber)
        }

        val openParenthesis = methodSpec.indexOf('(')
        if (openParenthesis < 0) {
            require(')' !in methodSpec && identifier.matches(methodSpec)) { FORMAT_ERROR }
            return@runCatching ControllerMethodSelector(className, methodSpec, null, lineNumber)
        }

        require(methodSpec.endsWith(')') && methodSpec.indexOf(')') == methodSpec.lastIndex) { FORMAT_ERROR }
        val methodName = methodSpec.substring(0, openParenthesis).trim()
        require(identifier.matches(methodName)) { "Invalid method name: '$methodName'" }

        val rawParameters = methodSpec.substring(openParenthesis + 1, methodSpec.lastIndex)
        val parameters = if (rawParameters.isBlank()) {
            emptyList()
        } else {
            splitParameterTypes(rawParameters).also { types ->
                require(types.none { it.isEmpty() }) { "Parameter type must not be empty" }
                require(types.all(parameterType::matches)) { "Invalid parameter type list: '$rawParameters'" }
            }
        }

        ControllerMethodSelector(className, methodName, parameters, lineNumber)
    }

    private fun splitParameterTypes(rawParameters: String): List<String> {
        val parameters = mutableListOf<String>()
        var genericDepth = 0
        var parameterStart = 0

        rawParameters.forEachIndexed { index, char ->
            when (char) {
                '<' -> genericDepth++
                '>' -> {
                    require(genericDepth > 0) { "Invalid parameter type list: '$rawParameters'" }
                    genericDepth--
                }

                ',' -> if (genericDepth == 0) {
                    parameters += rawParameters.substring(parameterStart, index).trim()
                    parameterStart = index + 1
                }
            }
        }
        require(genericDepth == 0) { "Invalid parameter type list: '$rawParameters'" }
        parameters += rawParameters.substring(parameterStart).trim()
        return parameters
    }

    private const val FORMAT_ERROR =
        "Expected <fully.qualified.Controller>[#.][<method>|*] or " +
            "<fully.qualified.Controller>[#.]<method>(<parameter.types>)"
}
