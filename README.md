# Flip Clock (AGI)

Aplikasi jam flip untuk tablet Android, dengan teks kustom dan warna yang bisa diubah dari jauh lewat internet (Firebase Realtime Database).

## Isi repo
- `app/` – aplikasi Android (WebView). Tampilan jam ada di `app/src/main/assets/www/`.
- `app/src/main/assets/www/config.js` – **alamat Firebase** dan alamat halaman remote.
- `docs/` – halaman remote (dibuka di HP/PC), di-hosting gratis lewat GitHub Pages.
- `.github/workflows/build.yml` – build APK otomatis setiap push ke `main`.

## Ambil APK
Tab **Actions** → run terbaru (centang hijau) → bagian **Artifacts** → `FlipClock-apk` → unduh, ekstrak, instal `FlipClock.apk` di tablet.

## Aktifkan halaman remote (sekali saja)
Settings → Pages → Source: *Deploy from a branch* → Branch: `main`, folder `/docs` → Save.
Halaman jadi di `https://brot1994.github.io/FlipClock/`.

## Firebase Realtime Database – Rules
```json
{
  "rules": {
    ".read": false,
    ".write": false,
    "clocks": {
      "$key": {
        ".read": "$key.length >= 20",
        ".write": "$key.length >= 20"
      }
    }
  }
}
```

## Cara pakai
1. Buka aplikasi di tablet → ketuk layar → ikon roda gigi → **Remote via internet**.
2. Scan QR dengan HP → halaman remote terbuka dan tablet otomatis masuk daftar.
3. Ubah teks/warna → **Kirim ke tablet**. Tablet berubah dalam 1–2 detik.

Font: Oswald & Archivo (SIL Open Font License). QR: qrcode-generator (MIT).
