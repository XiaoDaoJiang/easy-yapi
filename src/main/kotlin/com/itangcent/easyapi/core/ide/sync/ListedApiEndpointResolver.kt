package com.itangcent.easyapi.core.ide.sync

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.itangcent.easyapi.core.dashboard.ApiScanner
import com.itangcent.easyapi.core.export.ApiEndpoint
import com.itangcent.easyapi.core.export.recognizer.CompositeApiClassRecognizer
import com.itangcent.easyapi.core.internal.threading.read
import com.itangcent.easyapi.core.logging.console
import com.itangcent.easyapi.core.psi.type.areMethodsRelated

internal data class ListedApiResolutionError(val message: String)

internal data class ListedApiResolution(
    val endpoints: List<ApiEndpoint>,
    val errors: List<ListedApiResolutionError>
)

internal class ListedApiEndpointResolver(private val project: Project) {

    private val console = project.console

    suspend fun resolve(
        selectors: List<ControllerMethodSelector>,
        indicator: ProgressIndicator? = null
    ): ListedApiResolution {
        val errors = mutableListOf<ListedApiResolutionError>()
        val resolvedMethods = resolveMethods(selectors, errors)
        if (resolvedMethods.isEmpty()) {
            console.info("Listed API resolution: resolved=0, skipped=${errors.size}")
            return ListedApiResolution(emptyList(), errors)
        }

        indicator?.text = "Scanning listed API classes..."
        val classes = resolvedMethods.mapNotNull { it.method.containingClass }.distinct()
        val scanned = ApiScanner.getInstance(project).scanClasses(classes, indicator).toList()
        val endpoints = mutableListOf<ApiEndpoint>()
        val matchedMethods = mutableSetOf<PsiMethod>()

        for (endpoint in scanned) {
            val sourceMethod = endpoint.sourceMethod ?: continue
            for (resolved in resolvedMethods) {
                if (areMethodsRelated(sourceMethod, resolved.method)) {
                    endpoints += endpoint
                    matchedMethods += resolved.method
                    break
                }
            }
        }

        resolvedMethods
            .filterNot { it.method in matchedMethods }
            .forEach { resolved ->
                errors += error(
                    resolved.selector,
                    "method '${resolved.selector.methodName}' does not produce an API endpoint"
                )
            }

        console.info(
            "Listed API resolution: resolved=${resolvedMethods.size}, " +
                "skipped=${errors.size}, endpoints=${endpoints.size}"
        )
        return ListedApiResolution(endpoints, errors)
    }

    /** @requires ReadAction context */
    private suspend fun resolveMethods(
        selectors: List<ControllerMethodSelector>,
        errors: MutableList<ListedApiResolutionError>
    ): List<ResolvedSelector> = read {
        val javaPsiFacade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)
        val recognizer = CompositeApiClassRecognizer.getInstance(project)

        selectors.mapNotNull { selector ->
            val psiClass = javaPsiFacade.findClass(selector.className, scope)
            if (psiClass == null) {
                errors += error(selector, "class '${selector.className}' was not found")
                return@mapNotNull null
            }
            if (!recognizer.isApiClass(psiClass)) {
                errors += error(selector, "class '${selector.className}' is not an API controller")
                return@mapNotNull null
            }

            val namedMethods = psiClass.findMethodsByName(selector.methodName, false).toList()
            if (namedMethods.isEmpty()) {
                errors += error(selector, "method '${selector.methodName}' was not found")
                return@mapNotNull null
            }

            val method = if (selector.parameterTypeNames == null) {
                if (namedMethods.size != 1) {
                    errors += error(selector, "method '${selector.methodName}' is overloaded; specify parameter types")
                    return@mapNotNull null
                }
                namedMethods.single()
            } else {
                namedMethods.singleOrNull { method ->
                    method.parameterList.parameters.map { it.type.canonicalText } == selector.parameterTypeNames
                } ?: run {
                    errors += error(selector, "method '${selector.methodName}' has no matching parameter signature")
                    return@mapNotNull null
                }
            }

            ResolvedSelector(selector, method)
        }
    }

    private fun error(selector: ControllerMethodSelector, message: String) =
        ListedApiResolutionError("line ${selector.lineNumber}: $message")

    private data class ResolvedSelector(
        val selector: ControllerMethodSelector,
        val method: PsiMethod
    )
}
