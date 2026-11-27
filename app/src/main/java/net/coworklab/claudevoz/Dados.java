package net.coworklab.claudevoz;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Guarda a lista de projectos e o botão de voz "aprendido". */
public final class Dados {

    public static final String PACOTE_CLAUDE = "com.anthropic.claude";

    public static final class Projecto {
        public String nome;
        public String link;
        public String alternativas; // outros nomes, separados por vírgula

        public Projecto(String nome, String link, String alternativas) {
            this.nome = nome;
            this.link = link;
            this.alternativas = alternativas == null ? "" : alternativas;
        }
    }

    private Dados() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("claudevoz", Context.MODE_PRIVATE);
    }

    public static List<Projecto> lerProjectos(Context c) {
        List<Projecto> lista = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(c).getString("projectos", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                lista.add(new Projecto(o.optString("nome"), o.optString("link"), o.optString("alt")));
            }
        } catch (Exception ignored) {}
        return lista;
    }

    public static void guardarProjectos(Context c, List<Projecto> lista) {
        JSONArray arr = new JSONArray();
        try {
            for (Projecto p : lista) {
                JSONObject o = new JSONObject();
                o.put("nome", p.nome);
                o.put("link", p.link);
                o.put("alt", p.alternativas);
                arr.put(o);
            }
        } catch (Exception ignored) {}
        prefs(c).edit().putString("projectos", arr.toString()).apply();
    }

    // ---- botão de voz aprendido ----

    public static void guardarBotao(Context c, String id, String descricao, String texto, float xRel, float yRel) {
        prefs(c).edit()
                .putString("btn_id", id == null ? "" : id)
                .putString("btn_desc", descricao == null ? "" : descricao)
                .putString("btn_texto", texto == null ? "" : texto)
                .putFloat("btn_x", xRel)
                .putFloat("btn_y", yRel)
                .putBoolean("btn_ok", true)
                .apply();
    }

    public static boolean botaoAprendido(Context c) { return prefs(c).getBoolean("btn_ok", false); }
    public static String botaoId(Context c) { return prefs(c).getString("btn_id", ""); }
    public static String botaoDesc(Context c) { return prefs(c).getString("btn_desc", ""); }
    public static String botaoTexto(Context c) { return prefs(c).getString("btn_texto", ""); }
    public static float botaoX(Context c) { return prefs(c).getFloat("btn_x", -1f); }
    public static float botaoY(Context c) { return prefs(c).getFloat("btn_y", -1f); }

    public static int teclaGatilho(Context c) {
        return prefs(c).getInt("tecla", android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }
    public static void setTeclaGatilho(Context c, int k) { prefs(c).edit().putInt("tecla", k).apply(); }

    public static String nomeTecla(int k) {
        switch (k) {
            case android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS: return "faixa anterior";
            case android.view.KeyEvent.KEYCODE_MEDIA_NEXT: return "faixa seguinte";
            case android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case android.view.KeyEvent.KEYCODE_HEADSETHOOK: return "play/pausa";
            case android.view.KeyEvent.KEYCODE_MEDIA_PLAY: return "play";
            case android.view.KeyEvent.KEYCODE_MEDIA_PAUSE: return "pausa";
            case android.view.KeyEvent.KEYCODE_MEDIA_REWIND: return "recuar";
            case android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: return "avançar";
            case android.view.KeyEvent.KEYCODE_MEDIA_STOP: return "parar";
            default: return "sinal " + k;
        }
    }

    public static boolean ativarVozAuto(Context c) { return prefs(c).getBoolean("auto_voz", true); }
    public static void setAtivarVozAuto(Context c, boolean v) { prefs(c).edit().putBoolean("auto_voz", v).apply(); }

    // ---- correspondência entre o que foi dito e os projectos ----

    public static String normalizar(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        // palavras de comando que não fazem parte do nome
        n = (" " + n + " ")
                .replaceAll(" (abrir|abre|abra|abri|entrar|entra|no|na|em|o|a|ao|projecto|projeto|projectos|projetos|claude|por favor|quero|falar|com) ", " ");
        // repetir porque as substituições podem sobrepor-se
        n = (" " + n.trim() + " ")
                .replaceAll(" (abrir|abre|abra|no|na|em|o|a|ao|projecto|projeto|com) ", " ");
        return n.replaceAll("\\s+", " ").trim();
    }

    /** Devolve o projecto mais parecido com o que foi dito, ou null. */
    public static Projecto encontrar(List<Projecto> lista, List<String> hipoteses) {
        Projecto melhor = null;
        double melhorNota = 0;
        for (String h : hipoteses) {
            String dito = normalizar(h);
            if (dito.isEmpty()) continue;
            for (Projecto p : lista) {
                List<String> nomes = new ArrayList<>();
                nomes.add(p.nome);
                for (String a : p.alternativas.split(",")) if (!a.trim().isEmpty()) nomes.add(a);
                for (String nome : nomes) {
                    String alvo = normalizar(nome);
                    if (alvo.isEmpty()) continue;
                    double nota;
                    if (dito.equals(alvo)) nota = 1.0;
                    else if (dito.contains(alvo) || alvo.contains(dito)) nota = 0.9;
                    else nota = semelhanca(dito.replace(" ", ""), alvo.replace(" ", ""));
                    if (nota > melhorNota) {
                        melhorNota = nota;
                        melhor = p;
                    }
                }
            }
        }
        return melhorNota >= 0.6 ? melhor : null;
    }

    private static double semelhanca(String a, String b) {
        int max = Math.max(a.length(), b.length());
        if (max == 0) return 0;
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int custo = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + custo);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return 1.0 - (double) prev[b.length()] / max;
    }
}
