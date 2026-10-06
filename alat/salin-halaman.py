#!/usr/bin/env python3
"""Menyalin halaman TV Tamu (versi Raspberry Pi) ke dalam aplikasi box, dengan penyesuaian kecil
pada tulisan yang menyebut Raspberry Pi. Jalankan lagi setiap kali halaman di versi Pi berubah:

    python3 alat/salin-halaman.py /jalur/ke/tvtamu/static
"""
import os, shutil, sys

asal = sys.argv[1] if len(sys.argv) > 1 else "../tvtamu/static"
tujuan = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "static")
shutil.rmtree(tujuan, ignore_errors=True)
os.makedirs(os.path.join(tujuan, "font"))

GANTI_ADMIN = [
    # (lama, baru, berapa kali harus ditemukan; 0 = berapa pun asal ada)
    ('el("panel-wifi").hidden = DEMO || !!state.publik;', 'el("panel-wifi").hidden = true;   /* WiFi box diatur dari menu Setelan di box */', 1),
    ('el("isian-layar-tv").hidden = DEMO || !!state.publik;', 'el("isian-layar-tv").hidden = true;   /* menghentikan sinyal ke TV hanya ada di Raspberry Pi */', 1),
    (" Di Raspberry Pi 3 gerakannya bertahap, jadi makin cepat makin terlihat patah-patah.", "", 1),
    ("Jam mengikuti Raspberry Pi, yang mengambil waktu dari internet.", "Jam mengikuti jam box, yang mengambil waktu dari internet.", 1),
    ("Cek apakah Raspberry Pi menyala dan satu jaringan.", "Cek apakah box TV menyala dan satu jaringan.", 1),
    ('Kalau terus begini, lihat bagian "Kalau TV putih atau kosong" di BACA-DULU.txt.', "Kalau terus begini, buka lagi aplikasi TV Tamu di box.", 1),
    ("setelah dipasang di Raspberry Pi", "setelah dipasang di box", 0),
]

def salin(nama, ganti=()):
    s = open(os.path.join(asal, nama), encoding="utf-8").read()
    for lama, baru, kali in ganti:
        n = s.count(lama)
        if n == 0 or (kali and n != kali):
            sys.exit(f"{nama}: teks yang mau diganti ditemukan {n} kali (seharusnya {kali or 'minimal 1'}): {lama[:60]}")
        s = s.replace(lama, baru)
    open(os.path.join(tujuan, nama), "w", encoding="utf-8").write(s)

salin("tv.html")
salin("admin.html", GANTI_ADMIN)
shutil.copy(os.path.join(asal, "logo-agi.svg"), tujuan)
for f in sorted(os.listdir(os.path.join(asal, "font"))):
    shutil.copy(os.path.join(asal, "font", f), os.path.join(tujuan, "font", f))
sisa = [b for b in ("Raspberry", "BACA-DULU") if b in open(os.path.join(tujuan, "admin.html"), encoding="utf-8").read().split("<details class=\"panel\" id=\"panel-wifi\"")[0]]
print("tersalin:", sorted(os.listdir(tujuan)), "| font:", len(os.listdir(os.path.join(tujuan, "font"))), "| sisa sebutan Pi di luar panel WiFi:", sisa or "tidak ada")
