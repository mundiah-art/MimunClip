package com.mundiah.mimunclip.data

/**
 * Filter kata terlarang v1 — LOCKED.
 *
 * HANYA dua kelompok yang difilter:
 *  1. Stok-availability: tinggal, sisa, stok, stok tinggal, stok terbatas,
 *     stok menipis, sisa stok, limited stock.
 *  2. Promo: diskon, promo, promosi, cashback, voucher, kupon,
 *     potongan harga, flash sale, sale, deal.
 *
 * SENGAJA TIDAK difilter di v1 (berbeda dari workflow Termux lama):
 * PO / pre-order dan shipping / pengiriman / lokasi pengiriman, harga /
 * nominal, link / bio, dan kata "rekomendasi". Bila kebijakan berubah,
 * tambahkan pola di sini — JANGAN di tempat lain — supaya satu sumber
 * kebenaran untuk pemilihan segmen DAN validasi caption.
 */
object ContentFilter {

    private data class Rule(val regex: Regex, val label: String)

    private fun word(vararg words: String): Regex {
        val body = words.joinToString("|") { Regex.escape(it) }
        return Regex("\\b(?:$body)\\b", RegexOption.IGNORE_CASE)
    }

    private val rules = listOf(
        Rule(
            word(
                "stok tinggal", "stok terbatas", "stok menipis", "sisa stok",
                "limited stock", "tinggal", "sisa", "stok"
            ),
            "stok"
        ),
        Rule(
            word(
                "potongan harga", "flash sale", "diskon", "promo", "promosi",
                "cashback", "voucher", "kupon", "sale", "deal"
            ),
            "promo"
        ),
    )

    /** Label kelompok yang cocok ("stok" / "promo"), atau null bila bersih. */
    fun firstForbiddenLabel(text: String): String? {
        if (text.isBlank()) return null
        for (rule in rules) {
            if (rule.regex.containsMatchIn(text)) return rule.label
        }
        return null
    }

    fun hasForbiddenWords(text: String): Boolean = firstForbiddenLabel(text) != null

    /** Validasi caption memakai aturan yang SAMA dengan pemilihan segmen. */
    fun hasForbiddenCaptionWords(text: String): Boolean = hasForbiddenWords(text)

    /**
     * Caption pengganti yang aman bila caption dari Qwen mengandung kata
     * terlarang. Tidak menyebut stok, promo, harga, atau PO.
     */
    fun safeFallbackCaption(title: String): String {
        val cleanTitle = title.trim().ifEmpty { "Outfit Pilihan" }
        val tag = "#" + cleanTitle.lowercase()
            .replace(Regex("[^a-z0-9]"), "")
            .take(24)
            .ifEmpty { "fashionwanita" }
        return "$cleanTitle! Bahan adem, jatuh, dan nyaman dipakai seharian " +
            "dengan tampilan Casual Chic dan Flowy, Nyaman Dipakai harian " +
            "maupun OOTD \u2728 Langsung cek keranjang kuning ya!\n" +
            "$tag #racuntiktok #ootdwanita #outfitwanita #fyp"
    }
}
