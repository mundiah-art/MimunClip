package com.mundiah.mimunclip

import com.mundiah.mimunclip.video.Slsr
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Test minimal Slsr: input bukan MP4 / rusak harus gagal secara terkendali
 * (exception), bukan crash/hang — pemanggil (FfmpegRenderer) mengandalkan
 * perilaku ini untuk fallback ke hasil render langsung.
 *
 * Round-trip pada MP4 asli tidak diuji di unit test JVM karena butuh file
 * MP4 AAC stereo yang valid; validasi round-trip dilakukan manual dengan
 * skrip Python referensi (slsr_6_vcnz.py) pada file hasil ffmpeg — lihat
 * README.md bagian "Validasi SLSR".
 */
class SlsrTest {

    @Test
    fun `empty input fails gracefully`() {
        assertThrows(IllegalStateException::class.java) {
            Slsr.patchBytesSlsr(ByteArray(0))
        }
    }

    @Test
    fun `plain text is not an mp4`() {
        assertThrows(IllegalStateException::class.java) {
            Slsr.patchBytesSlsr("ini bukan file mp4 sama sekali".toByteArray())
        }
    }

    @Test
    fun `random bytes fail gracefully`() {
        val garbage = ByteArray(1024) { (it * 37 % 251).toByte() }
        assertThrows(IllegalStateException::class.java) {
            Slsr.patchBytesSlsr(garbage)
        }
    }

    @Test
    fun `truncated fake ftyp box fails gracefully`() {
        // Box 'ftyp' dengan ukuran yang mengklaim lebih dari data yang ada.
        val fake = byteArrayOf(
            0, 0, 0, 100,
            'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
        )
        assertThrows(IllegalStateException::class.java) {
            Slsr.patchBytesSlsr(fake)
        }
    }

    @Test
    fun `silent frame constant matches python reference`() {
        val expected = byteArrayOf(33, 16, 4, 96, 140.toByte(), 28)
        assertTrue(Slsr.SILENT_FRAME.contentEquals(expected))
        assertTrue(Slsr.AAC_RATES.contains(44100))
    }

    @Test
    fun `mp4 without moov fails gracefully`() {
        // Satu box mdat valid tapi tanpa moov.
        val mdat = byteArrayOf(
            0, 0, 0, 16,
            'm'.code.toByte(), 'd'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(),
            1, 2, 3, 4, 5, 6, 7, 8
        )
        try {
            Slsr.patchBytesSlsr(mdat)
            fail("seharusnya gagal: tidak ada moov")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("moov"))
        }
    }
}
