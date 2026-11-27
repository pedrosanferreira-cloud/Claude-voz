package net.coworklab.claudevoz;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Motor do Shaka (serviço de acessibilidade):
 *  - GRAVAR: o utilizador faz a tarefa uma vez; guarda cada toque (botão + o que estava no ecrã).
 *  - EXECUTAR: repete os passos; cada toque só acontece quando o ecrã certo aparece e está parado;
 *    confirma que o toque teve efeito; se um passo falhar, pergunta ao cérebro com IA (plano B).
 */
public class ToqueService extends AccessibilityService {

    private static ToqueService instancia;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean claudeEmPrimeiroPlano = false;

    public static ToqueService get() { return instancia; }
    public static boolean ativo() { return instancia != null; }

    @Override protected void onServiceConnected() { instancia = this; }
    @Override public boolean onUnbind(Intent intent) { instancia = null; return super.onUnbind(intent); }
    @Override public void onDestroy() { tirarBarra(); instancia = null; super.onDestroy(); }
    @Override public void onInterrupt() {}

    public boolean claudeVisivel() { return claudeEmPrimeiroPlano; }

    // ================================================================ tempos

    private static final long ESPERA_APOS_ABRIR = 2500;  // nunca tocar antes disto depois de abrir
    private static final long ESPERA_ENTRE = 600;        // entre toques seguidos
    private static final long ESTAVEL = 900;             // ecrã parado durante isto antes de tocar
    private static final long CONTEXTO_MAX = 9000;       // depois disto toca mesmo sem reconhecer o ecrã
    private static final long PEDIR_IA = 8000;           // depois disto, se não encontrou o botão, pergunta à IA
    private static final long DESISTIR_PASSO = 20000;
    private static final long CONFIRMAR = 1800;
    private static final long TOTAL_MAX = 60000;
    private static final int IA_MAX = 6;

    // ================================================================ abrir

    private void abrirLink(String link) {
        String l = link.trim();
        Intent i;
        if (!l.contains("://") && !l.contains("/") && l.contains(".")) {
            i = getPackageManager().getLaunchIntentForPackage(l);   // nome de pacote (ex.: com.google.android.gm)
            if (i == null) return;
        } else {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse(l));
            if (l.contains("claude.ai")) i.setPackage(Dados.PACOTE_CLAUDE);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Intent j = new Intent(Intent.ACTION_VIEW, Uri.parse(l));
            j.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(j); } catch (Exception ignored) {}
        }
    }

    // ================================================================ EXECUTAR

    private static final class Execucao {
        Dados.Projecto tarefa;
        List<Rotinas.Passo> passos;
        String objectivo;
        int i = 0;
        long inicio, inicioPasso, ultimaMudanca, novaJanelaEm, tocouEm, iaDepoisDe;
        boolean depoisDeAbrir, aPensar;
        int tentativas, iaUsos;
        AccessibilityNodeInfo tocado;
        final StringBuilder diario = new StringBuilder();
        final StringBuilder feito = new StringBuilder();
    }

    private Execucao ex;

    /** Corre a tarefa ensinada (ou a tarefa por omissão: abrir + ligar o modo de voz). */
    public void executar(Dados.Projecto pr, boolean ligarVoz) {
        parar(null);
        Execucao e = new Execucao();
        e.tarefa = pr;
        List<Rotinas.Passo> ensinados = Rotinas.ler(this, pr.link);
        e.passos = ensinados.isEmpty() ? Rotinas.padrao(this, pr, ligarVoz) : ensinados;
        e.objectivo = Rotinas.objectivo(pr, e.passos);
        e.inicio = System.currentTimeMillis();
        e.inicioPasso = e.inicio;
        e.ultimaMudanca = e.inicio;
        ex = e;
        nota(ensinados.isEmpty() ? "tarefa por omissão (" + e.passos.size() + " passos)"
                : "tarefa ensinada (" + e.passos.size() + " passos)");
        handler.post(this::tick);
    }

    /** Compatibilidade: abrir um projecto e (opcionalmente) ligar o modo de voz. */
    public void abrirProjecto(String link, boolean tocarVoz) {
        executar(new Dados.Projecto("", link, ""), tocarVoz);
    }

    private void tick() {
        Execucao e = ex;
        if (e == null) return;
        handler.postDelayed(this::tick, 300);
        long agora = System.currentTimeMillis();
        if (agora - e.inicio > TOTAL_MAX) { parar("Demorou demasiado. Parei."); return; }
        if (e.aPensar) return;
        if (e.i >= e.passos.size()) { concluir(); return; }

        Rotinas.Passo p = e.passos.get(e.i);

        if (Rotinas.ABRIR.equals(p.tipo)) {
            abrirLink(p.link);
            nota("abriu " + p.link);
            avancar(true);
            return;
        }

        AccessibilityNodeInfo raiz = raizDe(p.pacote);

        // ---- já tocou: confirmar que teve efeito
        if (e.tocouEm != 0) {
            if (agora - e.tocouEm < CONFIRMAR) return;
            boolean nadaMudou = e.ultimaMudanca <= e.tocouEm && e.novaJanelaEm <= e.tocouEm;
            if (nadaMudou && e.tentativas < 2) {
                nota("o ecrã não reagiu; toca outra vez");
                e.tocouEm = 0;
                e.ultimaMudanca = 0;   // não esperar estabilidade para repetir
            } else {
                e.feito.append(e.feito.length() == 0 ? "" : ", ").append(p.nome());
                avancar(false);
            }
            return;
        }

        long noPasso = agora - e.inicioPasso;
        if (noPasso < (e.depoisDeAbrir ? ESPERA_APOS_ABRIR : ESPERA_ENTRE)) return;
        if (raiz == null) {
            if (noPasso > DESISTIR_PASSO) parar("Não consegui ver a app " + p.pacote + ".");
            return;
        }
        if (agora - e.ultimaMudanca < ESTAVEL && noPasso < CONTEXTO_MAX) return;  // ecrã ainda a mexer

        AccessibilityNodeInfo alvo = encontrar(raiz, p);
        int ctx = contexto(raiz, p);
        boolean ecraCerto = ctx >= Math.min(2, p.contexto.size());
        boolean semIdentificacao = p.id.isEmpty() && p.desc.isEmpty() && p.texto.isEmpty()
                && p.rotulo.isEmpty() && !p.vozGenerico;

        if (alvo != null && (ecraCerto || noPasso > CONTEXTO_MAX)) {
            if (perigoso(rotulo(alvo))) {
                parar("O passo \"" + rotulo(alvo) + "\" precisa da sua confirmação. Por segurança, parei.");
                return;
            }
            if (clicar(alvo)) {
                e.tentativas++;
                e.tocouEm = agora;
                e.tocado = alvo;
                nota("tocou em \"" + rotulo(alvo) + "\" (ecrã " + (ecraCerto ? "reconhecido" : "não reconhecido")
                        + ", tentativa " + e.tentativas + ")");
            }
            return;
        }

        // ---- plano B: cérebro com IA
        boolean podeIA = Cerebro.ligado(this) && e.iaUsos < IA_MAX && agora > e.iaDepoisDe;
        if (podeIA && (semIdentificacao || noPasso > PEDIR_IA)) {
            perguntarIA(raiz, p);
            return;
        }

        // ---- último recurso: a posição onde tocou quando ensinou
        if (noPasso > DESISTIR_PASSO - 4000 && p.x > 0 && p.y > 0 && e.tentativas == 0 && ecraCerto) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            tocarEm(p.x * dm.widthPixels, p.y * dm.heightPixels);
            e.tentativas = 2;
            e.tocouEm = agora;
            nota("não encontrou \"" + p.nome() + "\"; tocou na posição guardada");
            return;
        }

        if (noPasso > DESISTIR_PASSO) {
            nota("ecrã: " + listaTextos(raiz, 12));
            parar("Não encontrei o botão " + p.nome() + ".");
        }
    }

    private void avancar(boolean foiAbrir) {
        Execucao e = ex;
        if (e == null) return;
        e.i++;
        e.inicioPasso = System.currentTimeMillis();
        e.depoisDeAbrir = foiAbrir;
        e.tocouEm = 0;
        e.tentativas = 0;
        e.tocado = null;
    }

    private void concluir() {
        nota("tarefa concluída");
        gravarDiario();
        ex = null;
    }

    /** Pára a execução; se houver mensagem, di-la por voz. */
    private void parar(String msg) {
        if (ex == null) return;
        if (msg != null) {
            nota("parou: " + msg);
            VozService.dizer(this, msg);
        }
        gravarDiario();
        ex = null;
    }

    private void perguntarIA(AccessibilityNodeInfo raiz, Rotinas.Passo p) {
        Execucao e = ex;
        e.aPensar = true;
        e.iaUsos++;
        List<AccessibilityNodeInfo> nos = new ArrayList<>();
        String ecra = descreverEcra(raiz, nos);
        String passo = p.vozGenerico ? "ligar o modo de voz" : "tocar em \"" + p.nome() + "\"";
        nota("pergunta à IA (" + e.iaUsos + "): " + passo);
        String hist = e.feito.toString();
        new Thread(() -> {
            Cerebro.Decisao d = Cerebro.decidir(this, e.objectivo, passo, ecra, hist);
            handler.post(() -> {
                if (ex != e) return;
                e.aPensar = false;
                long agora = System.currentTimeMillis();
                switch (d.accao) {
                    case "tocar":
                        if (d.n < 0 || d.n >= nos.size()) {
                            nota("IA escolheu um número inválido");
                            e.iaDepoisDe = agora + 1500;
                            return;
                        }
                        AccessibilityNodeInfo n = nos.get(d.n);
                        n.refresh();
                        String r = rotulo(n);
                        if (perigoso(r)) {
                            parar("A IA quis tocar em " + r + ". Isso precisa da sua confirmação. Parei.");
                            return;
                        }
                        if (clicar(n)) {
                            e.tentativas++;
                            e.tocouEm = agora;
                            e.tocado = n;
                            nota("IA tocou em \"" + r + "\" (" + d.motivo + ")");
                        }
                        return;
                    case "esperar":
                        nota("IA: esperar (" + d.motivo + ")");
                        e.iaDepoisDe = agora + 2500;
                        return;
                    case "feito":
                        nota("IA: já está feito (" + d.motivo + ")");
                        avancar(false);
                        return;
                    default:
                        parar("Não consegui. " + d.motivo);
                }
            });
        }).start();
    }

    private static boolean perigoso(String rotulo) {
        String r = " " + normal(rotulo) + " ";
        String[] palavras = {"enviar", "send", "apagar", "eliminar", "delete", "remover", "remove",
                "pagar", "pay", "comprar", "buy", "publicar", "post", "publish", "aceitar", "accept",
                "confirmar", "confirm", "transferir", "transfer"};
        for (String w : palavras) if (r.contains(" " + w + " ") || r.startsWith(" " + w)) return true;
        return false;
    }

    // ================================================================ GRAVAR (ensinar)

    private boolean gravando = false;
    private Dados.Projecto gravarTarefa;
    private final List<Rotinas.Passo> gravados = new ArrayList<>();
    private List<String> ultimoEcra = new ArrayList<>();
    private long ultimoEcraEm = 0;
    private View barra;
    private TextView barraTexto;

    public boolean aGravar() { return gravando; }

    /** Abre a tarefa e grava os toques do utilizador até carregar em "Terminar". */
    public void comecarGravar(Dados.Projecto pr) {
        parar(null);
        gravando = true;
        gravarTarefa = pr;
        gravados.clear();
        gravados.add(Rotinas.abrir(pr.link));
        mostrarBarra();
        abrirLink(pr.link);
    }

    private void terminarGravar(boolean guardar) {
        if (!gravando) return;
        gravando = false;
        tirarBarra();
        int n = gravados.size() - 1;
        String msg;
        if (guardar && n > 0) {
            Rotinas.guardar(this, gravarTarefa.link, new ArrayList<>(gravados));
            msg = "Tarefa \"" + gravarTarefa.nome + "\" ensinada: " + n + (n == 1 ? " toque." : " toques.");
        } else if (guardar) {
            msg = "Não gravei nenhum toque. Nada mudou.";
        } else {
            msg = "Ensino cancelado.";
        }
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);
        handler.postDelayed(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show(), 400);
    }

    private void gravarToque(AccessibilityNodeInfo n, String pacote) {
        Rotinas.Passo p = new Rotinas.Passo();
        p.pacote = pacote;
        p.id = n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName();
        p.desc = n.getContentDescription() == null ? "" : n.getContentDescription().toString().trim();
        p.texto = n.getText() == null ? "" : n.getText().toString().trim();
        if (p.texto.length() > 60) p.texto = "";
        p.rotulo = rotulo(n);
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        p.x = r.exactCenterX() / dm.widthPixels;
        p.y = r.exactCenterY() / dm.heightPixels;
        String meu = normal(p.rotulo);
        for (String t : ultimoEcra) {
            if (p.contexto.size() >= 10) break;
            if (!t.equals(meu) && t.length() >= 2) p.contexto.add(t);
        }
        gravados.add(p);
        if (barraTexto != null) {
            int q = gravados.size() - 1;
            barraTexto.setText("● A gravar — " + q + (q == 1 ? " toque" : " toques") + ": " + p.rotulo);
        }
    }

    private void mostrarBarra() {
        tirarBarra();
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            LinearLayout l = new LinearLayout(this);
            l.setOrientation(LinearLayout.HORIZONTAL);
            l.setGravity(Gravity.CENTER_VERTICAL);
            l.setBackgroundColor(Color.argb(230, 180, 20, 20));
            int pd = (int) (8 * getResources().getDisplayMetrics().density);
            l.setPadding(pd, pd / 2, pd, pd / 2);
            barraTexto = new TextView(this);
            barraTexto.setTextColor(Color.WHITE);
            barraTexto.setText("● A gravar — faça a tarefa");
            barraTexto.setSingleLine(true);
            l.addView(barraTexto, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            Button fim = new Button(this);
            fim.setText("Terminar");
            fim.setAllCaps(false);
            fim.setOnClickListener(v -> terminarGravar(true));
            l.addView(fim);
            Button canc = new Button(this);
            canc.setText("✕");
            canc.setOnClickListener(v -> terminarGravar(false));
            l.addView(canc);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM;
            lp.y = (int) (90 * getResources().getDisplayMetrics().density);
            wm.addView(l, lp);
            barra = l;
        } catch (Exception e) {
            Toast.makeText(this, "Não consegui mostrar a barra de gravação.", Toast.LENGTH_LONG).show();
        }
    }

    private void tirarBarra() {
        if (barra != null) {
            try { ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(barra); } catch (Exception ignored) {}
            barra = null;
            barraTexto = null;
        }
    }

    // ================================================================ eventos

    @Override
    public void onAccessibilityEvent(AccessibilityEvent ev) {
        CharSequence cs = ev.getPackageName();
        if (cs == null) return;
        String pkg = cs.toString();
        boolean meu = pkg.equals(getPackageName());
        boolean sistema = pkg.startsWith("com.android.systemui");
        int tipo = ev.getEventType();

        if (tipo == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            boolean antes = claudeEmPrimeiroPlano;
            if (Dados.PACOTE_CLAUDE.equals(pkg)) claudeEmPrimeiroPlano = true;
            else if (!meu && !sistema) claudeEmPrimeiroPlano = false;
            if (antes && !claudeEmPrimeiroPlano && !gravando && ex == null) VozService.rearmar(this);
        }
        if (meu || sistema) return;

        if (gravando) {
            if (tipo == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                AccessibilityNodeInfo n = ev.getSource();
                if (n != null) gravarToque(n, pkg);
            } else {
                long agora = System.currentTimeMillis();
                if (agora - ultimoEcraEm > 250) {
                    AccessibilityNodeInfo r = getRootInActiveWindow();
                    if (r != null && r.getPackageName() != null && pkg.contentEquals(r.getPackageName())) {
                        ultimoEcra = textosDoEcra(r);
                        ultimoEcraEm = agora;
                    }
                }
            }
            return;
        }

        Execucao e = ex;
        if (e != null && (tipo == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || tipo == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) {
            long agora = System.currentTimeMillis();
            e.ultimaMudanca = agora;
            if (tipo == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) e.novaJanelaEm = agora;
        }
    }

    // ================================================================ ler o ecrã

    private AccessibilityNodeInfo raizDe(String pacote) {
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null && r.getPackageName() != null
                        && (pacote == null || pacote.isEmpty() || pacote.contentEquals(r.getPackageName()))) {
                    return r;
                }
            }
        } catch (Exception ignored) {}
        AccessibilityNodeInfo r = getRootInActiveWindow();
        if (r == null || r.getPackageName() == null) return null;
        if (pacote != null && !pacote.isEmpty() && !pacote.contentEquals(r.getPackageName())) return null;
        return r;
    }

    private AccessibilityNodeInfo encontrar(AccessibilityNodeInfo raiz, Rotinas.Passo p) {
        if (!p.id.isEmpty()) {
            List<AccessibilityNodeInfo> l = raiz.findAccessibilityNodeInfosByViewId(p.id);
            if (l != null) {
                AccessibilityNodeInfo primeiro = null;
                for (AccessibilityNodeInfo n : l) {
                    if (!n.isVisibleToUser()) continue;
                    if (primeiro == null) primeiro = n;
                    if (bate(n, p)) return n;
                }
                if (primeiro != null && p.desc.isEmpty() && p.texto.isEmpty()) return primeiro;
            }
        }
        if (!p.desc.isEmpty() || !p.texto.isEmpty() || !p.rotulo.isEmpty()) {
            AccessibilityNodeInfo n = procurar(raiz, p, 0);
            if (n != null) return n;
        }
        if (p.vozGenerico) return procurarVoz(raiz, 0);
        return null;
    }

    private boolean bate(AccessibilityNodeInfo n, Rotinas.Passo p) {
        String d = n.getContentDescription() == null ? "" : n.getContentDescription().toString().trim();
        String t = n.getText() == null ? "" : n.getText().toString().trim();
        if (!p.desc.isEmpty() && p.desc.equals(d)) return true;
        if (!p.texto.isEmpty() && p.texto.equals(t)) return true;
        return p.desc.isEmpty() && p.texto.isEmpty() && !p.rotulo.isEmpty() && p.rotulo.equals(rotulo(n));
    }

    private AccessibilityNodeInfo procurar(AccessibilityNodeInfo n, Rotinas.Passo p, int prof) {
        if (n == null || prof > 45) return null;
        if (n.isVisibleToUser() && bate(n, p)) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = procurar(n.getChild(i), p, prof + 1);
            if (r != null) return r;
        }
        return null;
    }

    private AccessibilityNodeInfo procurarVoz(AccessibilityNodeInfo n, int prof) {
        if (n == null || prof > 45) return null;
        String d = normal(n.getContentDescription() == null ? "" : n.getContentDescription().toString());
        boolean ok = d.contains("voice mode") || d.contains("modo de voz") || d.contains("start voice")
                || d.contains("iniciar voz") || d.equals("voice") || d.equals("voz");
        if (ok && n.isVisibleToUser()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = procurarVoz(n.getChild(i), prof + 1);
            if (r != null) return r;
        }
        return null;
    }

    /** Quantos dos textos guardados no ensino estão agora no ecrã. */
    private int contexto(AccessibilityNodeInfo raiz, Rotinas.Passo p) {
        if (p.contexto.isEmpty()) return 0;
        List<String> agora = textosDoEcra(raiz);
        int n = 0;
        for (String c : p.contexto) {
            String cn = normal(c);
            for (String a : agora) if (a.equals(cn) || (cn.length() >= 4 && a.contains(cn))) { n++; break; }
        }
        return n;
    }

    private List<String> textosDoEcra(AccessibilityNodeInfo raiz) {
        List<String> l = new ArrayList<>();
        juntarTextos(raiz, l, 0);
        return l;
    }

    private void juntarTextos(AccessibilityNodeInfo n, List<String> l, int prof) {
        if (n == null || prof > 45 || l.size() >= 40) return;
        if (n.isVisibleToUser()) {
            for (CharSequence c : new CharSequence[]{n.getText(), n.getContentDescription()}) {
                if (c == null) continue;
                String t = normal(c.toString());
                if (t.length() >= 2 && t.length() <= 50 && !l.contains(t)) l.add(t);
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) juntarTextos(n.getChild(i), l, prof + 1);
    }

    private String listaTextos(AccessibilityNodeInfo raiz, int max) {
        List<String> l = textosDoEcra(raiz);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size() && i < max; i++) sb.append(i == 0 ? "" : " | ").append(l.get(i));
        return sb.toString();
    }

    /** Lista numerada dos elementos do ecrã, para a IA. */
    private String descreverEcra(AccessibilityNodeInfo raiz, List<AccessibilityNodeInfo> nos) {
        StringBuilder sb = new StringBuilder();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        descrever(raiz, nos, sb, dm, 0);
        return sb.toString();
    }

    private void descrever(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> nos, StringBuilder sb,
                           DisplayMetrics dm, int prof) {
        if (n == null || prof > 45 || nos.size() >= 80) return;
        if (n.isVisibleToUser()) {
            String d = n.getContentDescription() == null ? "" : n.getContentDescription().toString().trim();
            String t = n.getText() == null ? "" : n.getText().toString().trim();
            String id = n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName();
            if (n.isClickable() || !d.isEmpty() || (!t.isEmpty() && n.isClickable())) {
                if (!d.isEmpty() || !t.isEmpty() || !id.isEmpty()) {
                    Rect r = new Rect();
                    n.getBoundsInScreen(r);
                    String zona = (r.exactCenterY() > dm.heightPixels * 0.66f ? "baixo" :
                            r.exactCenterY() < dm.heightPixels * 0.33f ? "cima" : "meio")
                            + "-" + (r.exactCenterX() > dm.widthPixels * 0.66f ? "direita" :
                            r.exactCenterX() < dm.widthPixels * 0.33f ? "esquerda" : "centro");
                    sb.append(nos.size()).append(": ");
                    if (!d.isEmpty()) sb.append("desc=\"").append(corta(d)).append("\" ");
                    if (!t.isEmpty()) sb.append("texto=\"").append(corta(t)).append("\" ");
                    if (!id.isEmpty()) sb.append("id=").append(id.substring(id.indexOf('/') + 1)).append(' ');
                    sb.append(n.isClickable() ? "[clicável] " : "").append(zona).append('\n');
                    nos.add(n);
                }
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) descrever(n.getChild(i), nos, sb, dm, prof + 1);
    }

    private static String corta(String s) {
        s = s.replace('\n', ' ').replace('"', '\'');
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    private String rotulo(AccessibilityNodeInfo n) {
        CharSequence d = n.getContentDescription(), t = n.getText();
        if (d != null && d.toString().trim().length() > 0) return d.toString().trim();
        if (t != null && t.toString().trim().length() > 0) return corta(t.toString().trim());
        String filho = textoFilho(n, 0);
        if (!filho.isEmpty()) return corta(filho);
        String id = n.getViewIdResourceName();
        return id == null ? "" : id.substring(id.indexOf('/') + 1);
    }

    private String textoFilho(AccessibilityNodeInfo n, int prof) {
        if (n == null || prof > 4) return "";
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c == null) continue;
            CharSequence s = c.getText() != null ? c.getText() : c.getContentDescription();
            if (s != null && s.toString().trim().length() > 0) return s.toString().trim();
            String r = textoFilho(c, prof + 1);
            if (!r.isEmpty()) return r;
        }
        return "";
    }

    private static String normal(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    // ================================================================ tocar

    private boolean clicar(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = n;
        int saltos = 0;
        while (c != null && !c.isClickable() && saltos < 5) { c = c.getParent(); saltos++; }
        if (c != null && c.isClickable() && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        return r.width() > 0 && r.height() > 0 && tocarEm(r.exactCenterX(), r.exactCenterY());
    }

    private boolean tocarEm(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build();
        return dispatchGesture(g, null, null);
    }

    // ================================================================ diagnóstico

    private void nota(String t) {
        Execucao e = ex;
        if (e == null) return;
        long s = (System.currentTimeMillis() - e.inicio) / 100;
        e.diario.append(e.diario.length() == 0 ? "" : "\n").append(s / 10).append(',').append(s % 10)
                .append("s: ").append(t);
    }

    private void gravarDiario() {
        Execucao e = ex;
        if (e == null) return;
        getSharedPreferences("claudevoz", MODE_PRIVATE).edit()
                .putString("ultimo_toque", e.diario.toString()).apply();
    }
}
