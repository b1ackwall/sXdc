package com.v1per.sxdc.download

import com.v1per.sxdc.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WarningMessageMapperTest {
    private val strings = FakeStringProvider()

    private fun note(vararg warnings: String) = WarningMessageMapper.noteFor(warnings.toList(), strings)

    private fun render(id: Int) = FakeStringProvider.render(id)

    @Test
    fun noWarnings_isNoNote() {
        assertNull(note())
    }

    @Test
    fun eachWarning_onItsOwn() {
        assertEquals(render(R.string.warn_no_cover), note(WarningKinds.NO_COVER))
        assertEquals(render(R.string.warn_no_tags), note(WarningKinds.NO_TAGS))
        assertEquals(render(R.string.warn_short_audio), note(WarningKinds.SHORT_AUDIO))
    }

    @Test
    fun noTagsAndNoCover_useTheCombinedLine_inEitherOrder() {
        val combined = render(R.string.warn_no_tags_or_cover)
        assertEquals(combined, note(WarningKinds.NO_TAGS, WarningKinds.NO_COVER))
        assertEquals(combined, note(WarningKinds.NO_COVER, WarningKinds.NO_TAGS))
    }

    @Test
    fun severalWarnings_joinWithASpace_coverOrTagsFirst() {
        assertEquals(
            render(R.string.warn_no_tags_or_cover) + " " + render(R.string.warn_short_audio),
            note(WarningKinds.SHORT_AUDIO, WarningKinds.NO_COVER, WarningKinds.NO_TAGS),
        )
        assertEquals(
            render(R.string.warn_no_cover) + " " + render(R.string.warn_short_audio),
            note(WarningKinds.NO_COVER, WarningKinds.SHORT_AUDIO),
        )
    }

    @Test
    fun unknownIds_areDropped_andDuplicatesCountOnce() {
        assertNull(note("something_new", "", "NO_COVER"))
        assertEquals(
            render(R.string.warn_no_cover),
            note("something_new", WarningKinds.NO_COVER, WarningKinds.NO_COVER),
        )
    }

    /** The ids Python sends, as sxdc_embed.py spells them. */
    @Test
    fun ids_matchPython() {
        assertEquals("no_cover", WarningKinds.NO_COVER)
        assertEquals("no_tags", WarningKinds.NO_TAGS)
        assertEquals("short_audio", WarningKinds.SHORT_AUDIO)
    }
}
