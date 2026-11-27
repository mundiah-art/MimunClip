# MimunClip v1.0.0

Aplikasi Android untuk memotong video rekaman live TikTok (TikTok Shop
Affiliate, fashion wanita) menjadi klip-klip 35–50 detik dengan hook di
awal, memakai transkripsi Groq Whisper dan perencanaan hook/caption oleh
Groq Qwen. Dibangun dari nol dengan Kotlin + Jetpack Compose. **Bukan
tiruan FakeCLiping** — UI-nya minimalis dan fungsional.

Alur: **Pilih Video → Pengaturan → Transkripsi → Pilih Hook → Render → Hasil.**

## Fitur v1

- **SLSR inject (toggle on/off)** — port setia `patch_bytes_slsr`
  (Python) ke Kotlin (`video/Slsr.kt`): rewrite konfigurasi AAC di `esds`,
  tambah ~0,35 detik frame AAC sunyi, perpanjang `stts`/`stsz`/`stsc`/
  `stco`/`co64` + durasi, gandakan track audio dengan track id baru, lalu
  susun ulang file menjadi `free` + `mdat` (sunyi) + `moov` baru.
  Bila gagal: perbaiki audio dulu ke AAC stereo 44100 lalu coba lagi;
  bila masih gagal, dipakai hasil render langsung tanpa SLSR dan user
  diberi tahu di UI (bukan error fatal).
- **CAS 90% (toggle) dan Unsharp 90% (toggle)** — terpisah. FFmpeg:
  `cas=0.9` dan `unsharp=5:5:0.9:5:5:0.0`. Hanya aktif bila toggle on.
- **Filter kata (HANYA stok + promo)** — `data/ContentFilter.kt` adalah
  satu-satunya sumber aturan. Yang difilter: stok-availability
  (`tinggal`, `sisa`, `stok`, `stok tinggal`, `stok terbatas`,
  `stok menipis`, `sisa stok`, `limited stock`) dan promo (`diskon`,
  `promo`, `promosi`, `cashback`, `voucher`, `kupon`, `potongan harga`,
  `flash sale`, `sale`, `deal`). **Sengaja TIDAK difilter di v1:**
  PO/pre-order dan shipping/pengiriman. Aturan dipakai di pemilihan
  segmen DAN validasi caption (caption yang kena filter diganti caption
  fallback yang aman).
- **Hook picker** — setelah transkrip, sebelum render. Qwen mengusulkan
  4 kandidat hook; user memilih tepat N hook untuk N klip (N = 1–4).
  Tiap kandidat menampilkan `hook_quote`, durasi, dan rentang segmen,
  plus preview 5 detik pertama hook di mini player.
- **Qwen merapikan transkrip** Whisper yang berantakan sebelum dipakai
  untuk perencanaan subtitle/caption.
- **Output ke Downloads/MimunClip** via MediaStore, nama
  `VID_yyyyMMdd_HHmmss_SlugHook.mp4`.
- **Tombol salin caption + hashtag** ke clipboard per klip.
- **Mini video player** di dalam app (Media3 ExoPlayer).
- **Tidak ada auto-delete.** Video sumber dan hasil tidak pernah dihapus
  otomatis. Riwayat hasil disimpan lokal (JSON); menghapus entri riwayat
  hanya menghapus catatan, bukan file video.
- **Foreground Service + notifikasi progres** saat transkripsi/render.

## Render

- Crop ke rasio vertikal 9:16, scale 720×1280, fps 30.
- Video: libx264, preset veryfast, crf 23. Audio: AAC stereo 44100.
- Potong dengan `-ss` sebelum `-i`, lalu re-encode (bukan stream-copy
  buta — walau tidak ada filter aktif pun tetap re-encode ringan agar
  hasil konsisten).
- Klip multi-rentang: tiap rentang di-render terpisah lalu digabung
  (concat demuxer + re-encode ringan).

## Groq

- Transkripsi: `whisper-large-v3-turbo`, bahasa `id`,
  `verbose_json` + segment timestamps.
- Chat: model default **`qwen/qwen3-32b`**, fallback
  **`llama-3.3-70b-versatile`** (otomatis dipakai bila model utama
  ditolak server). Keduanya bisa diubah di Pengaturan.
- Catatan: workflow lama menulis model `qwen/qwen3.8-27b` — itu
  tampaknya typo (tidak ada model Groq bernama itu), jadi v1 memakai
  `qwen/qwen3-32b`.
- Multi-key: isi beberapa key dipisah koma/baris baru di Pengaturan.
  Saat 429/401/5xx/timeout, app otomatis rolling ke key berikutnya.
- **Jangan pernah commit API key.** Key disimpan di
  EncryptedSharedPreferences (fallback SharedPreferences biasa bila
  keystore bermasalah) dan tidak pernah ditulis ke log; di UI hanya
  tampil 4 karakter terakhir.

## Cara pakai

1. Buka **Pengaturan** (tombol di kanan atas) → isi Groq API key
   (dapatkan di https://console.groq.com/keys). Simpan.
2. Di layar utama: **Pilih Video** (rekaman live TikTok, MP4).
3. Pilih jumlah klip (1–4) dan toggle SLSR / CAS / Unsharp.
4. Ketuk **Transkripsi & Cari Hook**. Tunggu sampai 4 kandidat muncul.
5. Pilih tepat N hook (ketuk kartu kandidat; **Preview 5 dtk** untuk
   mendengar awal hook), lalu ketuk **Render N Klip**.
6. Hasil tersimpan di **Downloads/MimunClip** dan tampil di layar Hasil
   — putar di mini player, salin caption, atau bagikan.

## Build lokal

Butuh JDK 17 + Android SDK (API 34, build-tools 34.0.0).

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest   # unit test (Slsr, ContentFilter, planner)
```

Catatan: repo ini tidak menyertakan `gradle-wrapper.jar` (biner), jadi
untuk pertama kali jalankan `gradle wrapper` (bila Gradle sudah
terinstal) atau unduh jar wrapper dari rilis Gradle 8.7. Di GitHub
Actions hal ini tidak diperlukan — workflow menginstal Gradle 8.7
sendiri (lihat `.github/workflows/build-apk.yml`).

## Download APK dari GitHub (tanpa Android Studio)

Setiap push ke `main` dan setiap tag `v*` menjalankan
`.github/workflows/build-apk.yml`:

- Buka tab **Actions** → jalankan terbaru → unduh artifact
  `MimunClip-debug-apk`, atau
- Untuk tag `v*` (mis. `v1.0.0`): APK juga dilampirkan di tab
  **Releases**.

## Batasan SLSR

SLSR hanya bekerja pada MP4 yang memenuhi syarat:

- tepat 1 box `moov` (≤ 32 MB), MP4 non-fragmented (tanpa `moof`/`sidx`/`mfra`/`ssix`);
- tepat 1 track audio AAC stereo (2 channel);
- box `stbl` hanya berisi jenis yang dikenal (`stsd`, `stsz`, `stsc`,
  `stts`, `stco`/`co64`, `sbgp`, `sgpd`, `subs`, `free`, `skip`,
  `ctts`, `stss`, `stps`, `sdtp`, `stdp`).

Bila syarat tidak terpenuhi, app otomatis fallback ke hasil render
langsung (tanpa SLSR) dan menuliskannya di catatan klip.

### Validasi SLSR

Port Kotlin diuji terhadap implementasi Python referensi
(`slsr_6_vcnz.py`) pada file MP4 hasil ffmpeg (H.264 + AAC stereo
44100): patch berhasil, track audio menjadi 2 (asli + clone), durasi
bertambah ~0,35 detik. Unit test JVM (`SlsrTest`) memastikan input
bukan-MP4/rusak gagal secara terkendali (exception) sehingga fallback
berjalan.

## Kenapa bukan `com.arthenica:ffmpeg-kit-full:6.0-2`

FFmpegKit arthenica resmi dipensiunkan dan binarinya **dihapus dari
Maven Central pada 1 April 2025** — koordinat itu sekarang 404.
Proyek ini memakai pengganti drop-in yang masih aktif dan terverifikasi
di Maven Central:

```kotlin
implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7")
```

Paket Java-nya tetap `com.arthenica.ffmpegkit` dan API-nya sama
(`FFmpegKit`, `FFmpegKitConfig`), jadi pemindahan ini hanya ganti
group id. Versi 8.1.7 terverifikasi langsung (AAR mengembalikan HTTP
200 dari Maven Central); 8.1.9 juga tersedia bila ingin naik versi.

## Struktur proyek

```
app/src/main/java/com/mundiah/mimunclip/
├── model/Models.kt            # Segment, ClipCandidate, ClipResult, RenderSettings
├── data/
│   ├── GroqClient.kt          # REST Groq (Whisper + chat), multi-key rolling
│   ├── QwenPlanner.kt         # Rapikan transkrip + 4 kandidat + planner lokal fallback
│   ├── ContentFilter.kt       # Filter stok + promo (satu-satunya sumber aturan)
│   ├── SettingsStore.kt       # DataStore + EncryptedSharedPreferences (Groq key)
│   └── HistoryStore.kt        # Riwayat hasil (JSON lokal)
├── video/
│   ├── Slsr.kt                # Port patch_bytes_slsr (Python -> Kotlin)
│   ├── FfmpegRenderer.kt      # Ekstrak audio, render klip, fallback SLSR
│   └── MediaStoreSaver.kt     # Simpan ke Downloads/MimunClip
├── ui/
│   ├── MainActivity.kt        # Single activity + tema terang/gelap
│   ├── MainViewModel.kt       # State machine: Home -> HookPicker -> Results
│   └── screens/               # Home, Settings, HookPicker, Results, MiniPlayer
└── service/ProcessingService.kt  # Foreground service + notifikasi progres
```

## Privasi

- API key Groq hanya tersimpan di perangkat, tidak dikirim ke mana pun
  selain api.groq.com.
- Video dan transkrip diproses lokal + via Groq; tidak ada server
  MimunClip.
