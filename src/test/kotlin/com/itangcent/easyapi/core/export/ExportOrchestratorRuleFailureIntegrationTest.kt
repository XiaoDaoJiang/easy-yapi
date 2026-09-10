package com.itangcent.easyapi.core.export

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.project.Project
import com.intellij.testFramework.registerOrReplaceServiceInstance
import com.itangcent.easyapi.channel.spi.Channel
import com.itangcent.easyapi.channel.spi.ChannelConfig
import com.itangcent.easyapi.core.cache.api.ApiIndex
import com.itangcent.easyapi.core.internal.PluginInfo.PLUGIN_ID
import com.itangcent.easyapi.core.rule.engine.RuleFailureMonitor
import com.itangcent.easyapi.testFramework.EasyApiLightCodeInsightFixtureTestCase
import com.itangcent.easyapi.testFramework.TestConfigReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/** Pins the upstream rule-monitor boundary together with fork result handling. */
class ExportOrchestratorRuleFailureIntegrationTest : EasyApiLightCodeInsightFixtureTestCase() {

    private lateinit var orchestrator: ExportOrchestrator
    private lateinit var monitor: RuleFailureMonitor
    private lateinit var channel: ReportingChannel
    private val notifications = mutableListOf<Notification>()
    private val endpoints = listOf(ApiEndpoint(name = "sync regression", metadata = httpMetadata("/sync", HttpMethod.GET)))

    override fun createConfigReader() = TestConfigReader.empty(project)

    override fun setUp() {
        super.setUp()
        val index = ApiIndex()
        project.registerOrReplaceServiceInstance(ApiIndex::class.java, index, testRootDisposable)
        runBlocking { index.updateEndpoints(endpoints) }
        monitor = RuleFailureMonitor.getInstance(project)
        channel = ReportingChannel(monitor)
        project.extensionArea.getExtensionPoint<Channel>("$PLUGIN_ID.channel")
            .registerExtension(channel, testRootDisposable)
        orchestrator = ExportOrchestrator.getInstance(project)
        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    notifications += notification
                }
            }
        )
    }

    fun testSelectionExportReportsRulesOnSuccess() = runTest {
        assertSuccessfulRun(selectionEntry = true)
    }

    fun testEndpointExportReportsRulesOnSuccess() = runTest {
        assertSuccessfulRun(selectionEntry = false)
    }

    fun testSelectionExportReportsRulesWhenResultHandlingFails() = runTest {
        assertResultHandlingFailure(selectionEntry = true)
    }

    fun testEndpointExportReportsRulesWhenResultHandlingFails() = runTest {
        assertResultHandlingFailure(selectionEntry = false)
    }

    fun testSelectionExportPreservesCancellationAndClosesMonitor() = runTest {
        assertCancellation(selectionEntry = true)
    }

    fun testEndpointExportPreservesCancellationAndClosesMonitor() = runTest {
        assertCancellation(selectionEntry = false)
    }

    fun testSelectionExportClosesMonitorWhenChannelThrows() = runTest {
        assertExportFailure(selectionEntry = true)
    }

    fun testEndpointExportClosesMonitorWhenChannelThrows() = runTest {
        assertExportFailure(selectionEntry = false)
    }

    private suspend fun export(selectionEntry: Boolean): ExportResult =
        if (selectionEntry) orchestrator.orchestrateExport(null, channel.id)
        else orchestrator.exportViaChannel(channel.id, endpoints)

    private suspend fun assertSuccessfulRun(selectionEntry: Boolean) {
        assertEquals("Successful export must stay successful", ExportResult.Success(1, "test"), export(selectionEntry))
        assertEquals("Result should be handled once", 1, channel.handleCalls)
        assertRulesReportedAndWindowClosed()
    }

    private suspend fun assertResultHandlingFailure(selectionEntry: Boolean) {
        channel.handleFailure = IllegalStateException("disk failed")
        val result = export(selectionEntry)
        assertTrue("Result-handling exceptions must become an export error", result is ExportResult.Error)
        val message = (result as ExportResult.Error).message
        assertTrue("Error must identify the channel", message.contains(channel.displayName))
        assertTrue("Error must retain the cause", message.contains("disk failed"))
        assertEquals("Result should be handled once", 1, channel.handleCalls)
        assertRulesReportedAndWindowClosed()
    }

    private suspend fun assertCancellation(selectionEntry: Boolean) {
        val cancellation = CancellationException("cancelled by user")
        channel.handleFailure = cancellation
        try {
            export(selectionEntry)
            fail("Cancellation must escape, not become an export error")
        } catch (actual: CancellationException) {
            assertSame("The original cancellation must propagate", cancellation, actual)
        }
        assertEquals("Result should be handled once", 1, channel.handleCalls)
        assertRulesReportedAndWindowClosed()
    }

    private suspend fun assertExportFailure(selectionEntry: Boolean) {
        val failure = IllegalStateException("channel failed")
        channel.exportFailure = failure
        try {
            export(selectionEntry)
            fail("Channel exceptions must retain their existing propagation contract")
        } catch (actual: IllegalStateException) {
            assertSame("The original channel failure must propagate", failure, actual)
        }
        assertEquals("A failed export must not reach result handling", 0, channel.handleCalls)
        assertRulesReportedAndWindowClosed()
    }

    private fun assertRulesReportedAndWindowClosed() {
        val warnings = notifications.filter { it.content.contains("rule evaluation failure(s)") }
        assertEquals("Rule failures must produce one aggregated notification", 1, warnings.size)
        assertTrue("Both occurrences must be counted", warnings.single().content.contains("2 rule evaluation failure(s)"))
        assertTrue("The rule and cause must be visible", warnings.single().content.contains("api.name: rule exploded"))

        // Exercise the public API: if finally failed to close the window,
        // this outside-run failure would produce an extra notification.
        val count = notifications.size
        monitor.record("outside.run", IllegalArgumentException("must not leak"))
        monitor.endRunAndNotify("Leak check")
        assertEquals("No rule failure may leak after the export finishes", count, notifications.size)
    }

    private class ReportingChannel(private val monitor: RuleFailureMonitor) : Channel {
        override val id = "sync-rule-failure-test"
        override val displayName = "Sync rule failure test"
        var handleFailure: Throwable? = null
        var exportFailure: Throwable? = null
        var handleCalls = 0

        override suspend fun export(context: ExportContext): ExportResult {
            repeat(2) { monitor.record("api.name", IllegalArgumentException("rule exploded")) }
            exportFailure?.let { throw it }
            return ExportResult.Success(context.endpoints.size, "test")
        }

        override suspend fun handleResult(
            project: Project,
            result: ExportResult.Success,
            config: ChannelConfig
        ): Boolean {
            handleCalls++
            handleFailure?.let { throw it }
            return true
        }
    }
}
