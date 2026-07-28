package com.itangcent.easyapi.core.ide.sync

internal data class ControllerMethodSelector(
    val className: String,
    val methodName: String,
    val parameterTypeNames: List<String>?,
    val lineNumber: Int
)

internal data class ManifestParseError(
    val lineNumber: Int,
    val message: String
)

internal data class ManifestParseResult(
    val selectors: List<ControllerMethodSelector>,
    val errors: List<ManifestParseError>
)

internal object ControllerApiManifest {

    private val identifier = Regex("[A-Za-z_$][\\w$]*")
    private val qualifiedName = Regex("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*")
    private val parameterType = Regex("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*(?:\\[\\])*(?:\\.\\.\\.)?")

    fun parse(content: String): ManifestParseResult {
        val selectors = mutableListOf<ControllerMethodSelector>()
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

    private fun parseLine(line: String, lineNumber: Int): Result<ControllerMethodSelector> = runCatching {
        val separator = line.indexOf('#')
        require(separator > 0 && separator == line.lastIndexOf('#')) { FORMAT_ERROR }

        val className = line.substring(0, separator).trim()
        val methodSpec = line.substring(separator + 1).trim()
        require(qualifiedName.matches(className)) { "Invalid Controller class name: '$className'" }
        require(methodSpec.isNotEmpty()) { FORMAT_ERROR }

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
            rawParameters.split(',').map { it.trim() }.also { types ->
                require(types.none { it.isEmpty() }) { "Parameter type must not be empty" }
                require(types.all(parameterType::matches)) { "Invalid parameter type list: '$rawParameters'" }
            }
        }

        ControllerMethodSelector(className, methodName, parameters, lineNumber)
    }

    private const val FORMAT_ERROR =
        "Expected <fully.qualified.Controller>#<method> or <fully.qualified.Controller>#<method>(<parameter.types>)"
}
