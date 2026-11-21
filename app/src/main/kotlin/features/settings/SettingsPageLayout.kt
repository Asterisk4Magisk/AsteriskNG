// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs

internal class SettingsPageSections {
    private class Section(val key: String, val content: @Composable () -> Unit)

    private val sections = mutableListOf<Section>()

    fun settingsSection(key: String, content: @Composable () -> Unit) {
        sections.add(Section(key, content))
    }

    fun addTo(grid: LazyGridScope, twoColumns: Boolean) {
        with(grid) {
            if (twoColumns) {
                item(key = "settings_wide_sections") {
                    WideSettingsSections {
                        sections.forEach { section ->
                            key(section.key) { Column { section.content() } }
                        }
                    }
                }
            } else {
                sections.forEach { section ->
                    item(key = section.key) { Column { section.content() } }
                }
            }
        }
    }
}

@Composable
private fun WideSettingsSections(content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = 12.dp.roundToPx()
        val columnWidth = (width - gap) / 2
        val placeables = measurables.map { it.measure(Constraints.fixedWidth(columnWidth)) }
        val heights = placeables.map { it.height }
        val columns = settingsSectionColumns(heights, acceptableDifference = 64.dp.roundToPx())
        val bottoms = IntArray(2)
        val offsets = IntArray(placeables.size) { index ->
            val column = columns[index]
            bottoms[column].also { bottoms[column] += heights[index] }
        }

        layout(width, constraints.constrainHeight(maxOf(bottoms[0], bottoms[1]))) {
            placeables.forEachIndexed { index, section ->
                section.placeRelative(columns[index] * (width - columnWidth), offsets[index])
            }
        }
    }
}

private fun settingsSectionColumns(heights: List<Int>, acceptableDifference: Int): IntArray {
    val bottoms = IntArray(2)
    val columns = IntArray(heights.size) { index ->
        val column = if (bottoms[0] <= bottoms[1]) 0 else 1
        bottoms[column] += heights[index]
        column
    }
    val difference = bottoms[0] - bottoms[1]
    if (abs(difference) <= acceptableDifference) return columns

    // Only the last three groups can switch columns. Each column keeps its group order.
    val tailSize = minOf(3, heights.size)
    val tailStart = heights.size - tailSize
    var bestMask = 0
    var bestDifference = abs(difference)
    for (mask in 1 until (1 shl tailSize)) {
        var candidateDifference = difference
        for (tailIndex in 0 until tailSize) {
            if (mask and (1 shl tailIndex) != 0) {
                val index = tailStart + tailIndex
                val movedHeight = if (columns[index] == 0) -heights[index] else heights[index]
                candidateDifference += 2 * movedHeight
            }
        }
        val gap = abs(candidateDifference)
        if (gap < bestDifference || (gap == bestDifference && mask.countOneBits() < bestMask.countOneBits())) {
            bestMask = mask
            bestDifference = gap
        }
    }
    for (tailIndex in 0 until tailSize) {
        if (bestMask and (1 shl tailIndex) != 0) {
            val index = tailStart + tailIndex
            columns[index] = 1 - columns[index]
        }
    }
    return columns
}
