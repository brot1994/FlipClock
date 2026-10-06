package id.bram.tvtamubox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Penjawab nama: membuat box ini bisa dipanggil dengan nama (tvtamu.local) dari laptop atau HP di jaringan
 * yang sama, seperti Raspberry Pi. Caranya mengikuti mDNS (RFC 6762), bagian yang diperlukan saja.
 *
 * Sengaja dibuat mengalah. Sebelum mengaku punya nama itu, ditanyakan dulu ke jaringan; kalau sudah ada
 * perangkat lain yang menjawab (misalnya Raspberry Pi TV Tamu), box ini diam dan tidak ikut menjawab.
 * Kalau di tengah jalan ada perangkat lain yang mulai memakai nama itu, box ini juga langsung melepasnya.
 *
 * Hanya memakai java.net, supaya bisa diuji di komputer biasa tanpa Android.
 */
final class Mdns {

    static final int PORT = 5353;
    private static final int T_A = 1, T_AAAA = 28, T_NSEC = 47, T_ANY = 255;
    private static final int UMUR = 120;                       // detik jawaban boleh diingat penanya
    private static final long JEDA_TANYA = 250;                // jarak antar pertanyaan saat memeriksa nama
    private static final long JEDA_JAWAB = 250;                // jarak terpendek antar jawaban ke semua

    private final String nama;                                 // tanpa ".local", huruf kecil
    private final String namaLengkap;                          // "tvtamu.local"
    private final byte[] namaDns;
    private final long cobaLagi;                               // milidetik sebelum memeriksa lagi setelah mengalah
    private final InetAddress grup;

    private volatile boolean jalan;
    private volatile boolean punya;
    private volatile String keadaan = "belum mulai";
    private Thread benang;
    private MulticastSocket soket;

    /** Satu alamat IPv4 box ini beserta kartu jaringannya. */
    private static final class Alamat {
        final NetworkInterface kartu;
        final Inet4Address ip;
        final int awalan;

        Alamat(NetworkInterface kartu, Inet4Address ip, int awalan) {
            this.kartu = kartu;
            this.ip = ip;
            this.awalan = awalan;
        }
    }

    private static final class Rusak extends Exception {
    }

    Mdns(String nama) {
        this(nama, 120000);
    }

    Mdns(String nama, long cobaLagi) {
        this.nama = nama.toLowerCase(Locale.ROOT);
        this.namaLengkap = this.nama + ".local";
        this.namaDns = sandiNama(this.nama);
        this.cobaLagi = cobaLagi;
        InetAddress g = null;
        try {
            g = InetAddress.getByAddress(new byte[]{(byte) 224, 0, 0, (byte) 251});
        } catch (Exception ignored) {
        }
        this.grup = g;
    }

    /** True kalau box ini yang sekarang menjawab untuk nama itu. */
    boolean punya() {
        return punya;
    }

    String namaLengkap() {
        return namaLengkap;
    }

    String keadaan() {
        return keadaan;
    }

    synchronized void mulai() {
        if (jalan) return;
        jalan = true;
        benang = new Thread(new Runnable() {
            @Override
            public void run() {
                putaran();
            }
        }, "mdns");
        benang.setDaemon(true);
        benang.start();
    }

    synchronized void berhenti() {
        jalan = false;
        punya = false;
        MulticastSocket s = soket;
        if (s != null) s.close();
    }

    // ---------------------------------------------------------------- putaran utama

    private static final int TUNGGU = 0, PERIKSA = 1, PUNYA = 2, PASIF = 3;

    private void putaran() {
        while (jalan) {
            try {
                layani();
            } catch (Exception e) {
                keadaan = "terhenti: " + e;
            }
            punya = false;
            MulticastSocket s = soket;
            soket = null;
            if (s != null) s.close();
            tidur(10000);
        }
    }

    private void tidur(long ms) {
        long sampai = System.currentTimeMillis() + ms;
        while (jalan && System.currentTimeMillis() < sampai) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private int tahap;
    private long kapanCoba;                 // PASIF: kapan memeriksa lagi

    private void layani() throws IOException {
        MulticastSocket s = new MulticastSocket(null);
        soket = s;
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(PORT));
        s.setTimeToLive(255);
        s.setSoTimeout(200);

        List<Alamat> alamat = new ArrayList<>();
        String sidik = "";                  // daftar alamat terakhir, untuk tahu kalau jaringannya berubah
        long kapanLihat = 0, kapanTanya = 0, kapanUmum = 0, kapanJawab = 0;
        int tanya = 0, umum = 0;
        tahap = TUNGGU;
        keadaan = "menunggu jaringan";
        byte[] buf = new byte[9000];

        while (jalan) {
            long kini = System.currentTimeMillis();

            // Jaringan bisa baru tersambung atau berganti setelah aplikasi dibuka: dilihat ulang tiap 5 detik.
            if (kini - kapanLihat >= 5000) {
                kapanLihat = kini;
                alamat = alamatBox();
                StringBuilder b = new StringBuilder();
                for (Alamat a : alamat) b.append(a.kartu.getName()).append('=').append(a.ip.getHostAddress()).append(' ');
                if (!b.toString().equals(sidik)) {
                    sidik = b.toString();
                    for (Alamat a : alamat) {
                        try {
                            s.joinGroup(new InetSocketAddress(grup, PORT), a.kartu);
                        } catch (Exception sudah) {
                            // sudah bergabung di kartu itu
                        }
                    }
                    punya = false;
                    if (alamat.isEmpty()) {
                        tahap = TUNGGU;
                        keadaan = "menunggu jaringan";
                    } else {
                        tahap = PERIKSA;
                        tanya = 0;
                        kapanTanya = kini + (long) (Math.random() * 250);
                        keadaan = "memeriksa nama";
                    }
                }
            }

            if (tahap == PASIF && kini >= kapanCoba && !alamat.isEmpty()) {
                tahap = PERIKSA;
                tanya = 0;
                kapanTanya = kini;
                keadaan = "memeriksa nama";
            }
            if (tahap == PERIKSA && kini >= kapanTanya) {
                if (tanya < 3) {
                    kirimKeSemua(s, alamat, paketTanya());
                    tanya++;
                    kapanTanya = kini + (tanya < 3 ? JEDA_TANYA : 750);
                } else {
                    // Tiga kali ditanyakan dan tidak ada yang menjawab: nama ini bebas dipakai.
                    tahap = PUNYA;
                    punya = true;
                    keadaan = "menjawab untuk " + namaLengkap;
                    umum = 0;
                    kapanUmum = kini;
                }
            }
            if (tahap == PUNYA && umum < 2 && kini >= kapanUmum) {
                umumkan(s, alamat);
                umum++;
                kapanUmum = kini + 1000;
                kapanJawab = kini;
            }

            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                s.receive(p);
            } catch (SocketTimeoutException e) {
                continue;
            }
            int[] hasil;
            try {
                hasil = baca(buf, p.getLength(), alamat);
            } catch (Rusak e) {
                continue;
            } catch (RuntimeException e) {
                continue;
            }
            if (hasil == null) continue;
            if (hasil[0] == ORANG_LAIN) {
                if (tahap == PERIKSA || tahap == PUNYA) {
                    keadaan = "nama " + namaLengkap + " dipakai perangkat lain (" + p.getAddress().getHostAddress() + ")";
                }
                if (tahap != TUNGGU) {
                    punya = false;
                    tahap = PASIF;
                    kapanCoba = System.currentTimeMillis() + cobaLagi;
                }
                continue;
            }
            if (tahap != PUNYA) continue;
            boolean mintaA = hasil[1] != 0;
            if (p.getPort() != PORT) {
                // Penanya sederhana (bukan dari port 5353, misalnya HP Android): dijawab langsung kepadanya.
                Alamat a = untuk(p.getAddress(), alamat);
                if (a == null) continue;
                byte[] j = paketJawabLangsung(hasil[2], hasil[3], mintaA, a.ip, buf, hasil[4], hasil[5]);
                try {
                    s.send(new DatagramPacket(j, j.length, p.getAddress(), p.getPort()));
                } catch (IOException e) {
                    // penanya sudah tidak terjangkau
                }
            } else if (System.currentTimeMillis() - kapanJawab >= JEDA_JAWAB) {
                kapanJawab = System.currentTimeMillis();
                umumkan(s, alamat);
            }
        }
    }

    // ---------------------------------------------------------------- alamat box

    private static List<Alamat> alamatBox() {
        List<Alamat> hasil = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                try {
                    if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast()) continue;
                    for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                        InetAddress a = ia.getAddress();
                        if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                            hasil.add(new Alamat(ni, (Inet4Address) a, ia.getNetworkPrefixLength()));
                            break;                      // satu alamat per kartu jaringan
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return hasil;
    }

    /** Alamat box yang sejaringan dengan penanya; kalau tidak ada yang cocok, alamat pertama. */
    private static Alamat untuk(InetAddress penanya, List<Alamat> alamat) {
        byte[] p = penanya.getAddress();
        if (p.length == 4) {
            for (Alamat a : alamat) {
                byte[] q = a.ip.getAddress();
                int bit = Math.max(0, Math.min(32, a.awalan));
                boolean sama = true;
                for (int i = 0; i < 4 && sama; i++) {
                    int n = Math.max(0, Math.min(8, bit - i * 8));
                    int topeng = n == 0 ? 0 : (0xFF << (8 - n)) & 0xFF;
                    if ((p[i] & topeng) != (q[i] & topeng)) sama = false;
                }
                if (sama) return a;
            }
        }
        return alamat.isEmpty() ? null : alamat.get(0);
    }

    private void kirimKeSemua(MulticastSocket s, List<Alamat> alamat, byte[] isi) {
        for (Alamat a : alamat) {
            try {
                s.setNetworkInterface(a.kartu);
                s.send(new DatagramPacket(isi, isi.length, grup, PORT));
            } catch (Exception ignored) {
            }
        }
    }

    /** Memberi tahu semua perangkat di tiap jaringan: nama ini ada di alamat ini. */
    private void umumkan(MulticastSocket s, List<Alamat> alamat) {
        for (Alamat a : alamat) {
            byte[] isi = paketUmum(a.ip);
            try {
                s.setNetworkInterface(a.kartu);
                s.send(new DatagramPacket(isi, isi.length, grup, PORT));
            } catch (Exception ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- membaca paket

    private static final int ORANG_LAIN = 1, DITANYA = 2;

    /**
     * Membaca satu paket. Hasil: null (bukan urusan kita), {ORANG_LAIN} (perangkat lain memakai atau sedang
     * meminta nama kita), atau {DITANYA, mintaA, nomor paket, jenis pertanyaan, awal, akhir pertanyaan itu}.
     */
    private int[] baca(byte[] d, int n, List<Alamat> alamat) throws Rusak {
        if (n < 12) return null;
        int bendera = u16(d, 2), jTanya = u16(d, 4), jJawab = u16(d, 6), jWenang = u16(d, 8), jTambah = u16(d, 10);
        boolean jawaban = (bendera & 0x8000) != 0;
        if ((bendera & 0x7800) != 0) return null;                   // bukan pertanyaan/jawaban biasa
        int[] pos = {12};
        boolean ditanya = false, mintaA = false;
        int jenisPertama = 0, awalPertama = 0, akhirPertama = 0;
        for (int i = 0; i < jTanya; i++) {
            int awal = pos[0];
            String nm = bacaNama(d, n, pos);
            if (pos[0] + 4 > n) throw new Rusak();
            int jenis = u16(d, pos[0]), kelas = u16(d, pos[0] + 2) & 0x7FFF;
            pos[0] += 4;
            if (!nm.equals(namaLengkap) || (kelas != 1 && kelas != 255)) continue;
            if (jenis == T_A || jenis == T_ANY || jenis == T_AAAA) {
                if (!ditanya) {
                    jenisPertama = jenis;
                    awalPertama = awal;
                    akhirPertama = pos[0];
                }
                ditanya = true;
                if (jenis != T_AAAA) mintaA = true;
            }
        }
        if (!jawaban) {
            // Pertanyaan yang membawa calon jawaban di bagian "wenang" adalah perangkat lain yang sedang
            // meminta nama ini. Box ini tidak pernah mengirim yang seperti itu, jadi pasti bukan dari sini.
            if (ditanya && jWenang > 0) return new int[]{ORANG_LAIN};
            return ditanya ? new int[]{DITANYA, mintaA ? 1 : 0, u16(d, 0), jenisPertama, awalPertama, akhirPertama} : null;
        }
        int semua = jJawab + jWenang + jTambah;
        for (int i = 0; i < semua; i++) {
            String nm = bacaNama(d, n, pos);
            if (pos[0] + 10 > n) throw new Rusak();
            int jenis = u16(d, pos[0]), kelas = u16(d, pos[0] + 2) & 0x7FFF;
            long umur = ((long) u16(d, pos[0] + 4) << 16) | u16(d, pos[0] + 6);
            int panjang = u16(d, pos[0] + 8);
            int isi = pos[0] + 10;
            pos[0] = isi + panjang;
            if (pos[0] > n) throw new Rusak();
            if (!nm.equals(namaLengkap) || kelas != 1 || jenis != T_A || panjang != 4 || umur == 0) continue;
            boolean milikKita = false;
            for (Alamat a : alamat) {
                byte[] q = a.ip.getAddress();
                if (q[0] == d[isi] && q[1] == d[isi + 1] && q[2] == d[isi + 2] && q[3] == d[isi + 3]) milikKita = true;
            }
            if (!milikKita) return new int[]{ORANG_LAIN};          // nama kita, tetapi alamatnya bukan alamat box ini
        }
        return null;
    }

    private static int u16(byte[] d, int i) {
        return ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF);
    }

    /** Membaca nama (boleh memakai penunjuk ke bagian lain paket), dikembalikan dengan huruf kecil. */
    private static String bacaNama(byte[] d, int n, int[] pos) throws Rusak {
        StringBuilder b = new StringBuilder();
        int i = pos[0], lompat = 0;
        boolean sudahLompat = false;
        while (true) {
            if (i >= n) throw new Rusak();
            int p = d[i] & 0xFF;
            if (p == 0) {
                if (!sudahLompat) pos[0] = i + 1;
                break;
            }
            if ((p & 0xC0) == 0xC0) {
                if (i + 1 >= n || ++lompat > 20) throw new Rusak();
                if (!sudahLompat) pos[0] = i + 2;
                sudahLompat = true;
                i = ((p & 0x3F) << 8) | (d[i + 1] & 0xFF);
                continue;
            }
            if ((p & 0xC0) != 0 || i + 1 + p > n || b.length() > 255) throw new Rusak();
            if (b.length() > 0) b.append('.');
            for (int k = 0; k < p; k++) {
                char c = (char) (d[i + 1 + k] & 0xFF);
                b.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
            }
            i += 1 + p;
        }
        return b.toString();
    }

    // ---------------------------------------------------------------- menyusun paket

    private static byte[] sandiNama(String nama) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (String bagian : new String[]{nama, "local"}) {
            o.write(bagian.length());
            for (int i = 0; i < bagian.length(); i++) o.write(bagian.charAt(i));
        }
        o.write(0);
        return o.toByteArray();
    }

    private static void t16(ByteArrayOutputStream o, int v) {
        o.write((v >> 8) & 0xFF);
        o.write(v & 0xFF);
    }

    private static void kepala(ByteArrayOutputStream o, int nomor, int bendera, int tanya, int jawab, int tambah) {
        t16(o, nomor);
        t16(o, bendera);
        t16(o, tanya);
        t16(o, jawab);
        t16(o, 0);
        t16(o, tambah);
    }

    private void catatanA(ByteArrayOutputStream o, Inet4Address ip, int kelas, int umur) {
        o.write(namaDns, 0, namaDns.length);
        t16(o, T_A);
        t16(o, kelas);
        t16(o, 0);
        t16(o, umur);
        t16(o, 4);
        byte[] a = ip.getAddress();
        o.write(a, 0, 4);
    }

    /** Keterangan "nama ini hanya punya alamat IPv4", supaya penanya alamat IPv6 tidak menunggu lama. */
    private void catatanHanyaA(ByteArrayOutputStream o, int kelas, int umur) {
        o.write(namaDns, 0, namaDns.length);
        t16(o, T_NSEC);
        t16(o, kelas);
        t16(o, 0);
        t16(o, umur);
        t16(o, namaDns.length + 3);
        o.write(namaDns, 0, namaDns.length);
        o.write(0);                 // kelompok jenis 0-255
        o.write(1);                 // satu byte peta
        o.write(0x40);              // hanya jenis 1 (A)
    }

    /** Pertanyaan biasa "siapa yang punya nama ini?" (tanpa calon jawaban, jadi tidak mengganggu pemiliknya). */
    private byte[] paketTanya() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        kepala(o, 0, 0, 1, 0, 0);
        o.write(namaDns, 0, namaDns.length);
        t16(o, T_A);
        t16(o, 1);
        return o.toByteArray();
    }

    /** Jawaban untuk semua perangkat (lewat alamat grup). */
    private byte[] paketUmum(Inet4Address ip) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        kepala(o, 0, 0x8400, 0, 1, 1);
        catatanA(o, ip, 0x8001, UMUR);
        catatanHanyaA(o, 0x8001, UMUR);
        return o.toByteArray();
    }

    /** Jawaban langsung untuk satu penanya sederhana: nomor paket dan pertanyaannya diulang. */
    private byte[] paketJawabLangsung(int nomor, int jenis, boolean mintaA, Inet4Address ip,
                                      byte[] asal, int awal, int akhir) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        kepala(o, nomor, 0x8400, 1, mintaA ? 1 : 0, 0);
        if (akhir - awal == namaDns.length + 4) {
            o.write(asal, awal, akhir - awal);      // pertanyaan diulang persis seperti yang dikirim (termasuk huruf besar-kecilnya)
        } else {
            o.write(namaDns, 0, namaDns.length);
            t16(o, jenis);
            t16(o, 1);
        }
        if (mintaA) catatanA(o, ip, 1, 10);
        return o.toByteArray();
    }
}
