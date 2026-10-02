package com.cursorforandroid.ui.components

import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Where an anchored picker and its submenus go, and when a picker is a sheet instead: pure, so no window needed. */
class PickerGeometryTest {

    private val area = IntRect(0, 0, 1000, 2000)
    private val gap = 8

    @Test
    fun `a picker opens below its anchor, lined up with its start, growing from its centre`() {
        val anchor = IntRect(100, 200, 300, 260)
        val placed = PickerGeometry.place(anchor, IntSize(400, 600), area, gap, ltr = true)
        assertThat(placed.offset).isEqualTo(IntOffset(100, 268))
        assertThat(placed.below).isTrue()
        assertThat(placed.origin).isEqualTo(TransformOrigin(100f / 400f, 0f))
    }

    @Test
    fun `a picker with no room below opens above, growing up from the anchor`() {
        val anchor = IntRect(100, 1800, 300, 1860)
        val placed = PickerGeometry.place(anchor, IntSize(400, 600), area, gap, ltr = true)
        assertThat(placed.offset).isEqualTo(IntOffset(100, 1800 - gap - 600))
        assertThat(placed.below).isFalse()
        assertThat(placed.origin.pivotFractionY).isEqualTo(1f)
    }

    @Test
    fun `a picker that fits neither side takes the roomier one and is clamped into the area`() {
        val anchor = IntRect(100, 1200, 300, 1260)
        val placed = PickerGeometry.place(anchor, IntSize(400, 1500), area, gap, ltr = true)
        assertThat(placed.below).isFalse()
        assertThat(placed.offset.y).isEqualTo(0)
    }

    @Test
    fun `an anchor near the end lines the picker up with its end instead`() {
        val anchor = IntRect(800, 200, 980, 260)
        val placed = PickerGeometry.place(anchor, IntSize(400, 600), area, gap, ltr = true)
        assertThat(placed.offset.x).isEqualTo(980 - 400)
    }

    @Test
    fun `right to left lines the picker up with the anchor's right edge`() {
        val anchor = IntRect(600, 200, 900, 260)
        val placed = PickerGeometry.place(anchor, IntSize(400, 600), area, gap, ltr = false)
        assertThat(placed.offset.x).isEqualTo(500)
    }

    @Test
    fun `a picker wider than the room either way is clamped to the area's start`() {
        val anchor = IntRect(400, 200, 600, 260)
        val placed = PickerGeometry.place(anchor, IntSize(900, 600), IntRect(50, 0, 1000, 2000), gap, ltr = true)
        assertThat(placed.offset.x).isEqualTo(100)
    }

    @Test
    fun `no anchor, a narrow window or no room by the anchor makes a sheet`() {
        val anchor = IntRect(100, 200, 300, 260)
        assertThat(PickerGeometry.presentation(null, area, gap, minRoom = 500, minWidth = 600)).isEqualTo(PickerPresentation.Sheet)
        assertThat(PickerGeometry.presentation(anchor, IntRect(0, 0, 500, 2000), gap, minRoom = 500, minWidth = 600)).isEqualTo(PickerPresentation.Sheet)
        assertThat(PickerGeometry.presentation(IntRect(100, 0, 300, 1990), area, gap, minRoom = 500, minWidth = 600)).isEqualTo(PickerPresentation.Sheet)
        assertThat(PickerGeometry.presentation(anchor, IntRect(0, 0, 1000, 400), gap, minRoom = 500, minWidth = 600)).isEqualTo(PickerPresentation.Sheet)
        assertThat(PickerGeometry.presentation(anchor, area, gap, minRoom = 500, minWidth = 600)).isEqualTo(PickerPresentation.Anchored)
    }

    @Test
    fun `the tallest a picker may be is the roomier side of its anchor`() {
        assertThat(PickerGeometry.maxHeight(IntRect(0, 500, 10, 560), area, gap)).isEqualTo(2000 - 568)
        assertThat(PickerGeometry.maxHeight(IntRect(0, 1700, 10, 1760), area, gap)).isEqualTo(1692)
    }

    @Test
    fun `a submenu cascades where it fits beside the picker, on either side`() {
        assertThat(PickerGeometry.cascades(IntRect(100, 0, 500, 800), width = 400, area, gap)).isTrue()
        assertThat(PickerGeometry.cascades(IntRect(500, 0, 900, 800), width = 400, area, gap)).isTrue()
        assertThat(PickerGeometry.cascades(IntRect(300, 0, 700, 800), width = 400, area, gap)).isFalse()
    }

    @Test
    fun `a submenu opens on the picker's trailing side, its first row level with the row that opened it`() {
        val parent = IntRect(100, 100, 500, 900)
        val row = IntRect(108, 300, 492, 360)
        val placed = PickerGeometry.placeSubmenu(row, parent, IntSize(300, 400), area, gap, inset = 4, ltr = true)!!
        assertThat(placed.offset).isEqualTo(IntOffset(508, 296))
        assertThat(placed.origin.pivotFractionX).isEqualTo(0f)
    }

    @Test
    fun `with no room after it, a submenu opens before the picker and grows from its far edge`() {
        val parent = IntRect(600, 100, 950, 900)
        val row = IntRect(608, 300, 942, 360)
        val placed = PickerGeometry.placeSubmenu(row, parent, IntSize(300, 400), area, gap, inset = 4, ltr = true)!!
        assertThat(placed.offset.x).isEqualTo(600 - gap - 300)
        assertThat(placed.origin.pivotFractionX).isEqualTo(1f)
    }

    @Test
    fun `a submenu that fits on neither side has no place beside the picker`() {
        val parent = IntRect(300, 100, 700, 900)
        assertThat(PickerGeometry.placeSubmenu(IntRect(308, 300, 692, 360), parent, IntSize(400, 400), area, gap, inset = 4, ltr = true)).isNull()
    }

    @Test
    fun `a submenu near the bottom is pushed up to stay on screen`() {
        val parent = IntRect(100, 1000, 500, 1990)
        val placed = PickerGeometry.placeSubmenu(IntRect(108, 1900, 492, 1960), parent, IntSize(300, 600), area, gap, inset = 4, ltr = true)!!
        assertThat(placed.offset.y).isEqualTo(2000 - 600)
    }
}

/** Where the keyboard's highlight starts and goes. */
class PickerNavigationTest {

    private val rows: List<PickerEntry> = listOf(
        PickerSection("s", "Fruit"),
        PickerItem("apple", "Apple", onPick = {}),
        PickerItem("banana", "Banana", onPick = {}),
        PickerItem("cherry", "Cherry", onPick = {}, selected = true),
        PickerDivider("d"),
        PickerItem("grape", "Grape", onPick = {}, enabled = false),
        PickerNote("n", "A note"),
        PickerAction("add", "Add fruit", onClick = {}),
    )

    @Test
    fun `the highlight starts on the selection`() {
        assertThat(PickerNavigation.initial(rows)).isEqualTo(3)
    }

    @Test
    fun `with nothing selected it starts on the first row a key can land on`() {
        assertThat(PickerNavigation.initial(rows.map { if (it is PickerItem) it.copy(selected = false) else it })).isEqualTo(1)
        assertThat(PickerNavigation.initial(listOf(PickerNote("n", "Nothing here")))).isEqualTo(-1)
    }

    @Test
    fun `moves skip headers, hairlines, notes and disabled rows, and go round the ends`() {
        assertThat(PickerNavigation.move(rows, 3, 1)).isEqualTo(7)
        assertThat(PickerNavigation.move(rows, 7, 1)).isEqualTo(1)
        assertThat(PickerNavigation.move(rows, 1, -1)).isEqualTo(7)
        assertThat(PickerNavigation.move(rows, -1, 1)).isEqualTo(1)
        assertThat(PickerNavigation.move(rows, -1, -1)).isEqualTo(7)
    }

    @Test
    fun `a busy action is not a stop`() {
        val busy = rows.map { if (it is PickerAction) it.copy(busy = true) else it }
        assertThat(PickerNavigation.move(busy, 3, 1)).isEqualTo(1)
    }

    @Test
    fun `type-ahead goes to the next row starting with the letter, round the ends`() {
        val berries = rows + PickerItem("blueberry", "Blueberry", onPick = {})
        assertThat(PickerNavigation.typeAhead(berries, 1, "b")).isEqualTo(2)
        assertThat(PickerNavigation.typeAhead(berries, 2, "B")).isEqualTo(8)
        assertThat(PickerNavigation.typeAhead(berries, 8, "b")).isEqualTo(2)
        assertThat(PickerNavigation.typeAhead(berries, 1, "g")).isEqualTo(-1)
        assertThat(PickerNavigation.typeAhead(berries, 1, " ")).isEqualTo(-1)
    }

    @Test
    fun `search matches a label or its detail, ignoring case and outer spaces`() {
        assertThat(pickerMatches("  SON ", "Claude 4.6 Sonnet")).isTrue()
        assertThat(pickerMatches("acme", "repo-00", "acme")).isTrue()
        assertThat(pickerMatches("zzz", "repo-00", null)).isFalse()
        assertThat(pickerMatches("", "anything")).isTrue()
    }
}
