package net.coworklab.claudevoz;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private LinearLayout raiz;
    private boolean jaProcurou = false;
    private String linkPendente = null;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        int p = dp(18);
        raiz.setPadding(p, p, p, p);
        sv.addView(raiz);
        setContentView(sv);
        pedirPermissoes();
    }

    @Override
    protected void onResume() {
        super.onResume();
        desenhar();
        if (Actualizador.ACAO_RESULTADO.equals(getIntent().getAction())) {
            String msg = Actualizador.tratarResultado(this, getIntent());
            setIntent(new Intent(this, MainActivity.class));
            if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        } else if (!jaProcurou) {
            jaProcurou = true;
            procurarActualizacao(false);
        }
        java.util.List<ToqueService.Candidato> c = ToqueService.candidatos;
        if (c != null && !c.isEmpty() && getIntent().getBooleanExtra("escolher", false)) {
            getIntent().removeExtra("escolher");
            escolherBotao(c);
        }
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        if (linkPendente != null && (Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls())) {
            String l = linkPendente;
            linkPendente = null;
            instalar(l);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    private void procurarActualizacao(boolean avisarSemNovidades) {
        Actualizador.procurar(this, (nova, link, erro) -> {
            if (isFinishing()) return;
            if (erro != null) {
                if (avisarSemNovidades) Toast.makeText(this, "Não consegui verificar: " + erro, Toast.LENGTH_LONG).show();
                return;
            }
            if (nova == 0) {
                if (avisarSemNovidades) Toast.makeText(this, "Já tem a versão mais recente.", Toast.LENGTH_SHORT).show();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Há uma versão nova (1.0." + nova + ")")
                    .setMessage("Quer instalar agora? Tem a 1.0." + Actualizador.versaoInstalada(this) + ".")
                    .setPositiveButton("Actualizar", (d, w) -> instalar(link))
                    .setNegativeButton("Mais tarde", null)
                    .show();
        });
    }

    private void instalar(String link) {
        if (!Actualizador.temPermissao(this)) {
            linkPendente = link;
            Toast.makeText(this, "Permita instalar apps a partir da Claude Voz e volte atrás.", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "A descarregar…", Toast.LENGTH_SHORT).show();
        Actualizador.descarregarEInstalar(this, link, (ok, l, erro) -> {
            if (erro != null) Toast.makeText(this, "Falhou: " + erro, Toast.LENGTH_LONG).show();
        });
    }

    private void escolherBotao(java.util.List<ToqueService.Candidato> lista) {
        String[] nomes = new String[lista.size()];
        for (int i = 0; i < lista.size(); i++) {
            ToqueService.Candidato c = lista.get(i);
            nomes[i] = (c.pareceVoz() ? "⭐ " : "") + c.rotulo();
        }
        new AlertDialog.Builder(this)
                .setTitle("Qual é o botão do modo de voz (onda sonora)?")
                .setItems(nomes, (d, w) -> {
                    ToqueService.Candidato c = lista.get(w);
                    Dados.guardarBotao(this, c.id, c.desc, c.texto, c.x, c.y);
                    ToqueService.candidatos = null;
                    Toast.makeText(this, "Aprendido: " + c.rotulo(), Toast.LENGTH_LONG).show();
                    desenhar();
                })
                .setNegativeButton("Cancelar", (d, w) -> ToqueService.candidatos = null)
                .show();
    }

    private void pedirPermissoes() {
        List<String> pedir = new ArrayList<>();
        pedir.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33) pedir.add(Manifest.permission.POST_NOTIFICATIONS);
        if (Build.VERSION.SDK_INT >= 31) pedir.add(Manifest.permission.BLUETOOTH_CONNECT);
        List<String> falta = new ArrayList<>();
        for (String s : pedir) {
            if (checkSelfPermission(s) != PackageManager.PERMISSION_GRANTED) falta.add(s);
        }
        if (!falta.isEmpty()) requestPermissions(falta.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int c, String[] perms, int[] res) {
        desenhar();
    }

    // ------------------------------------------------------------ ecrã

    private void desenhar() {
        raiz.removeAllViews();

        TextView titulo = texto("Claude Voz", 26, true);
        raiz.addView(titulo);
        raiz.addView(texto("Faça o gesto escolhido nos phones, diga o nome do projecto e a app abre-o no Claude com o modo de voz ligado. Os outros gestos continuam a controlar a música.", 14, false));

        seccao("1. Configuração");

        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        linhaEstado("Microfone", mic, mic ? null : "Dar permissão", v -> pedirPermissoes());

        boolean aces = ToqueService.ativo();
        linhaEstado("Acessibilidade (abrir e tocar no botão de voz)", aces,
                aces ? null : "Activar", v -> {
                    Toast.makeText(this, "Procure \"Claude Voz\" na lista e active.", Toast.LENGTH_LONG).show();
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                });

        boolean aprendido = Dados.botaoAprendido(this);
        linhaEstado("Botão do modo de voz aprendido", aprendido,
                aprendido ? "Ensinar de novo" : "Ensinar", v -> {
                    ToqueService t = ToqueService.get();
                    if (t == null) {
                        Toast.makeText(this, "Active primeiro a acessibilidade.", Toast.LENGTH_LONG).show();
                    } else {
                        t.comecarAprender();
                    }
                });

        CheckBox auto = new CheckBox(this);
        auto.setText("Ligar o modo de voz automaticamente ao abrir o projecto");
        auto.setChecked(Dados.ativarVozAuto(this));
        auto.setOnCheckedChangeListener((c, v) -> Dados.setAtivarVozAuto(this, v));
        raiz.addView(auto);

        seccao("2. Ligar");
        boolean ativo = VozService.aCorrer();
        linhaEstado(ativo ? "A escutar o botão dos phones" : "Desligada", ativo,
                ativo ? "Desligar" : "Ligar", v -> {
                    if (VozService.aCorrer()) {
                        VozService.enviar(this, VozService.ACAO_PARAR);
                    } else {
                        VozService.enviar(this, VozService.ACAO_LIGAR);
                    }
                    raiz.postDelayed(this::desenhar, 600);
                });
        int tecla = Dados.teclaGatilho(this);
        linhaEstado("Gesto dos phones: " + Dados.nomeTecla(tecla), true, "Ensinar gesto", v -> {
            if (!VozService.aCorrer()) {
                Toast.makeText(this, "Toque primeiro em Ligar.", Toast.LENGTH_LONG).show();
                return;
            }
            VozService.ensinarGesto(this);
            new AlertDialog.Builder(this)
                    .setTitle("Ensinar gesto")
                    .setMessage("Com os phones ligados e sem música a tocar, faça agora o gesto que escolheu (ex.: 3 toques no auricular esquerdo). Vai ouvir \"Gesto guardado\".")
                    .setPositiveButton("Feito", (d, w) -> desenhar())
                    .show();
        });
        Button testar = botao("Testar agora (ouvir)");
        testar.setOnClickListener(v -> VozService.enviar(this, VozService.ACAO_OUVIR));
        raiz.addView(testar);

        seccao("3. Projectos");
        raiz.addView(texto("Para cada projecto: abra-o no claude.ai, copie o link (claude.ai/project/...) e adicione-o aqui.", 13, false));

        List<Dados.Projecto> lista = Dados.lerProjectos(this);
        for (int i = 0; i < lista.size(); i++) {
            final int idx = i;
            Dados.Projecto pr = lista.get(i);
            Button item = botao(pr.nome + (pr.alternativas.isEmpty() ? "" : "  (" + pr.alternativas + ")"));
            item.setAllCaps(false);
            item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            item.setOnClickListener(v -> editar(idx));
            raiz.addView(item);
        }
        Button novo = botao("+ Adicionar projecto");
        novo.setOnClickListener(v -> editar(-1));
        raiz.addView(novo);

        seccao("4. Versão");
        raiz.addView(texto("Versão instalada: 1.0." + Actualizador.versaoInstalada(this), 14, false));
        Button act = botao("Procurar actualização");
        act.setOnClickListener(v -> procurarActualizacao(true));
        raiz.addView(act);
    }

    private void editar(int idx) {
        List<Dados.Projecto> lista = Dados.lerProjectos(this);
        Dados.Projecto atual = idx >= 0 ? lista.get(idx) : null;

        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(dp(18), dp(8), dp(18), 0);

        EditText nome = campo("Nome a dizer (ex.: CapacitaMoz)", atual == null ? "" : atual.nome);
        EditText link = campo("Link (https://claude.ai/project/...)", atual == null ? linkDaAreaTransferencia() : atual.link);
        link.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText alt = campo("Outros nomes, separados por vírgula (opcional)", atual == null ? "" : atual.alternativas);
        f.addView(nome);
        f.addView(link);
        f.addView(alt);

        AlertDialog.Builder d = new AlertDialog.Builder(this)
                .setTitle(atual == null ? "Novo projecto" : "Editar projecto")
                .setView(f)
                .setPositiveButton("Guardar", (x, y) -> {
                    String n = nome.getText().toString().trim();
                    String l = link.getText().toString().trim();
                    if (n.isEmpty() || l.isEmpty()) {
                        Toast.makeText(this, "Preencha o nome e o link.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!l.startsWith("http")) l = "https://" + l;
                    Dados.Projecto novo = new Dados.Projecto(n, l, alt.getText().toString().trim());
                    if (idx >= 0) lista.set(idx, novo); else lista.add(novo);
                    Dados.guardarProjectos(this, lista);
                    desenhar();
                })
                .setNegativeButton("Cancelar", null);
        if (atual != null) {
            d.setNeutralButton("Apagar", (x, y) -> {
                lista.remove(idx);
                Dados.guardarProjectos(this, lista);
                desenhar();
            });
        }
        d.show();
    }

    private String linkDaAreaTransferencia() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData c = cm.getPrimaryClip();
            if (c != null && c.getItemCount() > 0) {
                CharSequence t = c.getItemAt(0).getText();
                if (t != null && t.toString().contains("claude.ai/project")) return t.toString().trim();
            }
        } catch (Exception ignored) {}
        return "";
    }

    // ------------------------------------------------------------ pequenos ajudantes

    private void seccao(String s) {
        TextView t = texto(s, 18, true);
        t.setPadding(0, dp(22), 0, dp(6));
        raiz.addView(t);
    }

    private void linhaEstado(String rotulo, boolean ok, String acao, View.OnClickListener l) {
        LinearLayout linha = new LinearLayout(this);
        linha.setOrientation(LinearLayout.HORIZONTAL);
        linha.setGravity(Gravity.CENTER_VERTICAL);
        linha.setPadding(0, dp(4), 0, dp(4));
        TextView t = texto((ok ? "✅ " : "⚪ ") + rotulo, 15, false);
        linha.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (acao != null) {
            Button b = botao(acao);
            b.setOnClickListener(l);
            linha.addView(b);
        }
        raiz.addView(linha);
    }

    private TextView texto(String s, int sp, boolean negrito) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        if (negrito) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private Button botao(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        return b;
    }

    private EditText campo(String dica, String valor) {
        EditText e = new EditText(this);
        e.setHint(dica);
        e.setText(valor);
        e.setSingleLine(true);
        e.setHintTextColor(Color.GRAY);
        return e;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
