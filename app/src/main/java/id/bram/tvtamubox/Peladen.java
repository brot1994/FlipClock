package id.bram.tvtamubox;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Server TV Tamu di dalam box: menyimpan tulisan, melayani halaman TV (/tv) dan halaman admin (/admin)
 * untuk laptop atau HP di jaringan yang sama. Isinya sama dengan server.py di versi Raspberry Pi,
 * tanpa pintu internet (login) dan tanpa pengaturan WiFi.
 *
 * Sengaja hanya memakai Java biasa dan org.json (tanpa kelas Android), supaya bisa diuji di komputer.
 */
public class Peladen {

    /** Berkas halaman yang ikut di dalam aplikasi (tv.html, admin.html, logo-agi.svg, font/...). */
    public interface Sumber {
        /** Isi berkas, atau null kalau tidak ada. */
        byte[] baca(String nama);
    }

    static final Set<String> EKST_VIDEO = new HashSet<>(Arrays.asList(".mp4", ".webm", ".m4v"));
    static final Set<String> EKST_FOTO = new HashSet<>(Arrays.asList(".jpg", ".jpeg", ".png", ".webp"));
    static final Set<String> EKST_LOGO = new HashSet<>(Arrays.asList(".png", ".jpg", ".jpeg", ".webp", ".svg"));
    static final long MAKS_UPLOAD = 1024L * 1024 * 1024;      // 1 GB per berkas
    static final long MAKS_LOGO = 10L * 1024 * 1024;
    static final long SISA_DISK_MIN = 300L * 1024 * 1024;     // sisakan 300 MB di penyimpanan box
    static final String[] TEMA = {"gelap", "gelapmerah", "terang", "pucat"};
    /** Isi satu urutan: semua = salam + nama + instansi + pesan; tamupesan = nama + instansi + pesan; tamu = nama + instansi;
     *  teks = tulisan sendiri; kosong = tanpa tulisan. */
    static final String[] URUTAN_ISI = {"semua", "tamupesan", "salam", "tamu", "nama", "pesan", "teks", "kosong"};
    /** Warna satu urutan; ikut = memakai "Warna tulisan" di pengaturan layar. */
    static final String[] URUTAN_WARNA = {"ikut", "hitam", "putih", "merah"};
    static final int URUTAN_MAKS = 20;
    static final int SIMPANAN_MAKS = 20;
    static final String[] GAYA_KUNCI = {"salam", "garis", "nama", "instansi", "pesan", "jam", "tanggal", "perusahaan"};

    private final File data, media, logo, berkasState;
    private final Sumber statis;
    private final String versi;
    private final Object kunci = new Object();
    private JSONObject state;
    private volatile long tvTerakhir = 0;          // System.nanoTime() saat halaman TV terakhir meminta data; 0 = belum pernah
    private volatile boolean ukurNyala = false;
    private volatile JSONObject ukurHasil = null;
    private ServerSocket soket;
    private final ExecutorService pekerja = Executors.newCachedThreadPool();

    public Peladen(File folderData, Sumber statis, String versi) {
        this.data = folderData;
        this.media = new File(folderData, "media");
        this.logo = new File(folderData, "logo");
        this.berkasState = new File(folderData, "state.json");
        this.statis = statis;
        this.versi = versi;
        media.mkdirs();
        logo.mkdirs();
        muatState();
    }

    // ------------------------------------------------------------------ hidup dan mati

    public void mulai(int port) throws IOException {
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(port));
        soket = s;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                terima();
            }
        }, "tvtamu-peladen");
        t.setDaemon(true);
        t.start();
    }

    public void berhenti() {
        try {
            if (soket != null) soket.close();
        } catch (IOException ignored) {
        }
        pekerja.shutdownNow();
    }

    public int port() {
        return soket == null ? -1 : soket.getLocalPort();
    }

    /** Arah putar halaman TV yang sedang dipakai (0, 90, 180, atau 270). */
    public int putar() {
        synchronized (kunci) {
            return state.optInt("putar", 0);
        }
    }

    /** Detik sejak halaman TV terakhir meminta data; -1 kalau belum pernah. */
    public int detikTv() {
        long t = tvTerakhir;
        return t == 0 ? -1 : (int) ((System.nanoTime() - t) / 1000000000L);
    }

    private void terima() {
        while (!soket.isClosed()) {
            try {
                final Socket s = soket.accept();
                pekerja.execute(new Runnable() {
                    @Override
                    public void run() {
                        layani(s);
                    }
                });
            } catch (IOException e) {
                if (soket.isClosed()) return;
            } catch (RuntimeException e) {
                return;
            }
        }
    }

    // ------------------------------------------------------------------ keadaan (state)

    private static JSONObject bawaan() {
        try {
            JSONObject b = new JSONObject();
            b.put("mode", "standby");
            b.put("salam", "Selamat Datang");
            b.put("tamu", new JSONArray());
            b.put("cara_tampil", "baris");
            b.put("pesan", "");
            b.put("perusahaan", "PT Asano Gear Indonesia");
            b.put("standby_judul", "Selamat Datang");
            b.put("standby_teks", "");
            b.put("latar", new JSONObject().put("jenis", "rodagigi").put("file", ""));
            b.put("gelap", 45);
            b.put("perataan", "tengah");
            b.put("jam", true);
            b.put("layar", "nyala");
            b.put("layar_tv", false);
            b.put("putar", 90);
            b.put("logo", "");
            b.put("logo_ukuran", 100);
            b.put("logo_posisi", "kiri");
            b.put("posisi_isi", 50);
            b.put("roda_kecepatan", 100);
            b.put("tema", "gelap");
            b.put("tata_teks", "biasa");
            b.put("warna_teks", "tema");
            b.put("urutan", new JSONArray()
                    .put(satuUrutan("salam", 100, 50, 4, "ikut"))
                    .put(satuUrutan("tamu", 100, 50, 8, "ikut"))
                    .put(satuUrutan("pesan", 100, 50, 4, "ikut")));
            // satu simpanan bawaan: urutan yang cocok dengan video "Welcome to PT. Asano Gear Indonesia Plant KIM" (19 detik)
            b.put("urutan_simpanan", new JSONArray().put(new JSONObject()
                    .put("nama", "Video 1 (Welcome to PT AGI Plant KIM)").put("latar", "")
                    .put("urutan", new JSONArray()
                            .put(satuUrutan("kosong", 100, 50, 8.1, "ikut"))
                            .put(satuUrutan("tamupesan", 90, 72, 7.3, "hitam"))
                            .put(satuUrutan("kosong", 100, 50, 0.8, "ikut"))
                            .put(satuUrutan("nama", 85, 93, 2.8, "hitam")))));
            JSONObject gaya = new JSONObject();
            for (String k : GAYA_KUNCI) gaya.put(k, new JSONObject().put("u", 100).put("j", 100));
            b.put("gaya", gaya);
            b.put("rev", 0);
            return b;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JSONObject satuUrutan(String isi, int ukuran, int posisi, double lama, String warna) throws Exception {
        return new JSONObject().put("isi", isi).put("teks", "").put("ukuran", ukuran).put("posisi", posisi).put("lama", lama).put("warna", warna);
    }

    private static void timpa(JSONObject tujuan, JSONObject dari) throws Exception {
        Iterator<String> it = dari.keys();
        while (it.hasNext()) {
            String k = it.next();
            tujuan.put(k, dari.get(k));
        }
    }

    private void muatState() {
        JSONObject s = bawaan();
        try {
            JSONObject tersimpan = new JSONObject(new String(bacaSemua(berkasState), StandardCharsets.UTF_8));
            timpa(s, bersihkan(tersimpan, s));
            s.put("rev", tersimpan.optInt("rev", 0));
        } catch (Exception ignored) {
            // belum ada berkas, atau berkasnya rusak: pakai bawaan
        }
        state = s;
    }

    private void simpanState() throws IOException {
        File tmp = new File(data, "state.json.tmp");
        String teks;
        try {
            teks = state.toString(1);
        } catch (Exception e) {
            throw new IOException(e);
        }
        FileOutputStream f = new FileOutputStream(tmp);
        try {
            f.write(teks.getBytes(StandardCharsets.UTF_8));
            f.flush();
            f.getFD().sync();
        } finally {
            f.close();
        }
        if (!tmp.renameTo(berkasState)) {
            berkasState.delete();
            if (!tmp.renameTo(berkasState)) throw new IOException("tidak bisa menulis state.json");
        }
    }

    private JSONObject ubahState(JSONObject perubahan) throws Exception {
        synchronized (kunci) {
            timpa(state, bersihkan(perubahan, state));
            state.put("rev", state.optInt("rev", 0) + 1);
            simpanState();
            return new JSONObject(state.toString());
        }
    }

    private JSONObject salinState() throws Exception {
        synchronized (kunci) {
            return new JSONObject(state.toString());
        }
    }

    /** State untuk dikirim ke halaman: ditambah versi halaman dan kabar browser TV. */
    private JSONObject keluar(JSONObject s) throws Exception {
        s.put("publik", false);
        s.put("versi", versi);
        s.put("waktu", System.currentTimeMillis());
        int d = detikTv();
        s.put("tv_detik", d < 0 ? JSONObject.NULL : (Object) Integer.valueOf(d));
        s.put("ukur", ukurNyala);
        return s;
    }

    // ---------- pembersih masukan (sama dengan bersihkan() di server.py)

    private static boolean kosong(Object v) {
        return v == null || v == JSONObject.NULL;
    }

    /** Membuang karakter kendali (kecuali baris baru), merapikan tepi, memotong sampai maks karakter. */
    static String teks(Object v, int maks) {
        String s = kosong(v) ? "" : String.valueOf(v);
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\n') {
                b.appendCodePoint(cp);
                continue;
            }
            switch (Character.getType(cp)) {
                case Character.CONTROL:
                case Character.FORMAT:
                case Character.SURROGATE:
                case Character.PRIVATE_USE:
                case Character.UNASSIGNED:
                    break;
                default:
                    b.appendCodePoint(cp);
            }
        }
        String r = rapikan(b.toString());
        if (r.codePointCount(0, r.length()) > maks) r = r.substring(0, r.offsetByCodePoints(0, maks));
        return r;
    }

    private static boolean putih(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp);
    }

    private static String rapikan(String s) {
        int a = 0, z = s.length();
        while (a < z) {
            int cp = s.codePointAt(a);
            if (!putih(cp)) break;
            a += Character.charCount(cp);
        }
        while (z > a) {
            int cp = s.codePointBefore(z);
            if (!putih(cp)) break;
            z -= Character.charCount(cp);
        }
        return s.substring(a, z);
    }

    /** Seperti int() di Python: bilangan dipotong, teks angka dibaca, selain itu gagal (null). */
    private static Integer keInt(Object v) {
        if (v instanceof Boolean) return ((Boolean) v) ? 1 : 0;
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) return null;
            if (d > Integer.MAX_VALUE) return Integer.MAX_VALUE;
            if (d < Integer.MIN_VALUE) return Integer.MIN_VALUE;
            return (int) d;
        }
        if (v instanceof String) {
            try {
                return Integer.valueOf(Integer.parseInt(((String) v).trim()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static boolean benar(Object v) {
        if (kosong(v)) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).doubleValue() != 0;
        if (v instanceof String) return ((String) v).length() > 0;
        if (v instanceof JSONArray) return ((JSONArray) v).length() > 0;
        if (v instanceof JSONObject) return ((JSONObject) v).length() > 0;
        return true;
    }

    private static boolean salahSatu(Object v, String... pilihan) {
        if (!(v instanceof String)) return false;
        for (String p : pilihan) if (p.equals(v)) return true;
        return false;
    }

    private static int jepit(int v, int bawah, int atas) {
        return Math.max(bawah, Math.min(atas, v));
    }

    private static String namaDasar(String jalur) {
        int i = jalur.lastIndexOf('/');
        return i < 0 ? jalur : jalur.substring(i + 1);
    }

    /** Ambil hanya kolom yang dikenal, dengan nilai yang masuk akal. */
    private JSONObject bersihkan(JSONObject masuk, JSONObject sekarang) throws Exception {
        JSONObject k = new JSONObject();
        if (salahSatu(masuk.opt("mode"), "tamu", "standby")) k.put("mode", masuk.opt("mode"));
        String[] kolom = {"salam", "pesan", "perusahaan", "standby_judul", "standby_teks"};
        int[] maks = {80, 400, 80, 80, 400};
        for (int i = 0; i < kolom.length; i++) {
            if (masuk.has(kolom[i])) k.put(kolom[i], teks(masuk.opt(kolom[i]), maks[i]));
        }
        if (masuk.opt("tamu") instanceof JSONArray) {
            JSONArray asal = (JSONArray) masuk.opt("tamu"), daftar = new JSONArray();
            for (int i = 0; i < asal.length() && i < 12; i++) {
                if (!(asal.opt(i) instanceof JSONObject)) continue;
                JSONObject t = (JSONObject) asal.opt(i);
                String nama = teks(t.opt("nama"), 80).replace('\n', ' ');
                String inst = teks(t.opt("instansi"), 100).replace('\n', ' ');
                if (nama.length() > 0 || inst.length() > 0) {
                    daftar.put(new JSONObject().put("nama", nama).put("instansi", inst));
                }
            }
            k.put("tamu", daftar);
        }
        if (salahSatu(masuk.opt("cara_tampil"), "baris", "gantian")) k.put("cara_tampil", masuk.opt("cara_tampil"));
        if (salahSatu(masuk.opt("perataan"), "tengah", "kiri")) k.put("perataan", masuk.opt("perataan"));
        if (masuk.has("jam")) k.put("jam", benar(masuk.opt("jam")));
        if (salahSatu(masuk.opt("layar"), "nyala", "mati")) k.put("layar", masuk.opt("layar"));
        if (masuk.has("layar_tv")) k.put("layar_tv", benar(masuk.opt("layar_tv")));
        if (masuk.opt("putar") instanceof Number) {
            double p = ((Number) masuk.opt("putar")).doubleValue();
            if (p == 0 || p == 90 || p == 180 || p == 270) k.put("putar", (int) p);
        }
        if (masuk.has("gelap")) {
            Integer g = keInt(masuk.opt("gelap"));
            if (g != null) k.put("gelap", jepit(g, 0, 85));
        }
        if (masuk.opt("latar") instanceof JSONObject) {
            JSONObject lt = (JSONObject) masuk.opt("latar");
            Object jenis = lt.opt("jenis");
            if (salahSatu(jenis, "rodagigi", "polos", "rodasudut")) {
                k.put("latar", new JSONObject().put("jenis", jenis).put("file", ""));
            } else if ("file".equals(jenis)) {
                String nama = namaDasar(teks(lt.opt("file"), 200));
                if (nama.length() > 0 && new File(media, nama).isFile()) {
                    k.put("latar", new JSONObject().put("jenis", "file").put("file", nama));
                }
            }
        }
        if (masuk.has("logo")) {
            String nama = namaDasar(teks(masuk.opt("logo"), 200));
            if (nama.equals("") || nama.equals("-") || new File(logo, nama).isFile()) k.put("logo", nama);
        }
        if (masuk.has("logo_ukuran")) {
            Integer u = keInt(masuk.opt("logo_ukuran"));
            if (u != null) k.put("logo_ukuran", jepit(u, 40, 250));
        }
        if (salahSatu(masuk.opt("tema"), TEMA)) k.put("tema", masuk.opt("tema"));
        if (salahSatu(masuk.opt("tata_teks"), "biasa", "urutan")) k.put("tata_teks", masuk.opt("tata_teks"));
        if (salahSatu(masuk.opt("warna_teks"), "tema", "hitam", "putih", "merah")) k.put("warna_teks", masuk.opt("warna_teks"));
        if (masuk.opt("urutan") instanceof JSONArray) k.put("urutan", bersihUrutan((JSONArray) masuk.opt("urutan")));
        if (masuk.opt("urutan_simpanan") instanceof JSONArray) {
            JSONArray asal = (JSONArray) masuk.opt("urutan_simpanan"), simpanan = new JSONArray();
            Set<String> terpakai = new HashSet<>();
            for (int i = 0; i < asal.length() && simpanan.length() < SIMPANAN_MAKS; i++) {
                if (!(asal.opt(i) instanceof JSONObject)) continue;
                JSONObject sp = (JSONObject) asal.opt(i);
                if (!(sp.opt("urutan") instanceof JSONArray)) continue;
                String nama = teks(sp.opt("nama"), 60).replace('\n', ' ');
                if (nama.length() == 0 || !terpakai.add(nama)) continue;      // tanpa nama, atau nama yang sama dua kali: dilewati
                simpanan.put(new JSONObject().put("nama", nama).put("latar", namaDasar(teks(sp.opt("latar"), 200)))
                        .put("urutan", bersihUrutan((JSONArray) sp.opt("urutan"))));
            }
            k.put("urutan_simpanan", simpanan);
        }
        if (masuk.has("posisi_isi")) {
            Integer v = keInt(masuk.opt("posisi_isi"));
            if (v != null) k.put("posisi_isi", jepit(v, 0, 100));
        }
        if (masuk.has("roda_kecepatan")) {
            Integer v = keInt(masuk.opt("roda_kecepatan"));
            if (v != null) k.put("roda_kecepatan", jepit(v, 0, 400));
        }
        if (salahSatu(masuk.opt("logo_posisi"), "kiri", "tengah", "kanan")) k.put("logo_posisi", masuk.opt("logo_posisi"));
        if (masuk.opt("gaya") instanceof JSONObject) {
            JSONObject baruSemua = (JSONObject) masuk.opt("gaya");
            JSONObject lama = sekarang.opt("gaya") instanceof JSONObject ? (JSONObject) sekarang.opt("gaya") : new JSONObject();
            JSONObject gaya = new JSONObject();
            String[] bagian = {"u", "j"};
            int[][] batas = {{40, 250}, {0, 400}};
            for (String kunciGaya : GAYA_KUNCI) {
                JSONObject dasar = lama.opt(kunciGaya) instanceof JSONObject ? (JSONObject) lama.opt(kunciGaya) : new JSONObject();
                JSONObject baru = baruSemua.opt(kunciGaya) instanceof JSONObject ? (JSONObject) baruSemua.opt(kunciGaya) : new JSONObject();
                JSONObject satu = new JSONObject();
                for (int i = 0; i < bagian.length; i++) {
                    Object nilai = baru.has(bagian[i]) ? baru.opt(bagian[i]) : dasar.has(bagian[i]) ? dasar.opt(bagian[i]) : (Object) Integer.valueOf(100);
                    Integer n = keInt(nilai);
                    satu.put(bagian[i], n == null ? 100 : jepit(n, batas[i][0], batas[i][1]));
                }
                gaya.put(kunciGaya, satu);
            }
            k.put("gaya", gaya);
        }
        return k;
    }

    /** Daftar urutan tulisan: hanya isi yang dikenal, dengan ukuran, posisi, lama, dan warna yang masuk akal. */
    private static JSONArray bersihUrutan(JSONArray asal) throws Exception {
        JSONArray daftar = new JSONArray();
        for (int i = 0; i < asal.length() && daftar.length() < URUTAN_MAKS; i++) {
            if (!(asal.opt(i) instanceof JSONObject)) continue;
            JSONObject u = (JSONObject) asal.opt(i);
            if (!salahSatu(u.opt("isi"), URUTAN_ISI)) continue;
            JSONObject satu = new JSONObject();
            satu.put("isi", u.opt("isi"));
            satu.put("teks", "teks".equals(u.opt("isi")) ? teks(u.opt("teks"), 200) : "");
            Integer ukuran = u.has("ukuran") ? keInt(u.opt("ukuran")) : Integer.valueOf(100);
            satu.put("ukuran", ukuran == null ? 100 : jepit(ukuran, 40, 250));
            Integer posisi = u.has("posisi") ? keInt(u.opt("posisi")) : Integer.valueOf(50);
            satu.put("posisi", posisi == null ? 50 : jepit(posisi, 0, 100));
            // lama dalam detik; disimpan dalam persepuluhan detik, 0,5 sampai 600
            double lama = 5;
            Object l = u.opt("lama");
            if (l instanceof Number && !(Double.isNaN(((Number) l).doubleValue()) || Double.isInfinite(((Number) l).doubleValue()))) {
                lama = ((Number) l).doubleValue();
            }
            long sepersepuluh = (long) (Math.max(0.0, Math.min(1e6, lama)) * 10 + 0.5);
            satu.put("lama", Math.max(5, Math.min(6000, sepersepuluh)) / 10.0);
            satu.put("warna", salahSatu(u.opt("warna"), URUTAN_WARNA) ? u.opt("warna") : "ikut");
            daftar.put(satu);
        }
        return daftar;
    }

    // ------------------------------------------------------------------ berkas unggahan

    private static String[] pisahEkstensi(String nama) {
        int i = nama.lastIndexOf('.');
        if (i <= 0) return new String[]{nama, ""};
        // titik-titik di depan nama bukan pemisah ekstensi (".profil" tidak punya ekstensi)
        boolean semuaTitik = true;
        for (int j = 0; j < i; j++) if (nama.charAt(j) != '.') semuaTitik = false;
        if (semuaTitik) return new String[]{nama, ""};
        return new String[]{nama.substring(0, i), nama.substring(i)};
    }

    /** Nama berkas yang aman untuk disimpan; null kalau jenisnya tidak diizinkan. */
    static String namaAman(String nama, Set<String> ekstensiBoleh) {
        nama = Normalizer.normalize(namaDasar(nama.replace('\\', '/')), Normalizer.Form.NFKC);
        String[] p = pisahEkstensi(nama);
        String ekst = p[1].toLowerCase(Locale.ROOT);
        if (!ekstensiBoleh.contains(ekst)) return null;
        StringBuilder b = new StringBuilder();
        boolean ganti = false;
        for (int i = 0; i < p[0].length(); ) {
            int cp = p[0].codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp) || cp == '_' || cp == '-' || cp == ' ') {
                b.appendCodePoint(cp);
                ganti = false;
            } else if (!ganti) {
                b.append('_');
                ganti = true;
            }
        }
        String dasar = b.toString();
        int a = 0, z = dasar.length();
        while (a < z && " ._".indexOf(dasar.charAt(a)) >= 0) a++;
        while (z > a && " ._".indexOf(dasar.charAt(z - 1)) >= 0) z--;
        dasar = dasar.substring(a, z);
        if (dasar.codePointCount(0, dasar.length()) > 60) dasar = dasar.substring(0, dasar.offsetByCodePoints(0, 60));
        if (dasar.length() == 0) dasar = "berkas";
        return dasar + ekst;
    }

    private static String namaBelumDipakai(File folder, String nama) {
        String[] p = pisahEkstensi(nama);
        String calon = nama;
        int n = 2;
        while (new File(folder, calon).exists()) {
            calon = p[0] + "-" + n + p[1];
            n++;
        }
        return calon;
    }

    private JSONArray daftarMedia() throws Exception {
        File[] isi = media.listFiles();
        List<File> urut = new ArrayList<>();
        if (isi != null) urut.addAll(Arrays.asList(isi));
        Collections.sort(urut, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                long x = a.lastModified(), y = b.lastModified();
                return x < y ? -1 : x > y ? 1 : a.getName().compareTo(b.getName());
            }
        });
        JSONArray hasil = new JSONArray();
        for (File f : urut) {
            String ekst = pisahEkstensi(f.getName())[1].toLowerCase(Locale.ROOT);
            if (!f.isFile() || !(EKST_VIDEO.contains(ekst) || EKST_FOTO.contains(ekst))) continue;
            hasil.put(new JSONObject().put("nama", f.getName())
                    .put("jenis", EKST_VIDEO.contains(ekst) ? "video" : "foto")
                    .put("ukuran", f.length())
                    .put("url", "/media/" + kutip(f.getName())));
        }
        return hasil;
    }

    // ------------------------------------------------------------------ HTTP

    private static class Galat extends Exception {
        final int kode;

        Galat(int kode, String pesan) {
            super(pesan);
            this.kode = kode;
        }
    }

    private static class Minta {
        String cara, jalurMentah, jalur, kueri;
        final Map<String, String> kepala = new HashMap<>();
        long panjang = -1;
        boolean panjangRusak = false;
        boolean dariSini;
        InputStream masuk;
        OutputStream keluar;
        boolean tutup = false;
        boolean kunciIsi = false;

        String kepala(String nama) {
            return kepala.get(nama.toLowerCase(Locale.ROOT));
        }
    }

    private static String barisKepala(InputStream in, int[] sisa) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(128);
        while (true) {
            int c = in.read();
            if (c < 0) return b.size() == 0 ? null : b.toString("ISO-8859-1");
            if (--sisa[0] < 0) throw new IOException("kepala permintaan terlalu panjang");
            if (c == '\n') break;
            if (c != '\r') b.write(c);
        }
        return b.toString("ISO-8859-1");
    }

    private void layani(Socket s) {
        try {
            s.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(s.getInputStream(), 64 * 1024);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 64 * 1024);
            boolean dariSini = s.getInetAddress() != null && s.getInetAddress().isLoopbackAddress();
            while (true) {
                s.setSoTimeout(30000);                       // sambungan menganggur ditutup setelah 30 detik
                int[] sisa = {32 * 1024};
                String awal = barisKepala(in, sisa);
                if (awal == null) return;
                if (awal.length() == 0) continue;
                String[] bagian = awal.split(" ");
                if (bagian.length < 2) return;
                Minta m = new Minta();
                m.cara = bagian[0];
                m.jalurMentah = bagian[1];
                m.dariSini = dariSini;
                m.masuk = in;
                m.keluar = out;
                if (bagian.length < 3 || bagian[2].equals("HTTP/1.0")) m.tutup = true;
                String baris;
                while ((baris = barisKepala(in, sisa)) != null && baris.length() > 0) {
                    int i = baris.indexOf(':');
                    if (i > 0) m.kepala.put(baris.substring(0, i).trim().toLowerCase(Locale.ROOT), baris.substring(i + 1).trim());
                }
                String sambung = m.kepala("Connection");
                if (sambung != null && sambung.toLowerCase(Locale.ROOT).contains("close")) m.tutup = true;
                String pj = m.kepala("Content-Length");
                if (pj != null) {
                    try {
                        m.panjang = Long.parseLong(pj.trim());
                    } catch (NumberFormatException e) {
                        m.panjangRusak = true;
                    }
                }
                int q = m.jalurMentah.indexOf('?');
                m.jalur = q < 0 ? m.jalurMentah : m.jalurMentah.substring(0, q);
                m.kueri = q < 0 ? "" : m.jalurMentah.substring(q + 1);
                s.setSoTimeout(60000);                       // unggahan besar: tiap potongan boleh menunggu 1 menit
                try {
                    rute(m);
                } catch (Galat g) {
                    kirimGalat(m, g.kode, g.getMessage());
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    kirimGalat(m, 500, "Kesalahan di server: " + e.getClass().getSimpleName());
                }
                out.flush();
                if (m.tutup) return;
            }
        } catch (SocketTimeoutException ignored) {
        } catch (IOException ignored) {
        } catch (Exception e) {
            // kesalahan tak terduga pada satu sambungan tidak boleh mematikan server
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final String[][] TIPE = {
            {".html", "text/html; charset=utf-8"}, {".svg", "image/svg+xml"}, {".png", "image/png"},
            {".jpg", "image/jpeg"}, {".jpeg", "image/jpeg"}, {".webp", "image/webp"}, {".mp4", "video/mp4"},
            {".m4v", "video/mp4"}, {".webm", "video/webm"}, {".woff2", "font/woff2"},
            {".txt", "text/plain; charset=utf-8"}, {".css", "text/css; charset=utf-8"},
            {".js", "application/javascript; charset=utf-8"}, {".json", "application/json"},
    };

    private static String tipe(String nama) {
        String n = nama.toLowerCase(Locale.ROOT);
        for (String[] t : TIPE) if (n.endsWith(t[0])) return t[1];
        return "application/octet-stream";
    }

    private static String keterangan(int kode) {
        switch (kode) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 411: return "Length Required";
            case 413: return "Payload Too Large";
            case 415: return "Unsupported Media Type";
            case 416: return "Range Not Satisfiable";
            case 500: return "Internal Server Error";
            case 501: return "Not Implemented";
            case 507: return "Insufficient Storage";
            default: return "OK";
        }
    }

    private void kepalaJawab(Minta m, int kode, String... pasangan) throws IOException {
        StringBuilder b = new StringBuilder(256);
        b.append("HTTP/1.1 ").append(kode).append(' ').append(keterangan(kode)).append("\r\n");
        b.append("Server: TVTamuBox/1.0\r\n");
        for (int i = 0; i + 1 < pasangan.length; i += 2) b.append(pasangan[i]).append(": ").append(pasangan[i + 1]).append("\r\n");
        b.append("X-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nX-Frame-Options: SAMEORIGIN\r\n");
        if (m.kunciIsi) {      // berkas unggahan tidak boleh menjalankan skrip
            b.append("Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; sandbox\r\n");
        }
        if (m.tutup) b.append("Connection: close\r\n");
        b.append("\r\n");
        m.keluar.write(b.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    private void kirimJson(Minta m, Object isi, int kode) throws IOException {
        byte[] badan = isi.toString().getBytes(StandardCharsets.UTF_8);
        kepalaJawab(m, kode, "Content-Type", "application/json; charset=utf-8",
                "Content-Length", String.valueOf(badan.length), "Cache-Control", "no-store");
        if (!"HEAD".equals(m.cara)) m.keluar.write(badan);
    }

    private void kirimGalat(Minta m, int kode, String pesan) throws IOException {
        m.tutup = true;          // badan permintaan mungkin belum terbaca; jangan pakai lagi sambungan ini
        try {
            kirimJson(m, new JSONObject().put("ok", false).put("pesan", pesan), kode);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private void kirimStatis(Minta m, String nama, String cache) throws IOException, Galat {
        byte[] isi = statis.baca(nama);
        if (isi == null) throw new Galat(404, "Berkas tidak ditemukan.");
        kepalaJawab(m, 200, "Content-Type", tipe(nama), "Content-Length", String.valueOf(isi.length), "Cache-Control", cache);
        if (!"HEAD".equals(m.cara)) m.keluar.write(isi);
    }

    private void kirimBerkas(Minta m, File f, String cache) throws IOException, Galat {
        if (!f.isFile()) throw new Galat(404, "Berkas tidak ditemukan.");
        long ukuran = f.length(), awal = 0, akhir = ukuran - 1;
        int kode = 200;
        String rentang = m.kepala("Range");
        if (rentang != null) {
            String r = rentang.trim();
            if (r.startsWith("bytes=") && r.indexOf('-') > 0 && r.indexOf(',') < 0) {
                String a = r.substring(6, r.indexOf('-')), z = r.substring(r.indexOf('-') + 1);
                if ((a.length() > 0 || z.length() > 0) && angka(a) && angka(z)) {
                    try {
                        if (a.length() > 0) {
                            awal = Long.parseLong(a);
                            if (z.length() > 0) akhir = Math.min(Long.parseLong(z), ukuran - 1);
                        } else {
                            awal = Math.max(0, ukuran - Long.parseLong(z));
                        }
                        if (awal > akhir || awal >= ukuran) {
                            kepalaJawab(m, 416, "Content-Range", "bytes */" + ukuran, "Content-Length", "0");
                            return;
                        }
                        kode = 206;
                    } catch (NumberFormatException e) {
                        awal = 0;
                        akhir = ukuran - 1;
                    }
                }
            }
        }
        long panjang = akhir - awal + 1;
        if (kode == 206) {
            kepalaJawab(m, kode, "Content-Type", tipe(f.getName()), "Content-Length", String.valueOf(panjang),
                    "Accept-Ranges", "bytes", "Cache-Control", cache, "Content-Range", "bytes " + awal + "-" + akhir + "/" + ukuran);
        } else {
            kepalaJawab(m, kode, "Content-Type", tipe(f.getName()), "Content-Length", String.valueOf(panjang),
                    "Accept-Ranges", "bytes", "Cache-Control", cache);
        }
        if ("HEAD".equals(m.cara)) return;
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        try {
            raf.seek(awal);
            byte[] buf = new byte[64 * 1024];
            long sisa = panjang;
            while (sisa > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, sisa));
                if (n <= 0) break;
                m.keluar.write(buf, 0, n);
                sisa -= n;
            }
            if (sisa > 0) m.tutup = true;
        } finally {
            raf.close();
        }
    }

    private static boolean angka(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        return true;
    }

    private JSONObject bacaJson(Minta m) throws IOException, Galat {
        if (m.panjangRusak) throw new Galat(400, "Permintaan tidak valid.");
        long n = Math.max(0, m.panjang);
        if (n > 200000) throw new Galat(413, "Data terlalu besar.");
        byte[] isi = new byte[(int) n];
        int terbaca = 0;
        while (terbaca < n) {
            int r = m.masuk.read(isi, terbaca, (int) n - terbaca);
            if (r < 0) throw new Galat(400, "Data tidak terbaca.");
            terbaca += r;
        }
        String s = new String(isi, StandardCharsets.UTF_8).trim();
        if (s.length() == 0) return new JSONObject();
        try {
            if (!s.startsWith("{")) throw new Galat(400, "Data tidak terbaca.");
            return new JSONObject(s);
        } catch (Galat g) {
            throw g;
        } catch (Exception e) {
            throw new Galat(400, "Data tidak terbaca.");
        }
    }

    private String terimaUpload(Minta m, File folder, Set<String> ekstensiBoleh, long batas) throws IOException, Galat {
        String nama = namaAman(parameter(m.kueri, "nama"), ekstensiBoleh);
        if (nama == null) {
            List<String> e = new ArrayList<>();
            for (String x : ekstensiBoleh) e.add(x.substring(1));
            Collections.sort(e);
            StringBuilder b = new StringBuilder("Jenis berkas tidak didukung. Pakai: ");
            for (int i = 0; i < e.size(); i++) b.append(i > 0 ? ", " : "").append(e.get(i));
            throw new Galat(415, b.append(".").toString());
        }
        if (m.panjangRusak || m.panjang < 0) throw new Galat(411, "Ukuran berkas tidak diketahui.");
        long n = m.panjang;
        if (n == 0) throw new Galat(400, "Berkas kosong.");
        if (n > batas) throw new Galat(413, "Berkas terlalu besar (maksimal " + (batas / (1024 * 1024)) + " MB).");
        if (folder.getUsableSpace() - n < SISA_DISK_MIN) {
            throw new Galat(507, "Ruang penyimpanan box tidak cukup. Hapus latar yang tidak dipakai.");
        }
        nama = namaBelumDipakai(folder, nama);
        File tujuan = new File(folder, nama), tmp = new File(folder, nama + ".unggah");
        long sisa = n;
        try {
            FileOutputStream f = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[64 * 1024];
                while (sisa > 0) {
                    int r = m.masuk.read(buf, 0, (int) Math.min(buf.length, sisa));
                    if (r < 0) throw new Galat(400, "Unggahan terputus, coba lagi.");
                    f.write(buf, 0, r);
                    sisa -= r;
                }
                f.flush();
                f.getFD().sync();
            } finally {
                f.close();
            }
            if (!tmp.renameTo(tujuan)) throw new IOException("tidak bisa menyimpan " + nama);
        } finally {
            if (tmp.exists()) tmp.delete();
        }
        return nama;
    }

    // ---------- rute

    private void rute(Minta m) throws Exception {
        boolean baca = "GET".equals(m.cara) || "HEAD".equals(m.cara);
        try {
            if (baca) ruteBaca(m);
            else if ("POST".equals(m.cara)) ruteKirim(m);
            else if ("DELETE".equals(m.cara)) ruteHapus(m);
            else throw new Galat(405, "Cara ini tidak didukung.");
        } catch (Galat g) {
            throw g;
        } catch (IOException e) {
            if (baca) throw e;
            throw new Galat(500, "Gagal menyimpan: " + e.getMessage());
        }
    }

    private void ruteBaca(Minta m) throws Exception {
        String jalur = lepasKutip(m.jalur);
        if (jalur.equals("/") || jalur.equals("/tv") || jalur.equals("/tv.html")) {
            kirimStatis(m, "tv.html", "no-cache");
            return;
        }
        if (jalur.equals("/admin") || jalur.equals("/admin/") || jalur.equals("/admin.html")) {
            kirimStatis(m, "admin.html", "no-cache");
            return;
        }
        if (jalur.equals("/api/state")) {
            if (("&" + m.kueri + "&").contains("&tv=1&") && m.dariSini) {
                tvTerakhir = Math.max(1, System.nanoTime());
                if (ukurNyala && parameter(m.kueri, "fps").length() > 0) {
                    JSONObject h = new JSONObject();
                    h.put("fps", desimal(parameter(m.kueri, "fps")));
                    h.put("video_tampil", desimal(parameter(m.kueri, "vt")));
                    h.put("video_hilang", desimal(parameter(m.kueri, "vh")));
                    h.put("lebar", desimal(parameter(m.kueri, "lw")));
                    h.put("tinggi", desimal(parameter(m.kueri, "lh")));
                    h.put("browser", teks(m.kepala("User-Agent"), 300));
                    h.put("waktu", System.currentTimeMillis() / 1000.0);
                    ukurHasil = h;
                }
            }
            kirimJson(m, keluar(salinState()), 200);
            return;
        }
        if (jalur.equals("/api/ukur")) {
            kirimJson(m, keadaanUkur(), 200);
            return;
        }
        if (jalur.equals("/logo-agi.svg")) {
            kirimStatis(m, "logo-agi.svg", "max-age=3600");
            return;
        }
        if (jalur.equals("/api/wifi")) {
            throw new Galat(501, "WiFi box diatur dari menu Setelan di box, bukan dari halaman ini.");
        }
        if (jalur.equals("/api/media")) {
            kirimJson(m, new JSONObject().put("media", daftarMedia()).put("sisa_disk", media.getUsableSpace()), 200);
            return;
        }
        String[] awalan = {"/media/", "/logo/", "/font/"};
        for (String a : awalan) {
            if (!jalur.startsWith(a)) continue;
            String nama = namaDasar(jalur.substring(a.length()));
            if (nama.length() == 0 || nama.startsWith(".") || nama.indexOf('\\') >= 0) break;
            if (a.equals("/font/")) {
                kirimStatis(m, "font/" + nama, "max-age=604800");
            } else {
                m.kunciIsi = true;
                kirimBerkas(m, new File(a.equals("/media/") ? media : logo, nama), "max-age=86400");
            }
            return;
        }
        throw new Galat(404, "Halaman tidak ditemukan.");
    }

    private JSONObject keadaanUkur() throws Exception {
        JSONObject h = ukurHasil;
        return new JSONObject().put("nyala", ukurNyala).put("hasil", h == null ? JSONObject.NULL : h);
    }

    private static Object desimal(String s) {
        try {
            return Math.round(Double.parseDouble(s) * 10) / 10.0;
        } catch (NumberFormatException e) {
            return JSONObject.NULL;
        }
    }

    private void ruteKirim(Minta m) throws Exception {
        String jalur = m.jalur;
        if (jalur.equals("/api/state")) {
            kirimJson(m, keluar(ubahState(bacaJson(m))), 200);
            return;
        }
        if (jalur.equals("/api/ukur")) {
            ukurNyala = benar(bacaJson(m).opt("nyala"));
            ukurHasil = null;
            kirimJson(m, keadaanUkur(), 200);
            return;
        }
        if (jalur.equals("/api/media")) {
            String nama = terimaUpload(m, media, gabung(EKST_VIDEO, EKST_FOTO), MAKS_UPLOAD);
            kirimJson(m, new JSONObject().put("ok", true).put("nama", nama), 200);
            return;
        }
        if (jalur.equals("/api/logo")) {
            String nama = terimaUpload(m, logo, EKST_LOGO, MAKS_LOGO);
            JSONObject s = ubahState(new JSONObject().put("logo", nama));
            File[] isi = logo.listFiles();          // hanya satu logo unggahan yang disimpan
            if (isi != null) for (File f : isi) if (!f.getName().equals(nama)) f.delete();
            kirimJson(m, keluar(s), 200);
            return;
        }
        throw new Galat(404, "Tidak ditemukan.");
    }

    private void ruteHapus(Minta m) throws Exception {
        if (m.jalur.equals("/api/media")) {
            String nama = namaDasar(parameter(m.kueri, "nama"));
            File f = new File(media, nama);
            if (nama.length() == 0 || nama.indexOf('\\') >= 0 || !f.isFile()) throw new Galat(404, "Berkas tidak ditemukan.");
            boolean dipakai;
            synchronized (kunci) {
                JSONObject lt = state.optJSONObject("latar");
                dipakai = lt != null && "file".equals(lt.optString("jenis")) && nama.equals(lt.optString("file"));
            }
            if (!f.delete()) throw new Galat(500, "Gagal menghapus berkas.");
            if (dipakai) ubahState(new JSONObject().put("latar", new JSONObject().put("jenis", "rodagigi")));
            kirimJson(m, new JSONObject().put("ok", true), 200);
            return;
        }
        if (m.jalur.equals("/api/logo")) {
            String lama;
            synchronized (kunci) {
                lama = state.optString("logo", "");
            }
            JSONObject s = ubahState(new JSONObject().put("logo", ""));
            if (lama.length() > 0 && !lama.equals("-")) new File(logo, namaDasar(lama)).delete();
            kirimJson(m, keluar(s), 200);
            return;
        }
        throw new Galat(404, "Tidak ditemukan.");
    }

    // ------------------------------------------------------------------ alat kecil

    private static Set<String> gabung(Set<String> a, Set<String> b) {
        Set<String> s = new HashSet<>(a);
        s.addAll(b);
        return s;
    }

    private static byte[] bacaSemua(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    /** Membuka sandi persen (%20 dan sejenisnya) sebagai UTF-8; tanda tambah dibiarkan apa adanya. */
    static String lepasKutip(String s) {
        if (s.indexOf('%') < 0) return s;
        ByteArrayOutputStream b = new ByteArrayOutputStream(s.length());
        byte[] asal = s.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < asal.length; i++) {
            if (asal[i] == '%' && i + 2 < asal.length) {
                int x = Character.digit(asal[i + 1], 16), y = Character.digit(asal[i + 2], 16);
                if (x >= 0 && y >= 0) {
                    b.write(x * 16 + y);
                    i += 2;
                    continue;
                }
            }
            b.write(asal[i]);
        }
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Nilai satu parameter dari bagian kueri alamat ("" kalau tidak ada). */
    static String parameter(String kueri, String nama) {
        for (String p : kueri.split("&")) {
            int i = p.indexOf('=');
            String k = i < 0 ? p : p.substring(0, i);
            if (lepasKutip(k.replace('+', ' ')).equals(nama)) return i < 0 ? "" : lepasKutip(p.substring(i + 1).replace('+', ' '));
        }
        return "";
    }

    /** Menyandi nama berkas untuk dipakai di alamat (sama dengan urllib.parse.quote). */
    static String kutip(String s) {
        StringBuilder b = new StringBuilder();
        for (byte x : s.getBytes(StandardCharsets.UTF_8)) {
            int c = x & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || "_.-~/".indexOf(c) >= 0) b.append((char) c);
            else b.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16))).append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
        }
        return b.toString();
    }
}
