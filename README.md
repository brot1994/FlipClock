# TV Tamu (versi box Android)

Layar selamat datang untuk TV yang disambungkan ke box Android (diuji di Akari AX512, Android 9).
Box sendiri yang menyimpan tulisan dan melayani halaman admin, jadi tidak perlu Raspberry Pi maupun internet.

## Membangun APK

Setiap ada perubahan di cabang `main`, GitHub Actions membangun APK. Buka tab **Actions**, pilih proses
terbaru, lalu unduh **TVTamu-apk** di bagian Artifacts. Isinya `app-debug.apk`.

## Memasang dan memakai

1. Pasang `app-debug.apk` di box, lalu buka aplikasi **TV Tamu**.
2. Di layar muncul alamat admin, misalnya `http://192.168.1.50:8080/admin`, dan beberapa detik kemudian
   juga `http://tvtamu.local:8080/admin`. Buka salah satunya di laptop atau HP yang satu jaringan dengan box.
   Tombol **OK** di remote menampilkan lagi alamat itu.
3. Isi nama tamu, pilih latar, tema, dan lain-lain di halaman admin. TV langsung mengikuti.

Aplikasi tidak terbuka sendiri saat box dinyalakan; buka aplikasi **TV Tamu** dari menu box setiap kali mau dipakai.

## Yang perlu diketahui

- Halaman admin tidak memakai password. Siapa pun di jaringan yang sama bisa mengganti tulisan.
- Alamat angka bisa berubah kalau router membagikan alamat baru; tekan OK di remote untuk melihat yang
  sekarang. Alamat nama (`http://tvtamu.local:8080/admin`) tidak ikut berubah.
- `:8080` di alamat tidak bisa dihilangkan: Android tidak mengizinkan aplikasi memakai port 80.
- Nama `tvtamu.local` hanya bisa dipakai satu perangkat di satu jaringan. Kalau di jaringan itu sudah ada
  perangkat lain bernama `tvtamu` (misalnya Raspberry Pi TV Tamu), box mengalah: perangkat itu tidak
  terganggu, baris alamat nama tidak muncul di layar box, dan box dibuka lewat alamat angka. Box memeriksa
  lagi tiap 2 menit, jadi kalau perangkat itu dimatikan, namanya dipakai box.
- Alamat nama biasanya tidak bisa dibuka dari HP Android lama (sebelum Android 12) dan dari sebagian
  jaringan kantor yang memblokirnya. Kalau begitu, pakai alamat angka.
- Tulisan, latar, dan logo tersimpan di box dan tidak hilang saat aplikasi diperbarui. Semuanya ikut
  terhapus kalau aplikasinya dihapus (uninstall).
- WiFi dan jam diatur dari menu Setelan di box.
- Video latar sebaiknya MP4 (H.264), berdiri 720 x 1280, 24-30 gambar per detik, pendek, tanpa suara.

## Isi proyek

- `app/src/main/java/.../Peladen.java`: server (sama perilakunya dengan `server.py` di versi Raspberry Pi,
  tanpa pintu internet dan pengaturan WiFi).
- `app/src/main/java/.../Mdns.java`: penjawab nama `tvtamu.local` (mDNS), dibuat mengalah kepada perangkat lain.
- `app/src/main/assets/static/`: halaman TV dan admin. Disalin dari versi Pi dengan
  `python3 alat/salin-halaman.py /jalur/ke/tvtamu/static`.
- `alat/JalanLokal.java`: menjalankan server di komputer biasa untuk diuji.
