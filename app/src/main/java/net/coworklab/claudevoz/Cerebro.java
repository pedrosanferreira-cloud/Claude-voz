package net.coworklab.claudevoz;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Plano B: quando um passo ensinado falha, pergunta ao Claude (API da Anthropic)
 * em que elemento do ecrã tocar. Recebe só a lista de botões/textos do ecrã.
 */
public final class Cerebro {

    public static final String MODELO = "claude-haiku-5-5";

    public static final class Decisao {
        public String accao = "falhou";   // tocar | esperar | feito | falhou
        public int n = -1;
        public String motivo = "";
    }

    private Cerebro() {}

    public static String chave(Context c) {
        return c.getSharedPreferences("claudevoz", Context.MODE_PRIVATE).getString("api_chave", "");
    }

    public static void setChave(Context c, String k) {
        c.getSharedPreferences("claudevoz", Context.MODE_PRIVATE).edit()
                .putString("api_chave", k == null ? "" : k.trim()).apply();
    }

    public static boolean ligado(Context c) {
        return !chave(c).isEmpty()
                && c.getSharedPreferences("claudevoz", Context.MODE_PRIVATE).getBoolean("ia_on", true);
    }

    public static void setLigado(Context c, boolean v) {
        c.getSharedPreferences("claudevoz", Context.MODE_PRIVATE).edit().putBoolean("ia_on", v).apply();
    }

    private static final String SISTEMA =
            "És o Shaka, um assistente que controla um telemóvel Android tocando em elementos do ecrã. "
            + "Recebes o objectivo da tarefa, o passo que falhou e a lista numerada dos elementos visíveis. "
            + "Escolhe UMA acção. Responde só com JSON, sem mais texto: "
            + "{\"accao\":\"tocar\",\"n\":<número>,\"motivo\":\"...\"} para tocar num elemento; "
            + "{\"accao\":\"esperar\",\"motivo\":\"...\"} se o ecrã ainda está a carregar ou não é o ecrã certo; "
            + "{\"accao\":\"feito\",\"motivo\":\"...\"} se o objectivo já está cumprido; "
            + "{\"accao\":\"falhou\",\"motivo\":\"...\"} se não é possível. "
            + "Nunca toques em nada que envie, apague, pague, compre, publique, aceite termos ou confirme algo: "
            + "nesse caso responde falhou. Nunca escrevas texto. O motivo é curto, em português de Portugal.";

    /** Chamada bloqueante: correr fora da thread principal. */
    public static Decisao decidir(Context c, String objectivo, String passo, String ecra, String historico) {
        Decisao d = new Decisao();
        HttpURLConnection con = null;
        try {
            JSONObject corpo = new JSONObject();
            corpo.put("model", MODELO);
            corpo.put("max_tokens", 200);
            corpo.put("system", SISTEMA);
            JSONArray msgs = new JSONArray();
            JSONObject m = new JSONObject();
            m.put("role", "user");
            m.put("content", "Objectivo: " + objectivo
                    + "\nPasso que falhou: " + passo
                    + (historico.isEmpty() ? "" : "\nJá feito: " + historico)
                    + "\nElementos do ecrã:\n" + ecra);
            msgs.put(m);
            corpo.put("messages", msgs);

            con = (HttpURLConnection) new URL("https://api.anthropic.com/v1/messages").openConnection();
            con.setRequestMethod("POST");
            con.setConnectTimeout(10000);
            con.setReadTimeout(20000);
            con.setDoOutput(true);
            con.setRequestProperty("content-type", "application/json");
            con.setRequestProperty("x-api-key", chave(c));
            con.setRequestProperty("anthropic-version", "2023-06-01");
            try (OutputStream os = con.getOutputStream()) {
                os.write(corpo.toString().getBytes(StandardCharsets.UTF_8));
            }
            int cod = con.getResponseCode();
            String resp = ler(cod >= 400 ? con.getErrorStream() : con.getInputStream());
            if (cod >= 400) {
                String msg = resp;
                try { msg = new JSONObject(resp).getJSONObject("error").optString("message", resp); }
                catch (Exception ignored) {}
                d.motivo = "erro da API " + cod + ": " + curto(msg);
                return d;
            }
            JSONArray content = new JSONObject(resp).getJSONArray("content");
            StringBuilder txt = new StringBuilder();
            for (int i = 0; i < content.length(); i++) txt.append(content.getJSONObject(i).optString("text"));
            String t = txt.toString();
            int a = t.indexOf('{'), b = t.lastIndexOf('}');
            if (a < 0 || b <= a) { d.motivo = "resposta inesperada: " + curto(t); return d; }
            JSONObject o = new JSONObject(t.substring(a, b + 1));
            d.accao = o.optString("accao", "falhou");
            d.n = o.optInt("n", -1);
            d.motivo = o.optString("motivo", "");
        } catch (Exception e) {
            d.accao = "falhou";
            d.motivo = "sem ligação à IA (" + e.getClass().getSimpleName() + ")";
        } finally {
            if (con != null) con.disconnect();
        }
        return d;
    }

    private static String ler(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toString("UTF-8");
    }

    private static String curto(String s) {
        return s == null ? "" : (s.length() > 120 ? s.substring(0, 120) + "…" : s);
    }
}
