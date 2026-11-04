package com.v1per.sxdc.licenses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentsTsvTest {
    private fun tsv(vararg lines: String) = lines.joinToString("\n")

    @Test
    fun parsesAllColumns() {
        val rows = ComponentsTsv.parse(
            tsv(
                "name\tversion\tlicense\turl\tfile\tnotes\tmaven",
                "FFmpeg\t8.1.1\tLGPL-2.1-or-later\thttps://ffmpeg.org\tFFmpeg-LGPL-2.1.txt\tExecutables\t",
            ),
        )
        assertEquals(
            listOf(
                LicenseComponent(
                    name = "FFmpeg",
                    version = "8.1.1",
                    license = "LGPL-2.1-or-later",
                    url = "https://ffmpeg.org",
                    files = listOf("FFmpeg-LGPL-2.1.txt"),
                    notes = "Executables",
                ),
            ),
            rows,
        )
    }

    @Test
    fun missingOptionalColumn_readsAsEmpty() {
        val rows = ComponentsTsv.parse(tsv("name\tversion\tlicense\tfile", "A\t1\tMIT\ta.txt"))
        assertEquals("", rows.single().url)
        assertEquals("", rows.single().notes)
        assertEquals(listOf("a.txt"), rows.single().files)
    }

    @Test
    fun shortRow_trailingCellsReadAsEmpty() {
        val rows = ComponentsTsv.parse(tsv("name\tversion\tlicense\turl\tfile\tnotes", "Icon\tn/a\tNOASSERTION"))
        assertEquals(emptyList<String>(), rows.single().files)
        assertEquals("", rows.single().notes)
    }

    @Test
    fun blankLinesAndCrlfAreIgnored() {
        val rows = ComponentsTsv.parse("name\tversion\tlicense\r\n\r\nA\t1\tMIT\r\n   \nB\t2\tMIT\r\n")
        assertEquals(listOf("A", "B"), rows.map { it.name })
        assertEquals("MIT", rows.last().license)
    }

    @Test
    fun columnsAreFoundByHeaderName() {
        val rows = ComponentsTsv.parse(tsv("license\tname\tversion", "MIT\tA\t1"))
        assertEquals(LicenseComponent("A", "1", "MIT", "", emptyList(), ""), rows.single())
    }

    @Test
    fun commaSeparatedFiles_areSplitAndTrimmed() {
        val rows = ComponentsTsv.parse(tsv("name\tversion\tlicense\tfile", "certifi\t1\tMPL-2.0\ta.txt, b.txt ,"))
        assertEquals(listOf("a.txt", "b.txt"), rows.single().files)
    }

    @Test
    fun missingRequiredColumn_throws() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            ComponentsTsv.parse(tsv("name\tversion", "A\t1"))
        }
        assertTrue(e.message!!.contains("license"))
    }

    @Test
    fun emptyText_throws() {
        assertThrows(IllegalArgumentException::class.java) { ComponentsTsv.parse("\n \n") }
    }

    @Test
    fun rowWithoutName_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            ComponentsTsv.parse(tsv("name\tversion\tlicense", "\t1\tMIT"))
        }
    }

    @Test
    fun headerOnly_isEmptyList() {
        assertEquals(emptyList<LicenseComponent>(), ComponentsTsv.parse("name\tversion\tlicense\n"))
    }
}
