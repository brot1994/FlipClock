import id.bram.tvtamubox.Peladen;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/** Menjalankan server box di komputer biasa, untuk diuji:  java JalanLokal <folder data> <folder halaman> <port> */
public class JalanLokal {
    public static void main(String[] a) throws Exception {
        final File halaman = new File(a[1]);
        Peladen p = new Peladen(new File(a[0]), new Peladen.Sumber() {
            @Override
            public byte[] baca(String nama) {
                File f = new File(halaman, nama);
                if (!f.isFile()) return null;
                try (InputStream in = new FileInputStream(f)) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    return out.toByteArray();
                } catch (Exception e) {
                    return null;
                }
            }
        }, "uji");
        p.mulai(Integer.parseInt(a[2]));
        System.out.println("Server box jalan di port " + p.port());
        Thread.sleep(Long.MAX_VALUE);
    }
}
