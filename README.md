# 🎵 Music Player

A Spotify-inspired desktop music player built with **Java + JavaFX**.  
Supports local files, cloud streaming, album art, lyrics, and more.

---

## ✨ Features

- 🎵 **Play local audio** — MP3 & WAV support
- ☁️ **Cloud streaming** — Stream langsung dari Google Drive / Dropbox / URL (tanpa menyimpan ke lokal)
- 🖼️ **Album Art** — Otomatis tampil dari ID3 tag file MP3
- 🎤 **Lyrics** — Lirik otomatis dari ID3 tag atau fetch dari internet
- 🔍 **Search** — Cari lagu berdasarkan judul atau nama artis
- 📋 **Queue** — Tambah lagu ke antrian putar
- 🕐 **Recently Played** — Riwayat lagu yang baru diputar
- 🔀 **Shuffle & Repeat** — Mode acak dan pengulangan (None / Repeat 1 / Repeat All)
- 🪟 **Mini Player** — Mode player kecil always-on-top
- 🔃 **Sort Playlist** — Urutkan lagu A–Z
- ⬇️ **YouTube Download** — Download lagu dari YouTube via yt-dlp
- ⌨️ **Keyboard Shortcuts** — Space (play/pause), ← → (prev/next)
- 💾 **Auto-save Playlist** — Playlist tersimpan otomatis saat aplikasi ditutup

---

## 🛠️ Tech Stack

| Teknologi | Versi |
|-----------|-------|
| Java | 24 |
| JavaFX | 21.0.2 |
| Maven | 3.9+ |
| mp3agic | 0.9.1 (ID3 tags) |
| mp3spi | 1.9.5.4 (audio decoding) |

---

## 🚀 Cara Menjalankan

### Prerequisites
- Java 24+
- Maven 3.9+

### Run
```bash
mvn javafx:run
📁 Struktur Project

MusicPlayer/
├── src/main/java/com/musicplayer/
│   ├── Main.java              # Entry point
│   ├── PlayerController.java  # Logika utama player
│   └── Song.java              # Model data lagu
└── src/main/resources/com/musicplayer/
    ├── player.fxml            # Layout UI
    └── player.css             # Styling
📸 Screenshot
Coming soon

📝 License
MIT License — bebas digunakan dan dimodifikasi.
