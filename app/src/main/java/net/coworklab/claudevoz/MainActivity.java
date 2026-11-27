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

        boolean temChave = !Cerebro.chave(this).isEmpty();
        linhaEstado("Plano B: cérebro com IA (quando um passo falha)", Cerebro.ligado(this),
                temChave ? "Alterar chave" : "Pôr chave", v -> definirChave());
        if (temChave) {
            CheckBox ia = new CheckBox(this);
            ia.setText("Usar a IA quando uma tarefa ensinada falhar");
            ia.setChecked(Cerebro.ligado(this));
            ia.setOnCheckedChangeListener((c, v) -> { Cerebro.setLigado(this, v); });
            raiz.addView(ia);
        }

        CheckBox auto = new CheckBox(this);
        auto.setText("Nas tarefas ainda não ensinadas: ligar o modo de voz ao abrir o projecto");
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
                    .setMessage("Com os phones ligados e sem música a tocar, faça agora o gesto que escolheu (ex.: 3 toques num auricular). Vai ouvir \"Gesto guardado\".")
                    .setPositiveButton("Feito", (d, w) -> desenhar())
                    .show();
        });
        Button testar = botao("Testar agora (ouvir)");
        testar.setOnClickListener(v -> VozService.enviar(this, VozService.ACAO_OUVIR));
        raiz.addView(testar);

        seccao("3. Projectos");
        raiz.addView(texto("Para cada projecto: copie o link (claude.ai/project/...), adicione-o aqui e grave a sua voz a dizer o nome 3 vezes, com os phones ligados. Depois toque nele e em \"Ensinar tarefa\": a app abre o projecto, faz os toques que quer (ex.: botão do modo de voz) e carrega em Terminar.", 13, false));

        List<Dados.Projecto> lista = Dados.lerProjectos(this);
        for (int i = 0; i < lista.size(); i++) {
            final int idx = i;
            Dados.Projecto pr = lista.get(i);
            int nv = Voz.quantos(this, pr.link);
            int np = Rotinas.quantos(this, pr.link);
            Button item = botao(pr.nome + "   " + (nv >= 3 ? "🎤✅" : "🎤 " + nv + "/3")
                    + "   " + (np > 0 ? "🎓 " + np + (np == 1 ? " toque" : " toques") : "🎓 por ensinar"));
            item.setAllCaps(false);
            item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            item.setOnClickListener(v -> editar(idx));
            raiz.addView(item);
        }
        Button novo = botao("+ Adicionar projecto");
        novo.setOnClickListener(v -> editar(-1));
        raiz.addView(novo);

        seccao("Diagnóstico");
        StringBuilder sb = new StringBuilder("Reconhecimento de voz instalado: ");
        java.util.List<android.content.ComponentName> sv = VozService.servicosReconhecimento(this);
        if (sv.isEmpty()) sb.append("nenhum");
        for (android.content.ComponentName cn : sv) sb.append("\n• ").append(cn.getPackageName());
        String ue = getSharedPreferences("claudevoz", MODE_PRIVATE).getString("ultimo_erro", "");
        String ut = getSharedPreferences("claudevoz", MODE_PRIVATE).getString("ultimo_toque", "");
        if (!ut.isEmpty()) sb.append("\nÚltimo toque automático:\n").append(ut);
        String uv = getSharedPreferences("claudevoz", MODE_PRIVATE).getString("ultima_voz", "");
        if (!uv.isEmpty()) sb.append("\nÚltima voz: ").append(uv);
        String ok = Motores.descricaoOk(this);
        if (!ok.isEmpty()) sb.append("\nMotor que funciona: ").append(ok);
        if (!ue.isEmpty()) sb.append("\nÚltimo erro: ").append(ue);
        raiz.addView(texto(sb.toString(), 12, false));

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
        Button gravar = botao("🎤 Gravar a minha voz");
        TextView estadoGravacao = texto("", 13, false);
        Runnable atualizar = () -> {
            String l = link.getText().toString().trim();
            int n = l.isEmpty() ? 0 : Voz.quantos(this, l);
            gravar.setText("🎤 Gravar a minha voz (" + n + " gravadas)");
            if (estadoGravacao.getText().length() == 0 || n == 0)
                estadoGravacao.setText(n >= 3 ? "✅ Pronto. Pode gravar mais para melhorar."
                        : "Carregue e diga o nome do projecto. Faça pelo menos 3 gravações.");
        };
        atualizar.run();
        gravar.setOnClickListener(v -> {
            String l = link.getText().toString().trim();
            if (l.isEmpty()) { Toast.makeText(this, "Preencha primeiro o link.", Toast.LENGTH_SHORT).show(); return; }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                pedirPermissoes(); return;
            }
            gravar.setEnabled(false);
            estadoGravacao.setText("🎙️ Diga agora o nome do projecto…");
            new Thread(() -> {
                short[] som = Voz.gravar(this, true,
                        () -> runOnUiThread(() -> estadoGravacao.setText("🎙️ A ouvir…")));
                float[][] imp = som == null ? null : Voz.impressao(som);
                String msg;
                if (som == null) msg = "Não ouvi nada. Carregue outra vez e fale um pouco mais alto.";
                else if (imp == null) msg = "Foi muito curto. Carregue outra vez.";
                else {
                    try { Voz.guardar(this, l, imp); msg = "✅ Gravado (" + String.format(java.util.Locale.ROOT, "%.1f", som.length / 16000.0) + " s)."; }
                    catch (Exception e) { msg = "Não consegui guardar: " + e.getMessage(); }
                }
                String m = msg;
                runOnUiThread(() -> {
                    gravar.setEnabled(true);
                    estadoGravacao.setText(m);
                    atualizar.run();
                });
            }).start();
        });
        Button apagarVoz = botao("Apagar gravações deste projecto");
        apagarVoz.setOnClickListener(v -> {
            String l = link.getText().toString().trim();
            if (!l.isEmpty()) Voz.apagar(this, l);
            estadoGravacao.setText("");
            atualizar.run();
        });
        f.addView(gravar);
        f.addView(estadoGravacao);
        f.addView(apagarVoz);

        final AlertDialog[] janela = new AlertDialog[1];
        if (atual != null) {
            int np = Rotinas.quantos(this, atual.link);
            f.addView(texto(np > 0 ? "🎓 Tarefa ensinada: " + passosTexto(atual.link)
                    : "🎓 Tarefa ainda não ensinada (faz o habitual: abre e liga o modo de voz).", 13, false));
            Button ensinar = botao(np > 0 ? "🎓 Ensinar tarefa de novo" : "🎓 Ensinar tarefa");
            ensinar.setOnClickListener(v -> {
                ToqueService t = ToqueService.get();
                if (t == null) { Toast.makeText(this, "Active primeiro a acessibilidade.", Toast.LENGTH_LONG).show(); return; }
                if (janela[0] != null) janela[0].dismiss();
                new AlertDialog.Builder(this)
                        .setTitle("Ensinar tarefa")
                        .setMessage("A app vai abrir o projecto e aparece uma barra vermelha \"A gravar\".\n\n"
                                + "1. Espere que o projecto abra.\n"
                                + "2. Na barra vermelha, carregue em \"Escolher botão\" e escolha o botão (ex.: ⭐ modo de voz). A app toca nele por si.\n"
                                + "3. Repita o passo 2 para cada botão da tarefa.\n"
                                + "4. Carregue em \"Terminar\".\n\n"
                                + "Se o último toque abrir um ecrã que tapa a barra, volte atrás e carregue em Terminar.")
                        .setPositiveButton("Começar", (x, y) -> t.comecarGravar(atual))
                        .setNegativeButton("Cancelar", null)
                        .show();
            });
            f.addView(ensinar);
            Button experimentar = botao("▶ Experimentar a tarefa");
            experimentar.setOnClickListener(v -> {
                ToqueService t = ToqueService.get();
                if (t == null) { Toast.makeText(this, "Active primeiro a acessibilidade.", Toast.LENGTH_LONG).show(); return; }
                if (janela[0] != null) janela[0].dismiss();
                t.executar(atual, Dados.ativarVozAuto(this));
            });
            f.addView(experimentar);
            if (np > 0) {
                Button esquecer = botao("Esquecer a tarefa ensinada");
                esquecer.setOnClickListener(v -> {
                    Rotinas.apagar(this, atual.link);
                    if (janela[0] != null) janela[0].dismiss();
                    desenhar();
                });
                f.addView(esquecer);
            }
        }

        AlertDialog.Builder d = new AlertDialog.Builder(this)
                .setTitle(atual == null ? "Novo projecto" : "Editar projecto")
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
        ScrollView rolar = new ScrollView(this);
        rolar.addView(f);
        d.setView(rolar);
        janela[0] = d.show();
    }

    private String passosTexto(String link) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Rotinas.Passo p : Rotinas.ler(this, link)) {
            if (!Rotinas.TOCAR.equals(p.tipo)) continue;
            n++;
            sb.append(n == 1 ? "" : " → ").append(n).append(". ").append(p.nome());
        }
        return sb.toString();
    }

    private void definirChave() {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(dp(18), dp(8), dp(18), 0);
        f.addView(texto("Crie uma chave em console.anthropic.com → API keys e cole-a aqui. Fica guardada só neste telemóvel. A IA só é usada quando um passo ensinado falha.", 13, false));
        EditText k = campo("sk-ant-…", "");
        k.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        f.addView(k);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Chave da API da Anthropic")
                .setView(f)
                .setPositiveButton("Guardar", (x, y) -> {
                    String v = k.getText().toString().trim();
                    if (!v.isEmpty()) { Cerebro.setChave(this, v); Cerebro.setLigado(this, true); }
                    desenhar();
                })
                .setNegativeButton("Cancelar", null);
        if (!Cerebro.chave(this).isEmpty()) {
            b.setNeutralButton("Apagar chave", (x, y) -> { Cerebro.setChave(this, ""); desenhar(); });
        }
        b.show();
    }

    private android.speech.SpeechRecognizer gravador;
    private int comboGravar = -1;

    /** Ouve o utilizador dizer o nome e junta o que foi percebido aos "outros nomes". */
    private void gravarNome(EditText alt, TextView estado) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pedirPermissoes();
            return;
        }
        java.util.List<Motores.Combo> cs = Motores.combos(this);
        if (comboGravar < 0) comboGravar = Motores.inicio(this, cs.size());
        final int meu = comboGravar % cs.size();
        final Motores.Combo combo = cs.get(meu);
        if (gravador != null) { try { gravador.destroy(); } catch (Exception ignored) {} }
        try {
            gravador = Motores.criar(this, combo);
        } catch (Exception e) {
            comboGravar++;
            estado.setText("Motor indisponível (" + combo.descricao() + "). Carregue outra vez.");
            return;
        }
        estado.setText("🎙️ A ouvir… diga o nome agora.  [" + (meu + 1) + "/" + cs.size() + ": " + combo.descricao() + "]");
        gravador.setRecognitionListener(new android.speech.RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle b) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float v) {}
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() { estado.setText("A processar…"); }
            @Override public void onPartialResults(Bundle b) {}
            @Override public void onEvent(int t, Bundle b) {}

            @Override
            public void onError(int erro) {
                comboGravar = meu + 1;
                String prox = cs.get(comboGravar % cs.size()).descricao();
                estado.setText("Erro " + erro + " com " + combo.descricao()
                        + ". Carregue outra vez e repita — vou tentar com " + prox + ".");
                getSharedPreferences("claudevoz", MODE_PRIVATE).edit()
                        .putString("ultimo_erro", "gravar nome: erro " + erro + " (" + combo.descricao() + ")").apply();
            }

            @Override
            public void onResults(Bundle b) {
                java.util.ArrayList<String> r = b.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
                if (r == null || r.isEmpty()) { onError(7); return; }
                Motores.funcionou(MainActivity.this, meu, combo);
                java.util.LinkedHashSet<String> todos = new java.util.LinkedHashSet<>();
                for (String a : alt.getText().toString().split(",")) if (!a.trim().isEmpty()) todos.add(a.trim());
                int n = 0;
                for (String h : r) {
                    if (n >= 3) break;
                    String hh = h.trim();
                    boolean existe = false;
                    for (String t : todos) if (t.equalsIgnoreCase(hh)) existe = true;
                    if (!hh.isEmpty() && !existe) todos.add(hh);
                    n++;
                }
                alt.setText(android.text.TextUtils.join(", ", todos));
                estado.setText("✅ Ouvi: \"" + r.get(0) + "\" (" + combo.descricao()
                        + "). Pode repetir para aprender mais variações.");
            }
        });
        try {
            gravador.startListening(Motores.pedido(this, combo));
        } catch (Exception e) {
            comboGravar = meu + 1;
            estado.setText("Falhou: " + e.getClass().getSimpleName() + ". Carregue outra vez.");
        }
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
