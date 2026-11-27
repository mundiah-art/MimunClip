package com.mundiah.mimunclip.video

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Port setia dari slsr_6_vcnz.py (patch_bytes_slsr) ke Kotlin.
 *
 * Syarat input (gagal via exception bila tidak terpenuhi — pemanggil WAJIB
 * menangkap dan fallback, lihat [FfmpegRenderer]):
 *  - tepat 1 box `moov`, ukuran <= 32 MB, dengan size 32-bit biasa;
 *  - tepat 1 track audio AAC stereo, MP4 non-fragmented (tanpa moof/sidx/mfra/ssix);
 *  - box stbl hanya berisi jenis yang dikenal.
 *
 * Yang dilakukan: rewrite konfigurasi AAC di `esds`, tambah ~0.35 detik
 * frame AAC sunyi, perpanjang stts/stsz/stsc/stco/co64 + durasi,
 * gandakan (clone) track audio dengan track id baru, lalu susun ulang file
 * menjadi free + mdat(sunyi) + moov baru.
 *
 * JANGAN "memperbaiki" urutan aneh di bawah (mis. stsc di-set ke
 * rebuilt(1), clone dibuat, lalu stsc di-set ke rebuilt(2)) — itu disengaja
 * dari implementasi Python: track asli memakai entry stsd ke-2 (esds yang
 * sudah di-patch), clone memakai entry ke-1.
 */
object Slsr {

    val AAC_RATES = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000, 24000,
        22050, 16000, 12000, 11025, 8000, 7350
    )
    val SILENT_FRAME: ByteArray = byteArrayOf(33, 16, 4, 96, 140.toByte(), 28)

    class SlsrException(message: String) : IllegalStateException(message)

    private fun fail(message: String): Nothing = throw SlsrException(message)

    // ---------- primitif byte (big-endian) ----------

    private fun u32(data: ByteArray, off: Int): Long {
        return ((data[off].toInt() and 0xFF).toLong() shl 24) or
            ((data[off + 1].toInt() and 0xFF).toLong() shl 16) or
            ((data[off + 2].toInt() and 0xFF).toLong() shl 8) or
            (data[off + 3].toInt() and 0xFF).toLong()
    }

    private fun u64(data: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (data[off + i].toInt() and 0xFF).toLong()
        return v
    }

    private fun p32(value: Long): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    private fun p64(value: Long): ByteArray {
        val out = ByteArray(8)
        for (i in 0 until 8) out[i] = ((value ushr (56 - 8 * i)) and 0xFF).toByte()
        return out
    }

    private fun p32s(vararg values: Long): ByteArray {
        val out = ByteArray(values.size * 4)
        values.forEachIndexed { i, v -> p32(v).copyInto(out, i * 4) }
        return out
    }

    private fun fourcc(value: String): ByteArray =
        value.toByteArray(Charsets.ISO_8859_1).copyOf(4)

    private fun box(name: String, payload: ByteArray): ByteArray =
        p32((payload.size + 8).toLong()) + fourcc(name) + payload

    // ---------- model box ----------

    class Box(
        var name: ByteArray,
        var data: ByteArray,
        var raw: ByteArray? = null,
        var children: MutableList<Box>? = null,
    ) {
        fun nameString(): String = String(name, Charsets.ISO_8859_1)

        fun deepCopy(): Box = Box(
            name.copyOf(), data.copyOf(), raw?.copyOf(),
            children?.map { it.deepCopy() }?.toMutableList()
        )
    }

    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "dinf")

    private data class Header(val name: ByteArray, val headerSize: Int, val total: Long, val zeroSize: Boolean)

    private fun readHeader(data: ByteArray, off: Int, available: Int): Header {
        if (available < 8) fail("box header terpotong")
        val size = u32(data, off)
        val header = if (size == 1L) 16 else 8
        if (available < header) fail("box header terpotong")
        val total: Long = when {
            size == 1L -> u64(data, off + 8)
            size == 0L -> available.toLong()
            else -> size
        }
        if (total < header || total > available) fail("ukuran box tidak valid")
        return Header(data.copyOfRange(off + 4, off + 8), header, total, size == 0L)
    }

    private fun parseBoxes(data: ByteArray, offset: Int = 0, length: Int = data.size - offset, depth: Int = 0): MutableList<Box> {
        if (depth >= 16) fail("kedalaman box MP4 melebihi 16")
        val result = mutableListOf<Box>()
        var pos = offset
        val end = offset + length
        while (pos < end) {
            val (name, header, size, _) = readHeader(data, pos, end - pos)
            val sizeInt = size.toInt()
            val raw = data.copyOfRange(pos, pos + sizeInt)
            val payload = raw.copyOfRange(header, sizeInt)
            val children = if (String(name, Charsets.ISO_8859_1) in CONTAINERS) {
                parseBoxes(payload, 0, payload.size, depth + 1)
            } else null
            result += Box(name, payload, raw, children)
            pos += sizeInt
        }
        return result
    }

    private fun serializeBox(item: Box): ByteArray {
        val children = item.children
        if (children != null) {
            val payload = children.fold(ByteArray(0)) { acc, c -> acc + serializeBox(c) }
            return box(item.nameString(), payload)
        }
        item.raw?.let { return it }
        return box(item.nameString(), item.data)
    }

    private fun child(item: Box, name: String): Box =
        item.children?.firstOrNull { it.nameString() == name }
            ?: fail("missing MP4 box: $name")

    private fun findChild(item: Box, name: String): Box? =
        item.children?.firstOrNull { it.nameString() == name }

    private fun setData(item: Box, data: ByteArray) {
        item.data = data.copyOf()
        item.raw = null
    }

    private fun writeInt(item: Box, offset: Int, width: Int, value: Long) {
        if (value < 0 || (width != 8 && value >= 0xFFFFFFFFL)) fail("nilai int box di luar jangkauan")
        val data = item.data.copyOf()
        val bytes = if (width == 8) p64(value) else p32(value)
        bytes.copyInto(data, offset)
        setData(item, data)
    }

    private fun readInt(data: ByteArray, offset: Int, width: Int): Long =
        if (width == 8) u64(data, offset) else u32(data, offset)

    // ---------- descriptor ESDS ----------

    /** @return Triple(tag, payloadStart, payloadEnd) */
    private fun descriptor(data: ByteArray, start: Int, limit: Int = data.size): Triple<Int, Int, Int> {
        if (start >= limit) fail("descriptor di luar data")
        val tag = data[start].toInt() and 0xFF
        var pos = start + 1
        var length = 0
        repeat(4) {
            if (pos >= limit) fail("descriptor terpotong")
            val value = data[pos].toInt() and 0xFF
            pos++
            length = length * 128 + (value and 0x7F)
            if (value and 0x80 == 0) {
                if (pos + length > limit) fail("descriptor melebihi batas")
                return Triple(tag, pos, pos + length)
            }
        }
        fail("invalid descriptor")
    }

    private fun descriptorBytes(tag: Int, payload: ByteArray): ByteArray {
        val length = payload.size
        return byteArrayOf(
            tag.toByte(),
            (0x80 or (length shr 21)).toByte(),
            (0x80 or ((length shr 14) and 0x7F)).toByte(),
            (0x80 or ((length shr 7) and 0x7F)).toByte(),
            (length and 0x7F).toByte(),
        ) + payload
    }

    // ---------- patch deskripsi audio (stsd/esds) ----------

    /** @return Pair(sampleRate, patchedStsdData) */
    private fun patchAudioDescription(stsd: Box): Pair<Int, ByteArray> {
        val data = stsd.data
        if (data.size < 44 || u32(data, 0) != 0L || u32(data, 4) != 1L) {
            fail("stsd tidak valid")
        }
        val sample = data.copyOfRange(8, data.size)
        if (String(sample.copyOfRange(4, 8), Charsets.ISO_8859_1) != "mp4a" ||
            u32(sample, 0) != sample.size.toLong()
        ) fail("sample entry bukan mp4a")
        if (sample[16].toInt() != 0 || sample[17].toInt() != 0 ||
            sample[24].toInt() != 0 || sample[25].toInt() != 2
        ) fail("mp4a bukan stereo / versi tidak didukung")

        val entries = parseBoxes(sample, 36, sample.size - 36)
        val esds = entries.firstOrNull { it.nameString() == "esds" }
            ?: fail("esds tidak ditemukan")
        if (entries.any { it.nameString() in setOf("sinf", "wave") }) {
            fail("mp4a terenkripsi (sinf/wave)")
        }
        val esdsData = esds.data
        if (esdsData.size < 4 || u32(esdsData, 0) != 0L) fail("esds tidak valid")
        val decoder = descriptor(esdsData, 4)
        if (decoder.first != 3 || decoder.third - decoder.second < 3 ||
            esdsData[decoder.second + 2].toInt() != 0
        ) fail("ES descriptor tidak valid")

        var rateIndex = -1
        val rebuilt = mutableListOf<ByteArray>()
        var pos = decoder.second + 3
        while (pos < decoder.third) {
            val item = descriptor(esdsData, pos, decoder.third)
            if (item.first != 4) {
                rebuilt += esdsData.copyOfRange(pos, item.third)
            } else {
                if (item.third - item.second < 13 || esdsData[item.second].toInt() != 64) {
                    fail("DecoderConfig descriptor tidak valid")
                }
                val nested = mutableListOf<ByteArray>()
                var nestedPos = item.second + 13
                while (nestedPos < item.third) {
                    val sub = descriptor(esdsData, nestedPos, item.third)
                    if (sub.first != 5) {
                        nested += esdsData.copyOfRange(nestedPos, sub.third)
                    } else {
                        val config = esdsData.copyOfRange(sub.second, sub.third)
                        if (rateIndex >= 0 || config.size < 2) fail("AAC config tidak valid")
                        if ((config[0].toInt() and 0xFF) shr 3 != 2 ||
                            ((config[1].toInt() and 0xFF) and 127) != 16
                        ) fail("AAC config bukan LC stereo")
                        rateIndex = (((config[0].toInt() and 0xFF) and 7) shl 1) or
                            ((config[1].toInt() and 0xFF) shr 7)
                        val bits = config.copyOfRange(2, config.size)
                            .joinToString("") { "%8s".format(Integer.toBinaryString(it.toInt() and 0xFF)).replace(' ', '0') }
                        if (rateIndex >= AAC_RATES.size) fail("AAC sample rate index tidak dikenal")
                        if ('1' in bits && !Regex("01010110111001010*").matches(bits)) {
                            fail("AAC config memiliki ekstensi yang tidak didukung")
                        }
                        val table = Integer.toBinaryString(rateIndex).padStart(4, '0')
                        val configBits = "00010" + table + "0000000000001" + table +
                            "00000001000000000000000010000"
                        val paddedLen = ceil(configBits.length / 8.0).toInt() * 8
                        val padded = configBits.padEnd(paddedLen, '0') + "00000000"
                        val newConfig = padded.chunked(8)
                            .map { it.toInt(2).toByte() }.toByteArray()
                        nested += descriptorBytes(5, newConfig)
                    }
                    nestedPos = sub.third
                }
                val inner = esdsData.copyOfRange(item.second, item.second + 13) +
                    nested.fold(ByteArray(0)) { acc, b -> acc + b }
                rebuilt += descriptorBytes(4, inner)
            }
            pos = item.third
        }
        if (rateIndex < 0) fail("AAC config tidak ditemukan")

        val newEsdsData = esdsData.copyOfRange(0, 4) +
            descriptorBytes(
                3,
                esdsData.copyOfRange(decoder.second, decoder.second + 3) +
                    rebuilt.fold(ByteArray(0)) { acc, b -> acc + b }
            ) +
            esdsData.copyOfRange(decoder.third, esdsData.size)
        setData(esds, newEsdsData)
        val replacement = box(
            "mp4a",
            sample.copyOfRange(8, 36) +
                entries.fold(ByteArray(0)) { acc, e -> acc + serializeBox(e) }
        )
        return AAC_RATES[rateIndex] to (p32s(0, 2) + sample + replacement)
    }

    // ---------- util tabel ----------

    private fun tableCount(item: Box, stride: Int): Int {
        if (item.data.size < 8 || u32(item.data, 0) != 0L) fail("tabel ${item.nameString()} tidak valid")
        val count = u32(item.data, 4).toInt()
        if (item.data.size != 8 + count * stride || count > 5_000_000) {
            fail("jumlah entri tabel ${item.nameString()} tidak konsisten")
        }
        return count
    }

    private data class HeaderFields(val offset: Int, val width: Int, val duration: Long, val scale: Long)

    private fun headerFields(item: Box): HeaderFields {
        val version = item.data[0].toInt()
        if (version !in 0..1) fail("versi header ${item.nameString()} tidak valid")
        val version1 = version == 1
        val isTkhd = item.nameString() == "tkhd"
        val offset = (if (version1) 28 else 20).let { if (isTkhd) it else if (version1) 24 else 16 }
        val width = if (version1) 8 else 4
        return HeaderFields(
            offset, width,
            readInt(item.data, offset, width),
            if (isTkhd) 0 else u32(item.data, offset - 4)
        )
    }

    /**
     * Perpanjang edit list (elst) bila ada; kembalikan tambahan durasi
     * (dalam skala movie) yang harus ditambahkan ke tkhd.
     */
    private fun extendEdit(
        track: Box, audioDuration: Long, audioScale: Long, movieScale: Long,
        sampleRate: Int, duration: Long, tail: Long,
    ): Long {
        val edts = findChild(track, "edts")
            ?: run {
                val expected = (audioDuration.toDouble() * movieScale / audioScale).roundToLong()
                if (kotlin.math.abs(duration - expected) > 1) fail("durasi track/edit tidak konsisten")
                return tail
            }
        val elst = child(edts, "elst")
        val data = elst.data
        if (data[0].toInt() !in 0..1) fail("versi elst tidak valid")
        val width = if (data[0].toInt() == 1) 8 else 4
        val count = u32(data, 4).toInt()
        val stride = 2 * width + 4
        if (count !in 1..2 || data.size != 8 + count * stride) fail("elst tidak valid")
        val offset = 8 + (count - 1) * stride
        val segment = readInt(data, offset, width)
        val mediaTime = readInt(data, offset + width, width)
        if (mediaTime < 0 || u32(data, offset + 2 * width) != 65536L) fail("elst tidak valid")
        if (count == 2) {
            if (!(data.copyOfRange(8 + width, 8 + 2 * width).all { it == 0xFF.toByte() })) {
                fail("elst dwell tidak valid")
            }
            if (u32(data, 8 + 2 * width) != 65536L) fail("elst tidak valid")
        }
        val firstSegment = if (count == 2) readInt(data, 8, width) else 0L
        if (kotlin.math.abs(duration - segment - firstSegment) > 1) {
            fail("durasi track/edit tidak konsisten")
        }
        val difference = (audioDuration - mediaTime) * movieScale - segment * audioScale
        val tolerance = ceil(2048.0 * audioScale / sampleRate).toLong() * movieScale
        if (kotlin.math.abs(difference) > tolerance) fail("selisih edit melebihi toleransi")
        val extra = tail + max(0L, ceil(difference.toDouble() / audioScale).toLong())
        writeInt(elst, offset, width, segment + extra)
        return extra
    }

    // ---------- entry point ----------

    /**
     * Patch byte MP4 seperti slsr.py. Melempar [SlsrException] (atau
     * IllegalArgumentException) bila input tidak memenuhi syarat.
     */
    fun patchBytesSlsr(data: ByteArray): ByteArray {
        data class Top(val name: String, val at: Int, val size: Long, val width: Int, val zero: Boolean)
        val tops = mutableListOf<Top>()
        var pos = 0
        while (pos < data.size) {
            val (name, header, size, zero) = readHeader(data, pos, data.size - pos)
            val nameStr = String(name, Charsets.ISO_8859_1)
            if (tops.size >= 10000 || nameStr in setOf("moof", "sidx", "mfra", "ssix")) {
                fail("MP4 fragmented atau terlalu banyak box")
            }
            tops += Top(nameStr, pos, size, header, zero)
            pos += size.toInt()
        }

        val moovs = tops.filter { it.name == "moov" }
        val mdats = tops.filter { it.name == "mdat" }
        if (moovs.size != 1 || mdats.isEmpty() || moovs[0].width != 8 ||
            moovs[0].size > 32L * 1024 * 1024
        ) fail("butuh tepat 1 moov (<=32MB) dan minimal 1 mdat")
        val moov = moovs[0]
        val root = parseBoxes(data, moov.at, moov.size.toInt())[0]
        val tracks = root.children?.filter { it.nameString() == "trak" } ?: emptyList()
        val audioTracks = tracks.filter { track ->
            val hdlr = child(child(track, "mdia"), "hdlr")
            hdlr.data.size >= 12 && String(hdlr.data.copyOfRange(8, 12), Charsets.ISO_8859_1) == "soun"
        }
        if (audioTracks.size != 1) fail("butuh tepat 1 track audio")
        val audio = audioTracks[0]
        val media = child(audio, "mdia")
        val minf = child(media, "minf")
        val dref = child(child(minf, "dinf"), "dref").data
        val refs = parseBoxes(dref, 8, dref.size - 8)
        if (u32(dref, 0) != 0L || u32(dref, 4) != refs.size.toLong() || refs.isEmpty()) {
            fail("dref tidak valid")
        }
        if (!refs.all { it.nameString() == "url " && u32(it.data, 0) == 1L }) {
            fail("referensi data audio bukan url lokal")
        }

        val stbl = child(minf, "stbl")
        val allowed = setOf(
            "stsd", "stsz", "stsc", "stts", "stco", "co64", "sbgp", "sgpd",
            "subs", "free", "skip", "ctts", "stss", "stps", "sdtp", "stdp"
        )
        if (stbl.children?.any { it.nameString() !in allowed } == true) {
            fail("stbl berisi box yang tidak didukung")
        }
        val stsd = child(stbl, "stsd")
        val (sampleRate, patchedStsd) = patchAudioDescription(stsd)

        val stsz = child(stbl, "stsz")
        if (stsz.data.size < 12 || u32(stsz.data, 0) != 0L) fail("stsz tidak valid")
        val uniform = u32(stsz.data, 4)
        val sampleCount = u32(stsz.data, 8).toInt()
        if (sampleCount <= 0 || sampleCount > 5_000_000) fail("jumlah sampel audio tidak valid")
        if (stsz.data.size != 12 + (if (uniform != 0L) 0 else 4 * sampleCount)) {
            fail("stsz tidak konsisten")
        }
        fun sampleSize(index: Int): Long =
            if (uniform != 0L) uniform else u32(stsz.data, 12 + 4 * index)

        val stsc = child(stbl, "stsc")
        val runs = tableCount(stsc, 12)
        val offsets: Box
        val offsetWidth: Int
        if (findChild(stbl, "stco") != null) {
            if (findChild(stbl, "co64") != null) fail("stco dan co64 bersamaan")
            offsets = findChild(stbl, "stco")!!
            offsetWidth = 4
        } else {
            offsets = child(stbl, "co64")
            offsetWidth = 8
        }
        val chunks = tableCount(offsets, offsetWidth)
        if (runs <= 0 || chunks <= 0) fail("stsc/stco kosong")
        fun firstChunk(index: Int): Long = u32(stsc.data, 8 + 12 * index)
        if (firstChunk(0) != 1L) fail("stsc harus mulai dari chunk 1")
        for (index in 0 until runs) {
            if (firstChunk(index) > chunks) fail("stsc menunjuk chunk yang tidak ada")
            if (index > 0 && firstChunk(index) <= firstChunk(index - 1)) {
                fail("stsc tidak berurutan")
            }
            if (u32(stsc.data, 12 + 12 * index) <= 0) fail("stsc samples-per-chunk nol")
            if (u32(stsc.data, 16 + 12 * index) != 1L) fail("stsc sample-description-index bukan 1")
        }

        var run = 0
        var sampled = 0
        for (index in 0 until chunks) {
            if (run + 1 < runs && firstChunk(run + 1) == (index + 1).toLong()) run++
            val perChunk = u32(stsc.data, 12 + 12 * run).toInt()
            if (sampled + perChunk > sampleCount) fail("stsc melebihi jumlah sampel")
            var chunkBytes = 0L
            repeat(perChunk) {
                val size = sampleSize(sampled)
                sampled++
                if (size <= 0) fail("ukuran sampel audio nol")
                chunkBytes += size
            }
            val offset = readInt(offsets.data, 8 + index * offsetWidth, offsetWidth)
            val insideMdat = mdats.any { m ->
                offset >= m.at + m.width && offset + chunkBytes <= m.at + m.size
            }
            if (!insideMdat) fail("chunk audio di luar mdat")
        }
        if (sampled != sampleCount) fail("jumlah sampel tidak cocok dengan stsc")

        val stts = child(stbl, "stts")
        val timingCount = tableCount(stts, 8)
        var audioDuration = 0L
        var timedSamples = 0
        val timing = mutableListOf<LongArray>()
        for (index in 0 until timingCount) {
            val count = u32(stts.data, 8 + 8 * index)
            val delta = u32(stts.data, 12 + 8 * index)
            if (count <= 0 || delta <= 0) fail("stts tidak valid")
            timing += longArrayOf(count, delta)
            timedSamples += count.toInt()
            audioDuration += count * delta
        }
        if (timedSamples != sampleCount) fail("stts tidak cocok dengan jumlah sampel")

        val mdhd = child(media, "mdhd")
        val mvhd = child(root, "mvhd")
        val tkhd = child(audio, "tkhd")
        val audioHeader = headerFields(mdhd)
        val movieHeader = headerFields(mvhd)
        val trackHeader = headerFields(tkhd)
        if (audioHeader.scale == 0L || movieHeader.scale == 0L || audioHeader.duration <= 0) {
            fail("durasi/skala header tidak valid")
        }
        val durationTolerance = ceil(2048.0 * audioHeader.scale / sampleRate).toLong()
        if (kotlin.math.abs(audioHeader.duration - audioDuration) > durationTolerance) {
            fail("durasi mdhd tidak cocok dengan stts")
        }
        if (tkhd.data[3].toInt() and 1 == 0 || trackHeader.duration <= 0) {
            fail("track audio tidak aktif / durasi nol")
        }

        val tailFrames = max(10, ceil(0.35 * sampleRate / 1024.0).toInt())
        for (item in stbl.children ?: emptyList()) {
            val payload = item.data
            when (item.nameString()) {
                "sbgp" -> {
                    if (payload[0].toInt() !in 0..1) fail("sbgp tidak valid")
                    val header = if (payload[0].toInt() == 1) 12 else 8
                    if (payload.size < header + 4) fail("sbgp terpotong")
                    val groups = u32(payload, header).toInt()
                    if (payload.size != header + 4 + groups * 8) fail("sbgp tidak konsisten")
                    var covered = 0L
                    for (g in 0 until groups) covered += u32(payload, header + 4 + 8 * g)
                    if (covered > sampleCount) fail("sbgp melebihi jumlah sampel")
                    val extra = sampleCount - covered + tailFrames
                    if (groups > 0 && u32(payload, payload.size - 4) == 0L) {
                        val at = payload.size - 8
                        writeInt(item, at, 4, u32(payload, at) + extra)
                    } else {
                        setData(
                            item,
                            payload.copyOfRange(0, header) + p32((groups + 1).toLong()) +
                                payload.copyOfRange(header + 4, payload.size) +
                                p32s(extra.toLong(), 0)
                        )
                    }
                }
                "ctts" -> {
                    val count = tableCount(item, 8)
                    var covered = 0L
                    for (index in 0 until count) {
                        covered += u32(payload, 8 + 8 * index)
                        if (u32(payload, 12 + 8 * index) != 0L) fail("ctts offset bukan nol")
                    }
                    if (covered != sampleCount.toLong()) fail("ctts tidak cocok dengan sampel")
                    setData(
                        item,
                        p32s(0, (count + 1).toLong()) + payload.copyOfRange(8, payload.size) +
                            p32s(tailFrames.toLong(), 0)
                    )
                }
                "stss", "stps" -> {
                    val count = tableCount(item, 4)
                    var previous = 0L
                    for (index in 0 until count) {
                        val value = u32(payload, 8 + 4 * index)
                        if (value <= previous || value > sampleCount) fail("${item.nameString()} tidak valid")
                        previous = value
                    }
                    val tailEntries = (0 until tailFrames).map { (sampleCount + it + 1).toLong() }.toLongArray()
                    setData(
                        item,
                        p32s(0, (count + tailFrames).toLong()) +
                            payload.copyOfRange(8, payload.size) +
                            p32s(*tailEntries)
                    )
                }
                "sdtp", "stdp" -> {
                    val stride = if (item.nameString() == "sdtp") 1 else 2
                    if (payload.size != 4 + stride * sampleCount) fail("${item.nameString()} tidak konsisten")
                    setData(item, payload + ByteArray(stride * tailFrames))
                }
            }
        }

        val tailExtra = tailFrames - 8
        val audioTail = floor(tailFrames * 1024.0 * audioHeader.scale / sampleRate).toLong()
        val movieTail = (audioTail.toDouble() * movieHeader.scale / audioHeader.scale).roundToLong()
        if (movieTail <= 0) fail("tail durasi nol")
        val editExtra = extendEdit(
            audio, audioDuration, audioHeader.scale, movieHeader.scale,
            sampleRate, trackHeader.duration, movieTail
        )
        writeInt(tkhd, trackHeader.offset, trackHeader.width, trackHeader.duration + editExtra)
        writeInt(mdhd, audioHeader.offset, audioHeader.width, audioHeader.duration + audioTail)

        for (index in 0 until tailFrames) {
            val delta = floor((index + 1) * 1024.0 * audioHeader.scale / sampleRate).toLong() -
                floor(index * 1024.0 * audioHeader.scale / sampleRate).toLong()
            if (delta <= 0 || delta > 0xFFFFFFFFL) fail("delta stts tail tidak valid")
            if (timing.last()[1] == delta) timing.last()[0]++
            else timing += longArrayOf(1, delta)
        }
        val newStts = ByteArray(8 + timing.size * 8)
        p32(timing.size.toLong()).copyInto(newStts, 4)
        timing.forEachIndexed { i, (count, delta) ->
            p32(count).copyInto(newStts, 8 + i * 8)
            p32(delta).copyInto(newStts, 12 + i * 8)
        }
        setData(stts, newStts)

        val newStsz = ByteArray(12 + 4 * (sampleCount + tailFrames))
        p32((sampleCount + tailFrames).toLong()).copyInto(newStsz, 8)
        for (index in 0 until sampleCount + tailFrames) {
            val size = if (index < sampleCount) sampleSize(index) else SILENT_FRAME.size.toLong()
            p32(size).copyInto(newStsz, 12 + 4 * index)
        }
        setData(stsz, newStsz)

        val oldOffsetWidth = offsetWidth
        val firstOffset = data.size.toLong() + 8
        val secondOffset = firstOffset + tailExtra.toLong() * SILENT_FRAME.size
        val newOffsetWidth = if (oldOffsetWidth == 8 || secondOffset > 0xFFFFFFFFL) 8 else 4
        val newOffsets = ByteArray(8 + (chunks + 2) * newOffsetWidth)
        p32((chunks + 2).toLong()).copyInto(newOffsets, 4)
        for (index in 0 until chunks + 2) {
            val value = when {
                index < chunks -> readInt(offsets.data, 8 + index * oldOffsetWidth, oldOffsetWidth)
                index == chunks -> firstOffset
                else -> secondOffset
            }
            if (newOffsetWidth == 8) p64(value).copyInto(newOffsets, 8 + index * 8)
            else p32(value).copyInto(newOffsets, 8 + index * 4)
        }
        offsets.name = fourcc(if (newOffsetWidth == 8) "co64" else "stco")
        setData(offsets, newOffsets)

        val stscPayload = stsc.data.copyOfRange(8, stsc.data.size)
        fun rebuiltStsc(description: Long): ByteArray =
            p32s(0, (runs + 2).toLong()) + stscPayload +
                p32s(
                    (chunks + 1).toLong(), tailExtra.toLong(), 1,
                    (chunks + 2).toLong(), 8, description
                )

        setData(stsc, rebuiltStsc(1))
        val clone = audio.deepCopy()
        setData(stsc, rebuiltStsc(2))
        setData(stsd, patchedStsd)

        fun trackId(item: Box): Long {
            val tkhdBox = child(item, "tkhd")
            return u32(tkhdBox.data, if (tkhdBox.data[0].toInt() == 1) 20 else 12)
        }
        val ids = tracks.map { trackId(it) }
        if (ids.any { it == 0L } || ids.toSet().size != ids.size) fail("track id duplikat/nol")
        val nextId = ids.max() + 1
        val cloneTkhd = child(clone, "tkhd")
        writeInt(cloneTkhd, if (cloneTkhd.data[0].toInt() == 1) 20 else 12, 4, nextId)
        val nextOffset = if (mvhd.data[0].toInt() == 1) 108 else 96
        val previousNext = u32(mvhd.data, nextOffset)
        writeInt(
            mvhd, nextOffset, 4,
            max(nextId + 1, if (previousNext == 0xFFFFFFFFL) 0 else previousNext)
        )
        if (movieHeader.duration > 0 &&
            !(movieHeader.width == 4 && movieHeader.duration == 0xFFFFFFFFL)
        ) {
            var longest = movieHeader.duration
            for (track in tracks) {
                val fields = headerFields(child(track, "tkhd"))
                if (fields.duration > 0 &&
                    !(fields.width == 4 && fields.duration == 0xFFFFFFFFL)
                ) longest = max(longest, fields.duration)
            }
            writeInt(mvhd, movieHeader.offset, movieHeader.width, longest)
        }

        val audioIndex = root.children!!.indexOf(audio)
        root.children!!.add(audioIndex + 1, clone)
        val silent = ByteArray(SILENT_FRAME.size * tailFrames).apply {
            for (i in 0 until tailFrames) SILENT_FRAME.copyInto(this, i * SILENT_FRAME.size)
        }
        val output = mutableListOf<ByteArray>()
        for (item in tops) {
            when {
                item === moov -> output += p32(item.size) + fourcc("free") +
                    data.copyOfRange(item.at + 8, item.at + item.size.toInt())
                item.zero -> output += p32(item.size) +
                    data.copyOfRange(item.at + 4, item.at + item.size.toInt())
                else -> output += data.copyOfRange(item.at, item.at + item.size.toInt())
            }
        }
        output += box("mdat", silent)
        output += serializeBox(root)
        return output.fold(ByteArray(0)) { acc, b -> acc + b }
    }
}
