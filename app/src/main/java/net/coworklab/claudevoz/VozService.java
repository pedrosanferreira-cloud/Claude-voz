package net.coworklab.claudevoz;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Serviço que fica activo em segundo plano:
 *  - apanha o botão play/pausa dos phones Bluetooth;
 *  - pergunta "Qual projecto?", ouve a resposta e abre o projecto na app Claude.
 */
public class VozService extends Service {

    public static final String ACAO_LIGAR = "ligar";
    public static final String ACAO_OUVIR = "ouvir";
    public static final String ACAO_REARMAR = "rearmar";
    public static final String ACAO_PARAR = "parar";
    public static final String ACAO_GESTO = "gesto";

    private static final String CANAL = "claudevoz";
    private static final int NOTIF_ID = 7;

    private static boolean aCorrer = false;

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaSession sessao;
    private TextToSpeech tts;
    private boolean ttsPronto = false;
    private SpeechRecognizer reconhecedor;
    private AudioManager audio;

    private volatile boolean silencioAtivo = false;
    private Thread threadSilencio;
    private boolean ocupado = false;
    private boolean emPrimeiroPlano = false;
    private boolean armado = false;
    private int silencioSeguido = 0;
    public static volatile boolean aprenderGesto = false;

    /** Começa a esperar o gesto dos phones para o aprender. */
    public static void ensinarGesto(Context c) {
        aprenderGesto = true;
        enviar(c, ACAO_GESTO);
    }

    public static boolean aCorrer() { return aCorrer; }

    public static void enviar(Context c, String acao) {
        Intent i = new Intent(c, VozService.class).setAction(acao);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {}
    }

    /** Chamado quando o utilizador sai da app Claude. */
    public static void rearmar(Context c) {
        if (aCorrer) {
            try { c.startService(new Intent(c, VozService.class).setAction(ACAO_REARMAR)); }
            catch (Exception ignored) {}
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        criarCanal();
        comecarPrimeiroPlano();
        aCorrer = true;

        tts = new TextToSpeech(this, estado -> {
            if (estado == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(new Locale("pt", "PT"));
                if (r < 0) tts.setLanguage(new Locale("pt"));
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
                ttsPronto = true;
            }
        });

        main.postDelayed(vigia, 3000);
        sessao = new MediaSession(this, "ClaudeVoz");
        sessao.setCallback(new MediaSession.Callback() {
            @Override
            public boolean onMediaButtonEvent(Intent intent) {
                KeyEvent ev = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (ev == null) return true;
                if (ev.getAction() != KeyEvent.ACTION_DOWN || ev.getRepeatCount() != 0) return true;
                int k = ev.getKeyCode();
                if (aprenderGesto) {
                    aprenderGesto = false;
                    Dados.setTeclaGatilho(VozService.this, k);
                    falar("Gesto guardado. A Claude Voz vai reagir a " + Dados.nomeTecla(k) + ".", null);
                    return true;
                }
                if (k == Dados.teclaGatilho(VozService.this)) {
                    ouvir();
                } else {
                    passarAdiante(k);
                }
                return true;
            }


        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!emPrimeiroPlano) comecarPrimeiroPlano();
        String acao = intent == null ? ACAO_LIGAR : intent.getAction();
        if (acao == null) acao = ACAO_LIGAR;
        switch (acao) {
            case ACAO_OUVIR:
                armar();
                ouvir();
                break;
            case ACAO_PARAR:
                stopSelf();
                break;
            case ACAO_GESTO:
                armar();
                falar("Faça agora o gesto nos phones.", null);
                break;
            case ACAO_REARMAR:
            case ACAO_LIGAR:
            default:
                if (!ocupado) armar();
                break;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        aCorrer = false;
        main.removeCallbacks(vigia);
        pararSilencio();
        if (sessao != null) { sessao.setActive(false); sessao.release(); }
        if (reconhecedor != null) reconhecedor.destroy();
        if (tts != null) tts.shutdown();
        desligarMicBluetooth();
        super.onDestroy();
    }

    // ------------------------------------------------------------ notificação

    private void criarCanal() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CANAL, "Claude Voz", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private void comecarPrimeiroPlano() {
        int fl = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent abrir = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), fl);
        PendingIntent ouvir = PendingIntent.getService(this, 1,
                new Intent(this, VozService.class).setAction(ACAO_OUVIR), fl);
        PendingIntent parar = PendingIntent.getService(this, 2,
                new Intent(this, VozService.class).setAction(ACAO_PARAR), fl);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CANAL) : new Notification.Builder(this);
        Notification n = b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Claude Voz activa")
                .setContentText("Faça o gesto dos phones e diga o projecto")
                .setContentIntent(abrir)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Ouvir agora", ouvir).build())
                .addAction(new Notification.Action.Builder(null, "Desligar", parar).build())
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIF_ID, n);
            }
            emPrimeiroPlano = true;
        } catch (Exception e) {
            // o Android recusou (ex.: arranque em segundo plano); tenta só como reprodução
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                    emPrimeiroPlano = true;
                }
            } catch (Exception ignored) {}
        }
    }

    // ------------------------------------------------------------ "armar" o botão dos phones

    /**
     * O Android envia o botão play/pausa à app que está a "tocar" som.
     * Por isso tocamos silêncio (volume zero) para receber o botão.
     */
    private void armar() {
        sessao.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE)
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .build());
        sessao.setActive(true);
        comecarSilencio();
        armado = true;
    }

    private void desarmar() {
        pararSilencio();
        sessao.setPlaybackState(new PlaybackState.Builder()
                .setState(PlaybackState.STATE_PAUSED, 0, 0f).build());
        sessao.setActive(false);
        armado = false;
    }

    /**
     * Um gesto que não é o da Claude Voz: larga o controlo e entrega-o
     * à app de música, como se a Claude Voz não existisse.
     */
    private void passarAdiante(int k) {
        desarmar();
        silencioSeguido = 0;
        main.postDelayed(() -> {
            long t = android.os.SystemClock.uptimeMillis();
            audio.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, k, 0));
            audio.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, k, 0));
        }, 250);
    }

    /**
     * De 3 em 3 segundos: se outra app está a tocar (música, chamada, modo de voz do Claude)
     * a Claude Voz fica de lado; quando tudo está calado há algum tempo, volta a ficar à escuta.
     */
    private final Runnable vigia = new Runnable() {
        @Override public void run() {
            try {
                if (!ocupado && !aprenderGesto) {
                    int ativos = audio.getActivePlaybackConfigurations().size();
                    int outros = armado ? ativos - 1 : ativos;
                    if (outros > 0) {
                        silencioSeguido = 0;
                        if (armado) desarmar();
                    } else {
                        silencioSeguido++;
                        ToqueService t = ToqueService.get();
                        boolean claudeAberto = t != null && t.claudeVisivel();
                        if (!armado && silencioSeguido >= 2 && !claudeAberto) armar();
                    }
                }
            } catch (Exception ignored) {}
            main.postDelayed(this, 3000);
        }
    };

    private void comecarSilencio() {
        if (silencioAtivo) return;
        silencioAtivo = true;
        threadSilencio = new Thread(() -> {
            int taxa = 8000;
            int tam = Math.max(AudioTrack.getMinBufferSize(taxa, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT), 4096);
            AudioTrack at = null;
            try {
                at = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(taxa)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(tam)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
                at.setVolume(0f);
                at.play();
                byte[] zeros = new byte[tam];
                while (silencioAtivo) {
                    at.write(zeros, 0, zeros.length);
                }
            } catch (Exception ignored) {
            } finally {
                if (at != null) {
                    try { at.stop(); } catch (Exception ignored) {}
                    at.release();
                }
            }
        }, "silencio");
        threadSilencio.start();
    }

    private void pararSilencio() {
        silencioAtivo = false;
        threadSilencio = null;
    }

    // ------------------------------------------------------------ ouvir e abrir

    private void ouvir() {
        if (ocupado) return;
        ocupado = true;
        pararSilencio();
        if (Voz.haModelos(this)) {
            falar("Qual projecto?", () -> main.postDelayed(this::ouvirMinhaVoz, 100));
        } else {
            falar("Qual projecto?", () -> main.postDelayed(this::comecarReconhecimento, 150));
        }
    }

    private List<Motores.Combo> combos = new ArrayList<>();
    private int idx = 0, tentadas = 0, repeticoes = 0;
    private boolean micPhones = true;
    private String ultimoErro = "";

    /** Serviços de reconhecimento instalados, com os da Google primeiro. */
    public static List<android.content.ComponentName> servicosReconhecimento(Context c) {
        List<android.content.ComponentName> l = new ArrayList<>();
        try {
            List<android.content.pm.ResolveInfo> ri = c.getPackageManager().queryIntentServices(
                    new Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0);
            for (android.content.pm.ResolveInfo r : ri) {
                android.content.ComponentName cn = new android.content.ComponentName(
                        r.serviceInfo.packageName, r.serviceInfo.name);
                if (r.serviceInfo.packageName.startsWith("com.google")) l.add(0, cn); else l.add(cn);
            }
        } catch (Exception ignored) {}
        return l;
    }

    /** Compara o que foi dito com as gravações da voz do utilizador. */
    private void ouvirMinhaVoz() {
        new Thread(() -> {
            short[] som = Voz.gravar(this, true, null);
            if (som == null) {
                main.post(() -> falar("Não ouvi nada. Tente outra vez.", this::terminar));
                return;
            }
            float[][] imp = Voz.impressao(som);
            if (imp == null) {
                main.post(() -> falar("Foi muito curto. Tente outra vez.", this::terminar));
                return;
            }
            Voz.Resultado r = Voz.escolher(this, Dados.lerProjectos(this), imp);
            String info = String.format(java.util.Locale.ROOT, "distância %.1f (limite %.1f)%s",
                    r.distancia, r.limite, r.projecto == null ? "" : " → " + r.projecto.nome);
            getSharedPreferences("claudevoz", MODE_PRIVATE).edit().putString("ultima_voz", info).apply();
            main.post(() -> {
                if (r.projecto == null) {
                    falar("Não reconheci o projecto. Tente outra vez.", this::terminar);
                } else {
                    Dados.Projecto p = r.projecto;
                    falar("A abrir " + p.nome, () -> main.post(() -> abrir(p)));
                }
            });
        }).start();
    }

    private void comecarReconhecimento() {
        combos = Motores.combos(this);
        idx = Motores.inicio(this, combos.size());
        tentadas = 0;
        repeticoes = 0;
        micPhones = true;
        tentar();
    }

    private void falhar(String frase) {
        desligarMicBluetooth();
        aviso("Reconhecimento: " + ultimoErro);
        falar(frase, this::terminar);
    }

    private void tentar() {
        if (tentadas >= combos.size()) {
            falhar("Não consegui perceber. Tente outra vez.");
            return;
        }
        final int meu = idx % combos.size();
        final Motores.Combo combo = combos.get(meu);
        if (micPhones) ligarMicBluetooth(); else desligarMicBluetooth();
        main.postDelayed(() -> {
            if (reconhecedor != null) { try { reconhecedor.destroy(); } catch (Exception ignored) {} }
            try {
                reconhecedor = Motores.criar(this, combo);
            } catch (Exception e) {
                ultimoErro = "criar " + combo.descricao();
                idx++; tentadas++;
                tentar();
                return;
            }
            reconhecedor.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle b) {}
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float v) {}
                @Override public void onBufferReceived(byte[] b) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onPartialResults(Bundle b) {}
                @Override public void onEvent(int t, Bundle b) {}

                @Override
                public void onError(int erro) {
                    ultimoErro = erro + " " + nomeErro(erro) + " (" + combo.descricao()
                            + (micPhones ? ", mic phones" : ", mic telemóvel") + ")";
                    if (erro == SpeechRecognizer.ERROR_AUDIO && micPhones) {
                        micPhones = false;           // tenta o mesmo motor com o mic do telemóvel
                        tentar();
                        return;
                    }
                    if (erro == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        falhar("Não ouvi nada. Tente outra vez.");
                        return;
                    }
                    idx++; tentadas++;
                    if (erro == SpeechRecognizer.ERROR_NO_MATCH) {
                        // ouviu mas não percebeu: pede para repetir com o motor seguinte
                        repeticoes++;
                        if (repeticoes > 3) { falhar("Não consegui perceber. Tente outra vez."); return; }
                        desligarMicBluetooth();
                        falar("Repita o nome, por favor.", () -> main.postDelayed(VozService.this::tentar, 150));
                        return;
                    }
                    tentar();                         // erro técnico: muda de motor sem incomodar
                }

                @Override
                public void onResults(Bundle b) {
                    desligarMicBluetooth();
                    ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (r == null || r.isEmpty()) { onError(SpeechRecognizer.ERROR_NO_MATCH); return; }
                    Motores.funcionou(VozService.this, meu, combo);
                    tratarResultado(r);
                }
            });
            try {
                reconhecedor.startListening(Motores.pedido(this, combo));
            } catch (Exception e) {
                ultimoErro = "iniciar " + combo.descricao();
                idx++; tentadas++;
                tentar();
            }
        }, micPhones ? 1200 : 300);
    }

    private static String nomeErro(int e) {
        switch (e) {
            case 1: return "rede (tempo)";
            case 2: return "rede";
            case 3: return "áudio";
            case 4: return "servidor";
            case 5: return "cliente";
            case 6: return "sem fala";
            case 7: return "não percebeu";
            case 8: return "ocupado";
            case 9: return "sem permissão";
            case 10: return "demasiados pedidos";
            case 11: return "servidor desligado";
            case 12: return "língua não suportada";
            case 13: return "língua indisponível";
            default: return "";
        }
    }

    private void aviso(String t) {
        main.post(() -> android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_LONG).show());
        getSharedPreferences("claudevoz", MODE_PRIVATE).edit().putString("ultimo_erro", t).apply();
    }

    private void tratarResultado(List<String> hipoteses) {
        List<Dados.Projecto> lista = Dados.lerProjectos(this);
        if (lista.isEmpty()) {
            falar("Ainda não tem projectos na lista. Abra a app Claude Voz para os adicionar.", this::terminar);
            return;
        }
        Dados.Projecto p = Dados.encontrar(lista, hipoteses);
        if (p == null) {
            String dito = hipoteses.isEmpty() ? "" : hipoteses.get(0);
            falar("Não encontrei o projecto " + dito + ". Tente outra vez.", this::terminar);
            return;
        }
        falar("A abrir " + p.nome, () -> main.post(() -> abrir(p)));
    }

    private void abrir(Dados.Projecto p) {
        ocupado = false;
        desarmar(); // larga o botão dos phones para a app Claude
        ToqueService t = ToqueService.get();
        if (t != null) {
            t.abrirProjecto(p.link, p.nome, Dados.ativarVozAuto(this));
        } else {
            // sem acessibilidade: tenta abrir directamente (pode ser bloqueado pelo Android)
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(p.link));
                i.setPackage(Dados.PACOTE_CLAUDE);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e) {
                falar("Active a acessibilidade da Claude Voz nas definições.", this::terminar);
            }
        }
    }

    private void terminar() {
        ocupado = false;
        main.post(this::armar);
    }

    // ------------------------------------------------------------ áudio

    private void falar(String texto, Runnable depois) {
        if (!ttsPronto) {
            main.postDelayed(() -> { if (depois != null) depois.run(); }, 300);
            return;
        }
        String id = "u" + System.nanoTime();
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String s) {}
            @Override public void onDone(String s) { if (depois != null && id.equals(s)) main.post(depois); }
            @Override public void onError(String s) { if (depois != null && id.equals(s)) main.post(depois); }
        });
        tts.speak(texto, TextToSpeech.QUEUE_FLUSH, null, id);
    }

    @SuppressWarnings("deprecation")
    private void ligarMicBluetooth() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                for (AudioDeviceInfo d : audio.getAvailableCommunicationDevices()) {
                    if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                        audio.setCommunicationDevice(d);
                        return;
                    }
                }
            } else {
                audio.startBluetoothSco();
                audio.setBluetoothScoOn(true);
            }
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("deprecation")
    private void desligarMicBluetooth() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                audio.clearCommunicationDevice();
            } else {
                audio.setBluetoothScoOn(false);
                audio.stopBluetoothSco();
            }
        } catch (Exception ignored) {}
    }
}
