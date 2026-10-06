package id.bram.tvtamubox;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Layar TV Tamu: halaman TV ditampilkan layar penuh, diambil dari server di dalam aplikasi ini sendiri.
 * Tulisan diganti dari laptop atau HP di jaringan yang sama lewat halaman admin.
 *
 * Tombol remote:  OK = tampilkan / sembunyikan alamat admin,  BACK = keluar.
 */
public class MainActivity extends Activity {

    private static final long TAMPIL_ALAMAT = 45000;     // alamat admin tampil 45 detik setelah aplikasi dibuka
    private static final long JEDA_JAGA = 30000;         // penjaga memeriksa halaman TV tiap 30 detik
    private static final int BATAS_DIAM = 60;            // halaman dianggap macet kalau diam selama ini (detik)
    private static final long TENGGANG = 90000;          // waktu untuk memuat halaman sebelum dianggap gagal

    private FrameLayout akar;
    private WebView web;
    private TextView kotak;
    private final Handler h = new Handler();
    private boolean tampak = false;
    private long terakhirMuat = 0;

    @Override
    protected void onCreate(Bundle simpanan) {
        super.onCreate(simpanan);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        akar = new FrameLayout(this);
        akar.setBackgroundColor(0xFF0A1826);
        setContentView(akar);

        kotak = new TextView(this);
        kotak.setTextColor(Color.WHITE);
        kotak.setBackgroundColor(0xE6000000);
        kotak.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        kotak.setLineSpacing(0, 1.25f);
        int tepi = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 18, getResources().getDisplayMetrics()));
        kotak.setPadding(tepi, tepi, tepi, tepi);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        akar.addView(kotak, lp);

        pasangWeb();
        tampilkanAlamat(true);
        h.postDelayed(segarkan, 2000);
        h.postDelayed(sembunyikan, TAMPIL_ALAMAT);
    }

    // ---------------------------------------------------------------- halaman TV

    private String alamatTv() {
        Peladen p = Aplikasi.peladen();
        return p == null ? null : "http://127.0.0.1:" + p.port() + "/tv";
    }

    private void pasangWeb() {
        if (web != null) {
            akar.removeView(web);
            web.destroy();
            web = null;
        }
        String alamat = alamatTv();
        if (alamat == null) return;                 // server gagal menyala; keterangannya tampil di kotak
        WebView w = new WebView(this);
        w.setBackgroundColor(0xFF0A1826);
        w.setFocusable(false);
        w.setFocusableInTouchMode(false);
        w.setVerticalScrollBarEnabled(false);
        w.setHorizontalScrollBarEnabled(false);
        w.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);      // video latar mulai sendiri
        s.setTextZoom(100);                                // ukuran huruf box tidak ikut mengubah tata letak
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        w.setWebViewClient(Build.VERSION.SDK_INT >= 26 ? new KlienBaru() : new WebViewClient());
        akar.addView(w, 0, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        web = w;
        w.loadUrl(alamat);
        terakhirMuat = System.currentTimeMillis();
    }

    /** Kalau mesin browser di box mati sendiri (kehabisan memori), halaman dibuat ulang; aplikasi tidak ikut tertutup. */
    private class KlienBaru extends WebViewClient {
        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    pasangWeb();
                }
            });
            return true;
        }
    }

    /** Penjaga: kalau halaman TV tidak pernah termuat atau berhenti meminta data, muat ulang. */
    private final Runnable jaga = new Runnable() {
        @Override
        public void run() {
            Peladen p = Aplikasi.peladen();
            if (tampak && p != null && System.currentTimeMillis() - terakhirMuat > TENGGANG) {
                int d = p.detikTv();
                if (d < 0 || d > BATAS_DIAM) pasangWeb();
            }
            if (tampak) h.postDelayed(this, JEDA_JAGA);
        }
    };

    // ---------------------------------------------------------------- alamat admin

    private static List<String> alamatJaringan() {
        List<String> hasil = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                        hasil.add(a.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return hasil;
    }

    private void tampilkanAlamat(boolean ya) {
        if (!ya) {
            kotak.setVisibility(View.GONE);
            return;
        }
        Peladen p = Aplikasi.peladen();
        StringBuilder b = new StringBuilder();
        if (p == null) {
            b.append("TV Tamu tidak bisa dimulai.\n").append(Aplikasi.galat()).append("\n\nTutup aplikasi lain yang memakai port 8080, lalu buka lagi.");
        } else {
            List<String> ip = alamatJaringan();
            b.append("Mengganti tulisan di TV ini\n");
            b.append("Buka di laptop atau HP yang satu jaringan dengan box:\n\n");
            if (ip.isEmpty()) {
                b.append("(box belum tersambung ke jaringan)\n");
            } else {
                for (String a : ip) b.append("   http://").append(a).append(':').append(p.port()).append("/admin\n");
                Mdns m = Aplikasi.mdns();
                if (m != null && m.punya()) {
                    b.append("atau\n   http://").append(m.namaLengkap()).append(':').append(p.port()).append("/admin\n");
                }
            }
            b.append("\nTombol OK di remote menampilkan lagi alamat ini.");
        }
        if (!b.toString().contentEquals(kotak.getText())) kotak.setText(b.toString());
        // TV yang dipasang berdiri: kotak ikut diputar seperti halaman TV, dan lebarnya dibatasi sisi pendek layar.
        int lebar = getResources().getDisplayMetrics().widthPixels, tinggi = getResources().getDisplayMetrics().heightPixels;
        int putar = p == null ? 0 : p.putar();
        boolean berdiri = putar == 90 || putar == 270;
        kotak.setMaxWidth(Math.round((berdiri ? Math.min(lebar, tinggi) : lebar) * 0.92f));
        kotak.setRotation(putar);
        kotak.setVisibility(View.VISIBLE);
        kotak.bringToFront();
    }

    /** Selama kotak alamat tampil, isinya diperbarui: jaringan dan nama box bisa baru siap beberapa detik kemudian. */
    private final Runnable segarkan = new Runnable() {
        @Override
        public void run() {
            if (kotak.getVisibility() == View.VISIBLE) {
                tampilkanAlamat(true);
                h.postDelayed(this, 2000);
            }
        }
    };

    private final Runnable sembunyikan = new Runnable() {
        @Override
        public void run() {
            if (Aplikasi.peladen() != null) tampilkanAlamat(false);
        }
    };

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER
                || k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_INFO) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                h.removeCallbacks(sembunyikan);
                boolean baru = kotak.getVisibility() != View.VISIBLE;
                tampilkanAlamat(baru);
                h.removeCallbacks(segarkan);
                if (baru) {
                    h.postDelayed(segarkan, 2000);
                    h.postDelayed(sembunyikan, TAMPIL_ALAMAT);
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // ---------------------------------------------------------------- siklus hidup

    @Override
    protected void onResume() {
        super.onResume();
        tampak = true;
        layarPenuh();
        if (web == null) pasangWeb();
        else web.onResume();
        h.removeCallbacks(jaga);
        h.postDelayed(jaga, JEDA_JAGA);
    }

    @Override
    protected void onPause() {
        tampak = false;
        h.removeCallbacks(jaga);
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (web != null) {
            akar.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean fokus) {
        super.onWindowFocusChanged(fokus);
        if (fokus) layarPenuh();
    }

    private void layarPenuh() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
}
