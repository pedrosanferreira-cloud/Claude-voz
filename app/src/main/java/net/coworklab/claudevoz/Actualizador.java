package net.coworklab.claudevoz;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Procura versões novas no GitHub e instala-as. */
public final class Actualizador {

    private static final String API =
            "https://api.github.com/repos/pedrosanferreira-cloud/claude-voz/releases/latest";
    public static final String ACAO_RESULTADO = "net.coworklab.claudevoz.INSTALACAO";

    public interface Resposta {
        /** versaoNova == 0 quando não há nada novo; erro != null se falhou. */
        void pronto(int versaoNova, String link, String erro);
    }

    private Actualizador() {}

    public static int versaoInstalada(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    public static void procurar(Context c, Resposta r) {
        Handler main = new Handler(Looper.getMainLooper());
        int atual = versaoInstalada(c);
        new Thread(() -> {
            try {
                JSONObject rel = new JSONObject(new String(ler(API), "UTF-8"));
                String tag = rel.optString("tag_name", "");          // ex.: v1.0.7
                int n = Integer.parseInt(tag.substring(tag.lastIndexOf('.') + 1));
                String link = null;
                JSONArray assets = rel.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (a.optString("name").endsWith(".apk")) {
                            link = a.optString("browser_download_url");
                            break;
                        }
                    }
                }
                final String lk = link;
                main.post(() -> r.pronto(n > atual && lk != null ? n : 0, lk, null));
            } catch (Exception e) {
                main.post(() -> r.pronto(0, null, e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }).start();
    }

    /** Pode instalar apps? Se não, abre a definição certa e devolve false. */
    public static boolean temPermissao(Activity a) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + a.getPackageName())));
            return false;
        }
        return true;
    }

    public static void descarregarEInstalar(Activity a, String link, Resposta r) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                byte[] apk = ler(link);
                PackageInstaller pi = a.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams p =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                p.setAppPackageName(a.getPackageName());
                int id = pi.createSession(p);
                PackageInstaller.Session s = pi.openSession(id);
                try (OutputStream out = s.openWrite("ClaudeVoz.apk", 0, apk.length)) {
                    out.write(apk);
                    s.fsync(out);
                }
                Intent i = new Intent(a, MainActivity.class).setAction(ACAO_RESULTADO);
                int fl = PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent pend = PendingIntent.getActivity(a, 99, i, fl);
                s.commit(pend.getIntentSender());
                s.close();
                main.post(() -> r.pronto(1, link, null));
            } catch (Exception e) {
                main.post(() -> r.pronto(0, null, e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }).start();
    }

    /** Trata a resposta do instalador (chamado pela MainActivity). */
    public static String tratarResultado(Activity a, Intent i) {
        int estado = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        if (estado == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmar = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmar != null) {
                confirmar.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                a.startActivity(confirmar);
            }
            return null;
        }
        if (estado == PackageInstaller.STATUS_SUCCESS) return "Actualizada!";
        String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        return "A instalação não terminou" + (msg == null ? "." : ": " + msg);
    }

    private static byte[] ler(String endereco) throws Exception {
        String atual = endereco;
        for (int saltos = 0; saltos < 5; saltos++) {
            HttpURLConnection c = (HttpURLConnection) new URL(atual).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", "ClaudeVoz");
            if (atual.contains("api.github.com")) c.setRequestProperty("Accept", "application/vnd.github+json");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                atual = c.getHeaderField("Location");
                c.disconnect();
                continue;
            }
            if (code != 200) throw new Exception("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                return bo.toByteArray();
            } finally {
                c.disconnect();
            }
        }
        throw new Exception("demasiados redireccionamentos");
    }
}
