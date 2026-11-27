package net.coworklab.claudevoz;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Rotinas ensinadas: lista de passos (abrir / tocar) guardada por tarefa. */
public final class Rotinas {

    public static final String ABRIR = "abrir";
    public static final String TOCAR = "tocar";

    public static final class Passo {
        public String tipo = TOCAR;
        public String link = "";        // ABRIR: link ou nome do pacote
        public String pacote = "";      // TOCAR: app onde está o botão
        public String id = "", desc = "", texto = "", rotulo = "";
        public float x = -1, y = -1;
        public boolean vozGenerico = false;   // procurar "modo de voz" pelas descrições típicas
        public List<String> contexto = new ArrayList<>(); // textos que estavam no ecrã

        JSONObject json() throws Exception {
            JSONObject o = new JSONObject();
            o.put("tipo", tipo); o.put("link", link); o.put("pacote", pacote);
            o.put("id", id); o.put("desc", desc); o.put("texto", texto); o.put("rotulo", rotulo);
            o.put("x", x); o.put("y", y); o.put("vg", vozGenerico);
            JSONArray c = new JSONArray();
            for (String s : contexto) c.put(s);
            o.put("ctx", c);
            return o;
        }

        static Passo de(JSONObject o) {
            Passo p = new Passo();
            p.tipo = o.optString("tipo", TOCAR); p.link = o.optString("link");
            p.pacote = o.optString("pacote"); p.id = o.optString("id");
            p.desc = o.optString("desc"); p.texto = o.optString("texto");
            p.rotulo = o.optString("rotulo");
            p.x = (float) o.optDouble("x", -1); p.y = (float) o.optDouble("y", -1);
            p.vozGenerico = o.optBoolean("vg", false);
            JSONArray c = o.optJSONArray("ctx");
            if (c != null) for (int i = 0; i < c.length(); i++) p.contexto.add(c.optString(i));
            return p;
        }

        public String nome() {
            if (ABRIR.equals(tipo)) return "abrir " + link;
            if (!rotulo.isEmpty()) return rotulo;
            if (!desc.isEmpty()) return desc;
            if (!texto.isEmpty()) return texto;
            return vozGenerico ? "modo de voz" : "botão";
        }
    }

    private Rotinas() {}

    private static String chave(String link) { return "passos_" + link; }

    public static List<Passo> ler(Context c, String link) {
        List<Passo> l = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(c.getSharedPreferences("claudevoz", Context.MODE_PRIVATE)
                    .getString(chave(link), "[]"));
            for (int i = 0; i < a.length(); i++) l.add(Passo.de(a.getJSONObject(i)));
        } catch (Exception ignored) {}
        return l;
    }

    public static void guardar(Context c, String link, List<Passo> passos) {
        JSONArray a = new JSONArray();
        try { for (Passo p : passos) a.put(p.json()); } catch (Exception ignored) {}
        c.getSharedPreferences("claudevoz", Context.MODE_PRIVATE).edit()
                .putString(chave(link), a.toString()).apply();
    }

    public static void apagar(Context c, String link) {
        c.getSharedPreferences("claudevoz", Context.MODE_PRIVATE).edit().remove(chave(link)).apply();
    }

    /** Quantos toques ensinados (sem contar o "abrir"). */
    public static int quantos(Context c, String link) {
        int n = 0;
        for (Passo p : ler(c, link)) if (TOCAR.equals(p.tipo)) n++;
        return n;
    }

    public static Passo abrir(String link) {
        Passo p = new Passo();
        p.tipo = ABRIR;
        p.link = link;
        return p;
    }

    /** Passos a usar quando a tarefa ainda não foi ensinada: abrir + ligar o modo de voz. */
    public static List<Passo> padrao(Context c, Dados.Projecto pr, boolean ligarVoz) {
        List<Passo> l = new ArrayList<>();
        l.add(abrir(pr.link));
        if (!ligarVoz) return l;
        Passo t = new Passo();
        t.pacote = Dados.PACOTE_CLAUDE;
        if (Dados.botaoAprendido(c)) {
            t.id = Dados.botaoId(c); t.desc = Dados.botaoDesc(c); t.texto = Dados.botaoTexto(c);
            t.x = Dados.botaoX(c); t.y = Dados.botaoY(c);
        } else {
            t.vozGenerico = true;
        }
        t.rotulo = "modo de voz";
        if (pr.nome != null && !pr.nome.isEmpty()) t.contexto.add(pr.nome);
        l.add(t);
        return l;
    }

    /** O que a tarefa pretende, em palavras, para o cérebro com IA. */
    public static String objectivo(Dados.Projecto pr, List<Passo> passos) {
        StringBuilder sb = new StringBuilder("Na app Claude, abrir o projecto \"" + pr.nome + "\"");
        boolean temToques = false;
        for (Passo p : passos) {
            if (!TOCAR.equals(p.tipo)) continue;
            sb.append(temToques ? ", depois tocar em \"" : " e depois tocar em \"").append(p.nome()).append("\"");
            temToques = true;
        }
        if (!temToques) sb.append(".");
        else sb.append(". \"modo de voz\" é o botão com o ícone de onda sonora, junto à caixa de mensagem.");
        return sb.toString();
    }
}
