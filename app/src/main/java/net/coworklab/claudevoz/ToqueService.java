package net.coworklab.claudevoz;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.Locale;

/**
 * Serviço de acessibilidade: abre o link do projecto e toca no botão do modo de voz
 * dentro da app Claude. Também "aprende" qual é esse botão.
 */
public class ToqueService extends AccessibilityService {

    private static ToqueService instancia;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long tocarAte = 0;          // enquanto > agora, procura o botão de voz
    private boolean aprender = false;
    private boolean claudeEmPrimeiroPlano = false;

    public static ToqueService get() { return instancia; }
    public static boolean ativo() { return instancia != null; }

    @Override
    protected void onServiceConnected() {
        instancia = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instancia = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instancia = null;
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {}

    // ---------------------------------------------------------------- acções

    /** Abre o projecto na app Claude e, se pedido, toca no botão de voz. */
    public void abrirProjecto(String link, boolean tocarVoz) {
        Uri uri = Uri.parse(link.trim());
        Intent i = new Intent(Intent.ACTION_VIEW, uri);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        i.setPackage(Dados.PACOTE_CLAUDE);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Intent j = new Intent(Intent.ACTION_VIEW, uri);
            j.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(j);
        }
        if (tocarVoz) {
            tocarAte = System.currentTimeMillis() + 15000;
            // tentativas periódicas, além dos eventos de ecrã
            for (int t = 1500; t <= 15000; t += 1000) {
                handler.postDelayed(this::tentarTocar, t);
            }
        }
    }

    /** Abre a app Claude e fica à espera que o utilizador toque no botão de voz. */
    public void comecarAprender() {
        aprender = true;
        candidatos = null;
        Intent i = getPackageManager().getLaunchIntentForPackage(Dados.PACOTE_CLAUDE);
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        }
        handler.post(() -> Toast.makeText(this,
                "Aguarde uns segundos sem tocar em nada…", Toast.LENGTH_LONG).show());
        handler.postDelayed(() -> capturar(false), 3500);
        handler.postDelayed(() -> capturar(true), 6000);
    }

    /** Um botão encontrado no ecrã do Claude. */
    public static final class Candidato {
        public String id, desc, texto;
        public float x, y;
        public String rotulo() {
            String r = !desc.isEmpty() ? desc : (!texto.isEmpty() ? texto : (id.isEmpty() ? "(sem nome)" : id));
            String zona = (y > 0.66f ? "em baixo" : y < 0.33f ? "em cima" : "ao meio")
                    + (x > 0.66f ? ", à direita" : x < 0.33f ? ", à esquerda" : ", ao centro");
            return r + "  —  " + zona;
        }
        public boolean pareceVoz() {
            String d = (desc + " " + texto + " " + id).toLowerCase(Locale.ROOT);
            return d.contains("voice") || d.contains("voz") || d.contains("audio")
                    || d.contains("áudio") || d.contains("speak") || d.contains("falar");
        }
    }

    public static volatile java.util.List<Candidato> candidatos;

    private void capturar(boolean ultima) {
        if (!aprender || candidatos != null) return;
        AccessibilityNodeInfo raiz = null;
        for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
            AccessibilityNodeInfo r = w.getRoot();
            if (r != null && r.getPackageName() != null
                    && Dados.PACOTE_CLAUDE.contentEquals(r.getPackageName())) { raiz = r; break; }
        }
        if (raiz == null) raiz = getRootInActiveWindow();
        if (raiz == null || raiz.getPackageName() == null
                || !Dados.PACOTE_CLAUDE.contentEquals(raiz.getPackageName())) {
            if (ultima) handler.post(() -> Toast.makeText(this,
                    "Não consegui ler o ecrã do Claude. Tente de novo.", Toast.LENGTH_LONG).show());
            return;
        }
        java.util.List<Candidato> lista = new java.util.ArrayList<>();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        juntar(raiz, lista, dm, 0);
        if (lista.isEmpty() && !ultima) return;
        // os que parecem de voz primeiro, depois os de baixo para cima
        lista.sort((a, b) -> {
            if (a.pareceVoz() != b.pareceVoz()) return a.pareceVoz() ? -1 : 1;
            return Float.compare(b.y, a.y);
        });
        candidatos = lista;
        aprender = false;
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        i.putExtra("escolher", true);
        startActivity(i);
    }

    private void juntar(AccessibilityNodeInfo n, java.util.List<Candidato> lista, DisplayMetrics dm, int prof) {
        if (n == null || prof > 50) return;
        String d = n.getContentDescription() == null ? "" : n.getContentDescription().toString().trim();
        String t = n.getText() == null ? "" : n.getText().toString().trim();
        String id = n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName();
        boolean interessa = n.isVisibleToUser() && (n.isClickable() || !d.isEmpty())
                && (!d.isEmpty() || !id.isEmpty() || (!t.isEmpty() && t.length() < 40));
        if (interessa) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.width() > 0 && r.height() > 0 && r.width() < dm.widthPixels * 0.6f) {
                Candidato c = new Candidato();
                c.id = id; c.desc = d; c.texto = t.length() < 40 ? t : "";
                c.x = r.exactCenterX() / dm.widthPixels;
                c.y = r.exactCenterY() / dm.heightPixels;
                lista.add(c);
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) juntar(n.getChild(i), lista, dm, prof + 1);
    }

    public boolean claudeVisivel() { return claudeEmPrimeiroPlano; }

    // ---------------------------------------------------------------- eventos

    @Override
    public void onAccessibilityEvent(AccessibilityEvent ev) {
        CharSequence pkg = ev.getPackageName();
        if (pkg == null) return;
        boolean ehClaude = Dados.PACOTE_CLAUDE.contentEquals(pkg);

        if (ev.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            boolean antes = claudeEmPrimeiroPlano;
            if (ehClaude) {
                claudeEmPrimeiroPlano = true;
            } else if (!pkg.toString().equals(getPackageName())
                    && !pkg.toString().startsWith("com.android.systemui")) {
                claudeEmPrimeiroPlano = false;
            }
            if (antes && !claudeEmPrimeiroPlano) {
                // saiu do Claude: volta a ficar à escuta do botão dos phones
                VozService.rearmar(this);
            }
        }

        if (!ehClaude) return;

        if (aprender && ev.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            AccessibilityNodeInfo n = ev.getSource();
            if (n != null) {
                registarBotao(n);
            }
            return;
        }

        if (System.currentTimeMillis() < tocarAte
                && (ev.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || ev.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) {
            tentarTocar();
        }
    }

    private void registarBotao(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float x = dm.widthPixels > 0 ? r.exactCenterX() / dm.widthPixels : -1f;
        float y = dm.heightPixels > 0 ? r.exactCenterY() / dm.heightPixels : -1f;
        String id = n.getViewIdResourceName();
        String desc = n.getContentDescription() == null ? "" : n.getContentDescription().toString();
        String txt = n.getText() == null ? "" : n.getText().toString();
        Dados.guardarBotao(this, id, desc, txt, x, y);
        aprender = false;
        candidatos = java.util.Collections.emptyList();
        String rotulo = !desc.isEmpty() ? desc : (!txt.isEmpty() ? txt : "posição no ecrã");
        handler.post(() -> Toast.makeText(this,
                "Aprendido! Botão de voz: " + rotulo, Toast.LENGTH_LONG).show());
    }

    // ---------------------------------------------------------------- tocar

    private void tentarTocar() {
        if (System.currentTimeMillis() >= tocarAte) return;
        AccessibilityNodeInfo raiz = getRootInActiveWindow();
        if (raiz == null || raiz.getPackageName() == null
                || !Dados.PACOTE_CLAUDE.contentEquals(raiz.getPackageName())) return;

        AccessibilityNodeInfo alvo = procurar(raiz);
        if (alvo != null) {
            if (clicar(alvo)) {
                tocarAte = 0;
                return;
            }
        }
        // último recurso: tocar na posição aprendida, depois de o ecrã estabilizar
        long faltam = tocarAte - System.currentTimeMillis();
        if (faltam < 9000 && Dados.botaoAprendido(this)) {
            float xr = Dados.botaoX(this), yr = Dados.botaoY(this);
            if (xr > 0 && yr > 0) {
                DisplayMetrics dm = getResources().getDisplayMetrics();
                tocarEm(xr * dm.widthPixels, yr * dm.heightPixels);
                tocarAte = 0;
            }
        }
    }

    private AccessibilityNodeInfo procurar(AccessibilityNodeInfo raiz) {
        String id = Dados.botaoId(this);
        if (id != null && !id.isEmpty()) {
            java.util.List<AccessibilityNodeInfo> l = raiz.findAccessibilityNodeInfosByViewId(id);
            if (l != null && !l.isEmpty()) return l.get(0);
        }
        String desc = Dados.botaoDesc(this);
        String txt = Dados.botaoTexto(this);
        AccessibilityNodeInfo n = percorrer(raiz, desc, txt, 0);
        if (n != null) return n;
        // sem aprendizagem: procurar por descrições típicas
        return percorrer(raiz, null, null, 0);
    }

    private AccessibilityNodeInfo percorrer(AccessibilityNodeInfo n, String desc, String txt, int prof) {
        if (n == null || prof > 40) return null;
        String d = n.getContentDescription() == null ? "" : n.getContentDescription().toString();
        String t = n.getText() == null ? "" : n.getText().toString();
        boolean ok;
        if (desc != null || txt != null) {
            ok = (desc != null && !desc.isEmpty() && desc.equals(d))
                    || (txt != null && !txt.isEmpty() && txt.equals(t));
        } else {
            String dl = d.toLowerCase(Locale.ROOT);
            ok = dl.contains("voice mode") || dl.contains("modo de voz")
                    || dl.contains("start voice") || dl.contains("iniciar voz")
                    || dl.equals("voice") || dl.equals("voz");
        }
        if (ok && n.isVisibleToUser()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = percorrer(n.getChild(i), desc, txt, prof + 1);
            if (r != null) return r;
        }
        return null;
    }

    private boolean clicar(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = n;
        int saltos = 0;
        while (c != null && !c.isClickable() && saltos < 5) {
            c = c.getParent();
            saltos++;
        }
        if (c != null && c.isClickable() && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true;
        }
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.width() > 0 && r.height() > 0) {
            return tocarEm(r.exactCenterX(), r.exactCenterY());
        }
        return false;
    }

    private boolean tocarEm(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60))
                .build();
        return dispatchGesture(g, null, null);
    }
}
