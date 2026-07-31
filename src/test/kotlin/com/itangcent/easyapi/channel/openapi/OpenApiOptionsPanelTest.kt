package com.itangcent.easyapi.channel.openapi

import com.itangcent.easyapi.testFramework.EasyApiLightCodeInsightFixtureTestCase
import java.awt.Container
import javax.swing.AbstractButton
import kotlin.reflect.full.memberProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * Round-trip tests for [OpenApiOptionsPanel].
 *
 * The panel exposes a two-way format selector (`JSON` default / `YAML`) and a
 * two-way document selector (`Single file` default /
 * `Multiple files by Controller`).
 * The "Always Ask" option is NOT exposed in the per-export panel — the panel
 * itself IS the per-export prompt, so "Always Ask" would be redundant (it
 * would just trigger a second `Messages.showChooseDialog` inside `export()`).
 * "Always Ask" remains available in [OpenApiSettings] as the persistent
 * default. The v1 envelope setters (`setTitle` / `setVersion` /
 * `setDescription` / `setServerUrl`) and the corresponding `OpenApiConfig`
 * fields were removed — those values vary per project and belong in rule
 * scripts.
 *
 * Pattern: select a radio → call [OpenApiOptionsPanel.buildConfig] →
 * assert the returned [OpenApiConfig] carries the selected format.
 * Conversely, call [OpenApiOptionsPanel.applyConfig] with a pre-built
 * [OpenApiConfig] and assert the radios reflect it.
 *
 * Uses [EasyApiLightCodeInsightFixtureTestCase] so Swing components are
 * properly initialized in an IntelliJ application context (the panel uses
 * `JRadioButton` which needs the IDEA look-and-feel).
 */
class OpenApiOptionsPanelTest : EasyApiLightCodeInsightFixtureTestCase() {

    private lateinit var panel: OpenApiOptionsPanel

    override fun setUp() {
        super.setUp()
        panel = OpenApiOptionsPanel(project)
    }

    fun testComponentIsNotNull() {
        assertNotNull(panel.component)
    }

    fun testBuildConfigReturnsChannelConfigSubtype() {
        val config = panel.buildConfig()
        assertNotNull(config)
        assertTrue("buildConfig should return OpenApiConfig, got: ${config.javaClass}", config is OpenApiConfig)
    }

    fun testDefaultBuildConfigReturnsJsonFormat() {
        // JSON is the default-selected radio.
        val config = panel.buildConfig() as OpenApiConfig
        assertEquals(OpenApiOutputFormat.JSON, config.outputFormat)
    }

    fun testDefaultBuildConfigReturnsSingleFileMode() {
        val config = panel.buildConfig() as OpenApiConfig
        assertEquals(OpenApiDocumentMode.SINGLE_FILE, config.documentMode)
    }

    fun testDocumentModeOptionsHaveExpectedLabels() {
        val buttonLabels = panel.component
            .descendants()
            .filterIsInstance<AbstractButton>()
            .map { it.text }
            .toSet()
        assertTrue("Single file option should be visible", "Single file" in buttonLabels)
        assertTrue(
            "Multiple files by Controller option should be visible",
            "Multiple files by Controller" in buttonLabels,
        )
    }

    fun testBuildConfigReturnsFormatAndDocumentModeProperties() {
        // The v1 envelope fields (infoTitle / infoVersion / infoDescription /
        // serverUrl) were removed. Reflection-level guard so
        // accidental re-introduction is caught by this test.
        val propertyNames = OpenApiConfig::class.memberProperties.map { it.name }.toSet()
        assertEquals(
            "OpenApiConfig should have format and document mode properties, got: $propertyNames",
            setOf("outputFormat", "documentMode"),
            propertyNames,
        )
    }


    fun testBuildConfigJsonFormatWhenSet() {
        panel.setFormat(OpenApiOutputFormat.JSON)
        val config = panel.buildConfig() as OpenApiConfig
        assertEquals(OpenApiOutputFormat.JSON, config.outputFormat)
    }

    fun testBuildConfigYamlFormatWhenSet() {
        panel.setFormat(OpenApiOutputFormat.YAML)
        val config = panel.buildConfig() as OpenApiConfig
        assertEquals(OpenApiOutputFormat.YAML, config.outputFormat)
    }

    fun testBuildConfigMultiFileModeWhenSet() {
        panel.setDocumentMode(OpenApiDocumentMode.MULTI_FILE_BY_CONTROLLER)

        val config = panel.buildConfig() as OpenApiConfig

        assertEquals(OpenApiOutputFormat.JSON, config.outputFormat)
        assertEquals(OpenApiDocumentMode.MULTI_FILE_BY_CONTROLLER, config.documentMode)
    }

    fun testSetDocumentModePreservesFormatSelection() {
        panel.setFormat(OpenApiOutputFormat.YAML)
        panel.setDocumentMode(OpenApiDocumentMode.MULTI_FILE_BY_CONTROLLER)

        val config = panel.buildConfig() as OpenApiConfig

        assertEquals(OpenApiOutputFormat.YAML, config.outputFormat)
        assertEquals(OpenApiDocumentMode.MULTI_FILE_BY_CONTROLLER, config.documentMode)
    }

    fun testBuildConfigAlwaysAskFallsBackToJson() {
        // The panel has no "Always Ask" radio. When applyConfig receives an
        // ALWAYS_ASK config (only reachable from OpenApiSettings), the panel
        // falls back to JSON (the default-of-defaults).
        panel.setFormat(OpenApiOutputFormat.ALWAYS_ASK)
        val config = panel.buildConfig() as OpenApiConfig
        assertEquals(
            "ALWAYS_ASK should fall back to JSON in the options panel",
            OpenApiOutputFormat.JSON,
            config.outputFormat,
        )
    }

    fun testApplyConfigPopulatesBothRadioGroupsFromConfig() {
        val cfg = OpenApiConfig(
            outputFormat = OpenApiOutputFormat.YAML,
            documentMode = OpenApiDocumentMode.MULTI_FILE_BY_CONTROLLER,
        )
        panel.applyConfig(cfg)

        val built = panel.buildConfig() as OpenApiConfig
        assertEquals(OpenApiOutputFormat.YAML, built.outputFormat)
        assertEquals(OpenApiDocumentMode.MULTI_FILE_BY_CONTROLLER, built.documentMode)
    }

    fun testApplyConfigWithDefaultsSelectsJson() {
        // First populate, then apply a default config — JSON should be selected.
        panel.setFormat(OpenApiOutputFormat.YAML)
        panel.applyConfig(OpenApiConfig())

        val built = panel.buildConfig() as OpenApiConfig
        assertEquals(
            "Default OpenApiConfig (ALWAYS_ASK) should select JSON in the panel",
            OpenApiOutputFormat.JSON,
            built.outputFormat,
        )
    }

    fun testRoundTripFromUItoConfigAndBack() {
        // Set UI → buildConfig → applyConfig → buildConfig → assert equality.
        panel.setFormat(OpenApiOutputFormat.YAML)

        val firstConfig = panel.buildConfig() as OpenApiConfig
        // Apply to a fresh panel and verify the second build matches the first.
        val freshPanel = OpenApiOptionsPanel(project)
        freshPanel.applyConfig(firstConfig)

        val secondConfig = freshPanel.buildConfig() as OpenApiConfig
        assertEquals(firstConfig, secondConfig)
    }

    fun testRoundTripBothFormats() {
        // Round-trip each panel-selectable format (JSON, YAML) through a fresh panel.
        for (format in listOf(OpenApiOutputFormat.JSON, OpenApiOutputFormat.YAML)) {
            panel.setFormat(format)
            val config = panel.buildConfig() as OpenApiConfig
            assertEquals(format, config.outputFormat)

            val freshPanel = OpenApiOptionsPanel(project)
            freshPanel.applyConfig(config)
            assertEquals(format, (freshPanel.buildConfig() as OpenApiConfig).outputFormat)
        }
    }

    fun testRoundTripBothDocumentModes() {
        for (documentMode in OpenApiDocumentMode.values()) {
            panel.setDocumentMode(documentMode)
            val config = panel.buildConfig() as OpenApiConfig
            assertEquals(documentMode, config.documentMode)

            val freshPanel = OpenApiOptionsPanel(project)
            freshPanel.applyConfig(config)
            assertEquals(documentMode, (freshPanel.buildConfig() as OpenApiConfig).documentMode)
        }
    }

    fun testOnShownDoesNotThrow() {
        // The SPI default is a no-op; the panel may override it to initialize
        // defaults. Either way, it should not throw.
        panel.onShown()
    }

    private fun Container.descendants(): Sequence<java.awt.Component> = sequence {
        components.forEach { child ->
            yield(child)
            if (child is Container) {
                yieldAll(child.descendants())
            }
        }
    }
}
