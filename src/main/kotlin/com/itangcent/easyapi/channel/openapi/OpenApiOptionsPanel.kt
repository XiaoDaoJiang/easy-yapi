package com.itangcent.easyapi.channel.openapi

import com.intellij.openapi.project.Project
import com.intellij.util.ui.FormBuilder
import com.itangcent.easyapi.channel.spi.ChannelOptionsPanel
import com.itangcent.easyapi.core.logging.IdeaLog
import java.awt.FlowLayout
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRadioButton

/**
 * Per-export options panel for the OpenAPI channel.
 *
 * The v1 envelope fields (`infoTitle` / `infoVersion` / `infoDescription` /
 * `serverUrl`) were removed — those values vary per project and belong in
 * rule scripts. The options panel exposes a two-way format selector:
 *
 *  - **JSON** (radio, default-selected)
 *  - **YAML** (radio)
 *
 * and a two-way document layout selector:
 *
 *  - **Single file** (radio, default-selected)
 *  - **Multiple files by Controller** (radio)
 *
 * The "Always Ask" option is NOT exposed here — the panel itself is the
 * per-export prompt, so offering "Always Ask" would be redundant (it would
 * just trigger a second `Messages.showChooseDialog` inside `export()`).
 * "Always Ask" remains available in [OpenApiSettings] as the persistent
 * default: when the quick-export path is used (no options panel shown) and
 * the setting is `ALWAYS_ASK`, `OpenApiChannel.export` prompts the user at
 * export time.
 *
 * Mirrors the shape of
 * [com.itangcent.easyapi.channel.hoppscotch.HoppscotchOptionsPanel] and
 * [com.itangcent.easyapi.channel.curl.CurlOptionsPanel] (the latter uses
 * a similar radio group for render-mode selection, including an
 * `ALWAYS_ASK`-equivalent).
 *
 * @param project the IntelliJ project context (unused today but kept for
 *  future rule-context lookups; consistent with the ChannelOptionsPanel SPI).
 * @see OpenApiChannel
 * @see OpenApiConfig
 */
class OpenApiOptionsPanel(@Suppress("UNUSED_PARAMETER") private val project: Project) :
    ChannelOptionsPanel, IdeaLog {

    private val jsonRadio = JRadioButton("JSON", true)
    private val yamlRadio = JRadioButton("YAML", false)
    private val singleFileRadio = JRadioButton("Single file", true)
    private val multiFileByControllerRadio = JRadioButton("Multiple files by Controller", false)

    init {
        ButtonGroup().apply {
            add(jsonRadio)
            add(yamlRadio)
        }
        ButtonGroup().apply {
            add(singleFileRadio)
            add(multiFileByControllerRadio)
        }
    }

    override val component: JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent(
            "Format:",
            JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(jsonRadio)
                add(yamlRadio)
            },
        )
        .addLabeledComponent(
            "Document:",
            JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(singleFileRadio)
                add(multiFileByControllerRadio)
            },
        )
        .addComponentFillVertically(JPanel(), 0)
        .panel

    override fun buildConfig(): OpenApiConfig = OpenApiConfig(
        outputFormat = if (yamlRadio.isSelected) OpenApiOutputFormat.YAML
        else OpenApiOutputFormat.JSON,
        documentMode = if (multiFileByControllerRadio.isSelected) {
            OpenApiDocumentMode.MULTI_FILE_BY_CONTROLLER
        } else {
            OpenApiDocumentMode.SINGLE_FILE
        },
    )

    /**
     * Populates both radio selections from a pre-built [OpenApiConfig].
     *
     * Resets every radio so the panel reflects [cfg]. When [cfg.outputFormat]
     * is `ALWAYS_ASK` (only reachable from [OpenApiSettings] — the panel
     * itself never produces it), the panel falls back to JSON (the
     * default-of-defaults) since there is no "Always Ask" radio to select.
     */
    fun applyConfig(cfg: OpenApiConfig) {
        jsonRadio.isSelected = cfg.outputFormat != OpenApiOutputFormat.YAML
        yamlRadio.isSelected = cfg.outputFormat == OpenApiOutputFormat.YAML
        singleFileRadio.isSelected = cfg.documentMode == OpenApiDocumentMode.SINGLE_FILE
        multiFileByControllerRadio.isSelected =
            cfg.documentMode == OpenApiDocumentMode.MULTI_FILE_BY_CONTROLLER
    }

    /** Test-visible setter for the format radio. */
    internal fun setFormat(format: OpenApiOutputFormat) {
        jsonRadio.isSelected = format != OpenApiOutputFormat.YAML
        yamlRadio.isSelected = format == OpenApiOutputFormat.YAML
    }

    /** Test-visible setter for the document layout radio. */
    internal fun setDocumentMode(mode: OpenApiDocumentMode) {
        singleFileRadio.isSelected = mode == OpenApiDocumentMode.SINGLE_FILE
        multiFileByControllerRadio.isSelected =
            mode == OpenApiDocumentMode.MULTI_FILE_BY_CONTROLLER
    }
}
