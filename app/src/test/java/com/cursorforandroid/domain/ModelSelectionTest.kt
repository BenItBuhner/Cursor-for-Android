package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModelSelectionTest {

    @Test
    fun `a blank id omits the model field`() {
        assertThat(ModelSelection.wire(null)).isNull()
        assertThat(ModelSelection.wire("")).isNull()
        assertThat(ModelSelection.wire("  ")).isNull()
    }

    @Test
    fun `retired Composer 2 ids remap and composer-2-fast sets fast`() {
        assertThat(ModelSelection.wire("composer-2")).isEqualTo("composer-2.5" to emptyList<ModelParam>())
        assertThat(ModelSelection.wire("Composer-2", listOf(ModelParam("fast", "false"))))
            .isEqualTo("composer-2.5" to listOf(ModelParam("fast", "false")))
        assertThat(ModelSelection.wire("composer-2-fast"))
            .isEqualTo("composer-2.5" to listOf(ModelParam("fast", "true")))
        assertThat(ModelSelection.wire("composer-2-fast", listOf(ModelParam("fast", "false"))))
            .isEqualTo("composer-2.5" to listOf(ModelParam("fast", "true")))
    }

    @Test
    fun `auto-smart and the desktop default require optimize_for, and auto is left alone`() {
        assertThat(ModelSelection.wire("auto-smart"))
            .isEqualTo("auto-smart" to listOf(ModelParam("optimize_for", "balanced")))
        assertThat(ModelSelection.wire("default"))
            .isEqualTo("auto-smart" to listOf(ModelParam("optimize_for", "balanced")))
        assertThat(ModelSelection.wire("auto-smart", listOf(ModelParam("optimize_for", "cost"))))
            .isEqualTo("auto-smart" to listOf(ModelParam("optimize_for", "cost")))
        assertThat(ModelSelection.wire("auto-smart", listOf(ModelParam("optimize_for", "default"))))
            .isEqualTo("auto-smart" to listOf(ModelParam("optimize_for", "balanced")))
        assertThat(ModelSelection.wire("auto")).isEqualTo("auto" to emptyList<ModelParam>())
        assertThat(ModelSelection.wire("gpt-5.6", listOf(ModelParam("effort", "high"))))
            .isEqualTo("gpt-5.6" to listOf(ModelParam("effort", "high")))
    }
}
