# Epson Print Enabler MAX

Plugin layanan cetak Android (`PrintService`) untuk printer Epson — seperti *Epson Print
Enabler* resmi, tetapi **USB OTG adalah jalur kelas satu**, bukan hanya Wi-Fi.

Setelah plugin diaktifkan di **Settings → Connected devices → Printing**, printer muncul di
dialog cetak bawaan Android dari aplikasi apa pun (Chrome, Foto, Drive, PDF viewer, dll).

---

## Untuk Epson L3110

L3110 itu **USB-only** — tidak punya Wi-Fi maupun Ethernet (yang punya Wi-Fi adalah L3150).
Jadi satu-satunya jalur adalah USB OTG, dan plugin akan mendeteksinya sebagai:

```
mode     raw bulk, ESC/P-R
1284 CMD ESCPL2,BDC,D4,D4PX,ESCPR1,END4
```

Yang dibutuhkan:

* adapter **USB OTG** (USB-C atau micro-USB ke USB-A female) dan kabel USB printer;
* ponsel yang mendukung **USB host** — buka aplikasi ini, printer harus muncul di daftar
  "USB (OTG) printers". Kalau kosong, ponselnya tidak mendukung OTG atau adapternya rusak.

L3110 pakai listrik dari stopkontak, jadi tidak ada masalah daya OTG.

Default rasterisasi 360 dpi (teks/dokumen). Untuk foto naikkan ke 600 di pengaturan plugin,
yang akan dipetakan ke 720 dpi ESC/P-R.

---

## Cara kerja

Android menyerahkan pekerjaan cetak dalam bentuk **PDF**. Plugin ini mengubahnya menjadi
byte yang dimengerti printer, lalu mengirimnya lewat salah satu dari empat jalur:

| Jalur | Transport | Format dikirim | Untuk printer |
|---|---|---|---|
| `USB_IPP` | USB printer class 7 / subclass 1 / **protocol 4** (IPP-over-USB) | PDF langsung, atau PWG-Raster | Epson ±2016 ke atas |
| `USB_RAW` | USB printer class 7, bulk endpoint mentah | ESC/P-R | **L3110** dan Epson lain tanpa IPP |
| `NET_IPP` | TCP, IPP/IPPS (AirPrint, IPP Everywhere) | PDF langsung, atau PWG-Raster | hampir semua Epson Wi-Fi |
| `NET_RAW` | TCP port 9100 | ESC/P-R | printer jaringan jadul |

Pemilihan format mengikuti urutan: **PDF apa adanya** (paling tajam dan cepat, kalau printer
mengiklankan `application/pdf`) → **PWG-Raster** (wajib ada di setiap printer IPP Everywhere,
jadi selalu berhasil) → **ESC/P-R** (untuk jalur mentah).

Kunci desainnya: IPP-over-USB itu **HTTP di atas dua bulk endpoint**. Karena itu
[`HttpOverStream`](app/src/main/java/com/maxprint/epson/transport/HttpOverStream.kt) menulis
HTTP/1.1 secara manual, dan seluruh lapisan IPP di atasnya tidak peduli apakah sedang bicara
ke socket atau ke kabel OTG — jalur Wi-Fi dan USB berbagi kode yang sama persis.

### Deteksi printer USB

Saat printer dicolok, plugin membaca **IEEE-1284 device ID** lewat request `GET_DEVICE_ID`
kelas printer. Dari situ didapat nama model yang benar untuk dialog cetak, dan field `CMD:`
memberi tahu apakah printer menerima ESC/P-R (`ESCPR1`/`ESCPR2`).

### ESC/P-R

Format kabelnya dibaca langsung dari driver referensi LGPL milik Epson,
`epson-inkjet-printer-escpr` 1.8.8 (`lib/epson-escpr-api.c`) — driver yang sama yang
mendukung seri EcoTank L. Bukan hasil tebakan.

```
Framing:  0x1B <class> <panjang param: 4 byte LITTLE endian> <nama: 4 ASCII> <param>
```

Perangkapnya: **panjang perintah little-endian, tetapi semua parameter di dalamnya
big-endian.** Urutan satu job:

```
ExitPacketMode + ESC @ + REMOTE1{JS,JH,HD,PP,US,US} + ExitRemote
ESC ( R "ESCPR"
setq (kualitas, 9 byte) · seti (10 byte) · setj (geometri, 22 byte)
sttp → dsnd × tinggi halaman → endp
endj
```

Dua hal yang mudah salah dan sudah ditangani:

* **`dsnd` mengirim satu baris raster per perintah**, bukan satu pita.
* **Piksel selalu 24-bit RGB**, bahkan untuk job hitam-putih — driver aslinya menahan
  `bpp = 3` dan hanya menandai mono lewat byte colour mode di `setq`. Mengirim grayscale
  8-bit akan dibaca sebagai RGB dan keluar jadi sampah warna.

Geometri `setj` dinyatakan dalam **dot pada resolusi input**. Unit test memverifikasi
perhitungan kami menghasilkan angka yang sama persis dengan tabel `epsMediaSize[]` milik
Epson (A4 @360 dpi = 2976 × 4209 dot, area cetak 2892 × 4125, margin 42 dot ≈ 3 mm).

---

## Struktur kode

```
app/src/main/java/com/maxprint/epson/
├── service/     PrintService, PrinterDiscoverySession, pembuat PrinterInfo
├── discovery/   mDNS (_ipp, _ipps, _pdl-datastream)
├── usb/         enumerasi printer USB, izin, activity USB_DEVICE_ATTACHED
├── transport/   stream bulk USB, IEEE-1284, HTTP-over-stream, IPP-USB, TCP
├── ipp/         encoder/parser biner RFC 8010, klien, pemetaan kapabilitas
├── pdl/         penulis ESC/P-R dan PWG-Raster
├── render/      PdfRenderer → band raster
├── job/         pipeline: PDF → format → transport
└── ui/          layar status, pengaturan, tambah printer manual
```

### Kenapa dirender per-band

A4 pada 600 dpi = 4960 × 7016 piksel = **139 MB** sebagai ARGB_8888. Itu OOM di kebanyakan
ponsel. Kedua format raster yang kita hasilkan berorientasi baris, jadi
[`PdfRasterizer`](app/src/main/java/com/maxprint/epson/render/PdfRasterizer.kt) merender satu
pita (~8 MB), mengirimnya, lalu membebaskannya.

---

## Build

Toolchain sudah terpasang di mesin ini: Android SDK di `C:\Android\sdk` (platform 34,
build-tools 34.0.0, platform-tools), Gradle 8.7 di `C:\Android\gradle-8.7`, JDK 21 di
`C:\Program Files\Java\jdk-21`. `local.properties` sudah menunjuk ke SDK tersebut.

```bash
gradlew.bat :app:assembleDebug
```

APK keluar di `app/build/outputs/apk/debug/app-debug.apk`.

Unit test (murni JVM, tidak butuh perangkat maupun printer):

```bash
gradlew.bat :app:testDebugUnitTest
```

### Pemakaian

1. Install APK (izinkan "install from unknown sources").
2. Aktifkan di **Settings → Connected devices → Printing → Epson Print Enabler MAX**
   (tombol pintasnya ada di layar utama aplikasi).
3. Colok printer lewat adapter OTG.
4. Cetak dari aplikasi apa pun. Saat printer USB dipilih pertama kali, Android meminta izin
   akses USB — centang "always" supaya tidak ditanya lagi.

---

## Status tiap bagian

| Bagian | Status |
|---|---|
| Build | Kompilasi bersih, APK debug 6,2 MB terbentuk |
| Unit test | 9 test lewat (ESC/P-R 5, IPP 2, PWG-Raster 2) |
| ESC/P-R | Framing, urutan perintah, geometri dan RLE diverifikasi terhadap driver resmi Epson |
| IPP biner (RFC 8010) | Spesifikasi publik, lengkap, ada test round-trip |
| PWG-Raster (PWG 5102.4) | Header 1796 byte dan RLE sesuai spesifikasi, ada test decode |
| IPP-over-USB | Sesuai spesifikasi ipp-usb (class 7/1/4, HTTP di atas bulk) |
| Enumerasi USB + IEEE-1284 | Spesifikasi USB printer class |
| **Cetak sungguhan ke L3110** | **Belum diuji — tidak ada printer di mesin build ini** |

Baris terakhir itu yang penting: semuanya terverifikasi terhadap spesifikasi dan lewat test,
tetapi belum ada kertas yang benar-benar keluar. Kalau ada masalah saat dicoba, aktifkan
**Verbose logging** di pengaturan plugin lalu ambil lognya:

```bash
adb logcat -s EpsonMax
```

---

## Yang belum ada

- `image/urf` (AirPrint URF). Printer yang hanya menerima URF akan dikirimi PWG-Raster, yang
  formatnya mirip tetapi header-nya berbeda — perlu penulis tersendiri.
- Pembacaan status tinta/kertas untuk ditampilkan di notifikasi cetak.
- Borderless dan CD/DVD label (ESC/P-R mendukungnya, kami selalu pakai margin 3 mm).
- Beberapa antarmuka IPP-USB paralel. Sekarang satu antarmuka dipakai berurutan, jadi
  pekerjaan cetak diproses satu per satu.
