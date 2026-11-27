package com.mundiah.mimunclip

import com.mundiah.mimunclip.data.ContentFilter
import com.mundiah.mimunclip.data.LocalClipPlanner
import com.mundiah.mimunclip.model.Segment
import com.mundiah.mimunclip.video.MediaStoreSaver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test filter kata terlarang v1 (HANYA stok + promo) dan planner lokal.
 */
class ContentFilterTest {

    @Test
    fun `stock words are forbidden`() {
        assertEquals("stok", ContentFilter.firstForbiddenLabel("stok tinggal 3 ya kak"))
        assertEquals("stok", ContentFilter.firstForbiddenLabel("Sisa stok menipis"))
        assertEquals("stok", ContentFilter.firstForbiddenLabel("limited stock only"))
        assertTrue(ContentFilter.hasForbiddenWords("bahannya adem, stok terbatas"))
    }

    @Test
    fun `promo words are forbidden`() {
        assertEquals("promo", ContentFilter.firstForbiddenLabel("lagi diskon 50%"))
        assertEquals("promo", ContentFilter.firstForbiddenLabel("ada voucher cashback"))
        assertEquals("promo", ContentFilter.firstForbiddenLabel("flash sale hari ini"))
        assertEquals("promo", ContentFilter.firstForbiddenLabel("kupon potongan harga"))
        assertEquals("promo", ContentFilter.firstForbiddenLabel("deal terbaik"))
    }

    @Test
    fun `clean fashion talk is allowed`() {
        assertNull(ContentFilter.firstForbiddenLabel("bahannya rayon premium, adem dan jatuh"))
        assertFalse(ContentFilter.hasForbiddenWords("cocok buat OOTD ngantor maupun hangout"))
        assertFalse(ContentFilter.hasForbiddenWords("modelnya flowy, warna sage"))
    }

    @Test
    fun `pre-order and shipping are NOT filtered in v1`() {
        // Lock v1: PO/pre-order dan pengiriman SENGAJA tidak difilter.
        assertNull(ContentFilter.firstForbiddenLabel("ini PO dulu ya kak, pre-order 3 hari"))
        assertNull(ContentFilter.firstForbiddenLabel("pengiriman dari Jakarta, dikirim besok"))
        assertNull(ContentFilter.firstForbiddenLabel("harganya 150 ribu"))
    }

    @Test
    fun `word boundaries matter`() {
        // "stok" di dalam kata lain tidak boleh kena filter.
        assertNull(ContentFilter.firstForbiddenLabel("restock besok pagi"))
        assertNull(ContentFilter.firstForbiddenLabel("harga wholesale untuk reseller"))
        // ...tapi kata itu sebagai kata sendiri tetap kena:
        assertNotNull(ContentFilter.firstForbiddenLabel("lagi promo besar"))
        assertNotNull(ContentFilter.firstForbiddenLabel("stok tinggal dua"))
    }

    @Test
    fun `caption with forbidden words falls back to safe caption`() {
        val bad = "Blouse Rayon! Diskon 50% stok tinggal 2, cek!"
        assertTrue(ContentFilter.hasForbiddenCaptionWords(bad))
        val safe = ContentFilter.safeFallbackCaption("Blouse Rayon Twill")
        assertFalse(ContentFilter.hasForbiddenCaptionWords(safe))
        assertTrue(safe.contains("#racuntiktok"))
        assertTrue(safe.contains("#ootdwanita"))
    }

    @Test
    fun `fallback caption has at most 5 hashtags`() {
        val safe = ContentFilter.safeFallbackCaption("One Set Rayon")
        val tags = Regex("#\\S+").findAll(safe).count()
        assertTrue(tags <= 5)
    }
}

class ClipPlannerTest {

    private fun segments(n: Int, secondsEach: Double = 5.0): List<Segment> =
        (1..n).map { i ->
            Segment(i, (i - 1) * secondsEach, i * secondsEach, "kalimat nomor $i bahannya adem")
        }

    @Test
    fun `local planner builds a 35-50s clip from clean segments`() {
        val segs = segments(12) // 60 detik total
        val built = LocalClipPlanner.buildFromStart(segs, 1, 50.0)
        assertNotNull(built)
        assertTrue(built!!.durationSec >= 28.0)
        assertTrue(built.durationSec <= 50.0)
        assertEquals(1, built.startSegmentId)
    }

    @Test
    fun `forbidden segment can never start a clip`() {
        val segs = segments(12).map {
            if (it.id == 1) it.copy(text = "stok tinggal 2, buruan diskon") else it
        }
        assertNull(LocalClipPlanner.buildFromStart(segs, 1, 50.0))
        assertNotNull(LocalClipPlanner.buildFromStart(segs, 2, 50.0))
    }

    @Test
    fun `too little clean material returns null`() {
        val segs = segments(3) // hanya 15 detik
        assertNull(LocalClipPlanner.buildFromStart(segs, 1, 50.0))
    }

    @Test
    fun `planner skips forbidden middle segment and still builds`() {
        val segs = segments(12).map {
            if (it.id == 4) it.copy(text = "lagi promo cashback") else it
        }
        val built = LocalClipPlanner.buildFromStart(segs, 1, 50.0)
        assertNotNull(built)
        // Segmen 4 terlarang: klip terbelah jadi 2 rentang.
        assertTrue(built!!.ranges.size >= 1)
        assertTrue(built.durationSec >= 28.0)
    }

    @Test
    fun `plan returns up to 4 candidates`() {
        val segs = segments(40) // 200 detik
        val candidates = LocalClipPlanner.plan(segs)
        assertTrue(candidates.size in 1..4)
        assertTrue(candidates.all { it.durationSec >= 28.0 })
    }

    @Test
    fun `slug for file name is alphanumeric pascal case`() {
        assertEquals("BlouseRayonTwillAdem", MediaStoreSaver.slugFromTitle("Blouse Rayon Twill Adem!"))
        assertEquals("Clip", MediaStoreSaver.slugFromTitle("!!!"))
        val name = MediaStoreSaver.fileNameFor("Blouse Rayon")
        assertTrue(name.startsWith("VID_"))
        assertTrue(name.endsWith("_BlouseRayon.mp4"))
    }
}
