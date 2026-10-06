package id.bram.tvtamubox;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.wifi.WifiManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Menyalakan server TV Tamu sekali saat aplikasi dibuka, dan menyimpannya selama aplikasi hidup. */
public class Aplikasi extends Application {

    static final int PORT_PERTAMA = 8080;
    static final String NAMA_JARINGAN = "tvtamu";       // box bisa dipanggil dengan http://tvtamu.local:8080/admin

    private static Peladen peladen;
    private static Mdns mdns;
    private static WifiManager.MulticastLock kunciWifi;
    private static String galat = "";

    /** Penjawab nama (tvtamu.local), atau null kalau tidak bisa dinyalakan. */
    static Mdns mdns() {
        return mdns;
    }

    static Peladen peladen() {
        return peladen;
    }

    static String galat() {
        return galat;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mulaiPeladen();
        mulaiNama();
    }

    /** Menyalakan penjawab nama, supaya box ini bisa dipanggil dengan nama seperti Raspberry Pi. */
    private synchronized void mulaiNama() {
        if (peladen == null || mdns != null) return;
        try {
            // Tanpa izin ini, WiFi di banyak perangkat membuang pertanyaan nama yang datang dari perangkat lain.
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                kunciWifi = wifi.createMulticastLock("tvtamu");
                kunciWifi.setReferenceCounted(false);
                kunciWifi.acquire();
            }
        } catch (Exception ignored) {
            // box tanpa WiFi (hanya kabel LAN): tidak perlu izin itu
        }
        try {
            Mdns m = new Mdns(NAMA_JARINGAN);
            m.mulai();
            mdns = m;
        } catch (Exception ignored) {
            // alamat angka tetap bisa dipakai
        }
    }

    private synchronized void mulaiPeladen() {
        if (peladen != null) return;
        File data = getExternalFilesDir(null);          // penyimpanan milik aplikasi; ikut terhapus kalau aplikasi dihapus
        if (data == null) data = getFilesDir();
        data = new File(data, "data");
        data.mkdirs();

        String versi = "0";
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            versi = String.valueOf(info.lastUpdateTime);   // berubah saat aplikasi diperbarui, sehingga halaman TV memuat ulang
        } catch (Exception ignored) {
        }

        final Map<String, byte[]> simpanan = new HashMap<>();
        Peladen.Sumber halaman = new Peladen.Sumber() {
            @Override
            public byte[] baca(String nama) {
                synchronized (simpanan) {
                    if (simpanan.containsKey(nama)) return simpanan.get(nama);
                    byte[] isi = null;
                    if (!nama.contains("..")) {
                        try {
                            InputStream in = getAssets().open("static/" + nama);
                            try {
                                ByteArrayOutputStream out = new ByteArrayOutputStream();
                                byte[] buf = new byte[16 * 1024];
                                int n;
                                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                                isi = out.toByteArray();
                            } finally {
                                in.close();
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    if (isi != null) simpanan.put(nama, isi);
                    return isi;
                }
            }
        };

        Peladen p = new Peladen(data, halaman, versi);
        Exception terakhir = null;
        for (int port = PORT_PERTAMA; port < PORT_PERTAMA + 5; port++) {
            try {
                p.mulai(port);
                peladen = p;
                galat = "";
                return;
            } catch (Exception e) {
                terakhir = e;
            }
        }
        galat = "Server tidak bisa dinyalakan: " + (terakhir == null ? "?" : terakhir.getMessage());
    }
}
