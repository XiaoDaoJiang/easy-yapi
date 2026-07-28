package com.itangcent.easyapi.core.ide.sync

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
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
        selectors: List<ControllerApiSelector>,
        indicator: ProgressIndicator? = null
    ): ListedApiResolution {
        val errors = mutableListOf<ListedApiResolutionError>()
        val resolvedControllers = resolveControllers(selectors, errors)
        if (resolvedControllers.isEmpty()) {
            console.info("Listed API resolution: controllers=0, skipped=${errors.size}")
            return ListedApiResolution(emptyList(), errors)
        }

        indicator?.text = "Scanning listed API classes..."
        val classes = resolvedControllers.map { it.psiClass }
        val scanned = ApiScanner.getInstance(project).scanClasses(classes, indicator).toList()
        val controllersByClass = resolvedControllers.associateBy { it.psiClass }
        val endpoints = mutableListOf<ApiEndpoint>()
        val matchedMethods = mutableSetOf<PsiMethod>()

        for (endpoint in scanned) {
            val controller = endpoint.sourceClass?.let(controllersByClass::get) ?: continue
            if (controller.allMethods) {
                endpoints += endpoint
                continue
            }

            val sourceMethod = endpoint.sourceMethod ?: continue
            for (resolved in controller.methods) {
                if (areMethodsRelated(sourceMethod, resolved.method)) {
                    endpoints += endpoint
                    matchedMethods += resolved.method
                    break
                }
            }
        }

        resolvedControllers
            .asSequence()
            .flatMap { it.methods.asSequence() }
            .filterNot { it.method in matchedMethods }
            .forEach { resolved ->
                errors += error(
                    resolved.selector,
                    "method '${resolved.selector.methodName}' does not produce an API endpoint"
                )
            }

        console.info(
            "Listed API resolution: controllers=${resolvedControllers.size}, " +
                "skipped=${errors.size}, endpoints=${endpoints.size}"
        )
        return ListedApiResolution(endpoints, errors)
    }

    private suspend fun resolveControllers(
        selectors: List<ControllerApiSelector>,
        errors: MutableList<ListedApiResolutionError>
    ): List<ResolvedController> {
        val javaPsiFacade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)
        val recognizer = CompositeApiClassRecognizer.getInstance(project)
        val resolvedControllers = mutableListOf<ResolvedController>()

        for (group in selectors.groupBy { it.className }.values) {
            val resolved = read {
                val className = group.first().className
                val psiClass = javaPsiFacade.findClass(className, scope)
                if (psiClass == null) {
                    group.forEach { errors += error(it, "class '$className' was not found") }
                    return@read null
                }
                if (!recognizer.isApiClass(psiClass)) {
                    group.forEach { errors += error(it, "class '$className' is not an API controller") }
                    return@read null
                }
                if (group.any { it is ControllerSelector }) {
                    return@read ResolvedController(psiClass, allMethods = true, emptyList())
                }

                val methods = group
                    .filterIsInstance<ControllerMethodSelector>()
                    .mapNotNull { resolveMethod(psiClass, it, errors) }
                if (methods.isEmpty()) null else ResolvedController(psiClass, allMethods = false, methods)
            }
            if (resolved != null) resolvedControllers += resolved
        }

        return resolvedControllers
    }

    /** @requires ReadAction context */
    private fun resolveMethod(
        psiClass: PsiClass,
        selector: ControllerMethodSelector,
        errors: MutableList<ListedApiResolutionError>
    ): ResolvedMethod? {
        val namedMethods = psiClass.findMethodsByName(selector.methodName, false).toList()
        if (namedMethods.isEmpty()) {
            errors += error(selector, "method '${selector.methodName}' was not found")
            return null
        }

        val method = if (selector.parameterTypeNames == null) {
            if (namedMethods.size != 1) {
                errors += error(selector, "method '${selector.methodName}' is overloaded; specify parameter types")
                return null
            }
            namedMethods.single()
        } else {
            namedMethods.singleOrNull { method ->
                method.parameterList.parameters.map { it.type.canonicalText } == selector.parameterTypeNames
            } ?: run {
                errors += error(selector, "method '${selector.methodName}' has no matching parameter signature")
                return null
            }
        }

        return ResolvedMethod(selector, method)
    }

    private fun error(selector: ControllerApiSelector, message: String) =
        ListedApiResolutionError("line ${selector.lineNumber}: $message")

    private data class ResolvedController(
        val psiClass: PsiClass,
        val allMethods: Boolean,
        val methods: List<ResolvedMethod>
    )

    private data class ResolvedMethod(
        val selector: ControllerMethodSelector,
        val method: PsiMethod
    )
}
