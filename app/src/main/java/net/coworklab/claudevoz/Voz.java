package net.coworklab.claudevoz;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reconhecimento pela voz do próprio utilizador, sem motores de voz:
 * grava o nome dito, tira a "impressão" do som (MFCC) e compara com as gravações
 * guardadas de cada projecto (DTW). Funciona em qualquer língua, siglas, etc.
 */
public final class Voz {

    public static final int TAXA = 16000;
    private static final int FRAME = 400;   // 25 ms
    private static final int SALTO = 160;   // 10 ms
    private static final int NFFT = 512;
    private static final int NMEL = 26;
    private static final int NCEP = 13;

    private Voz() {}

    // ================================================================ gravação

    public interface Progresso { void falaDetectada(); }

    /**
     * Grava até a pessoa acabar de falar. Devolve null se ninguém falou.
     * Espera até 5 s pelo início; pára após 0,7 s de silêncio ou 3,5 s de fala.
     */
    public static short[] gravar(Context c, boolean micPhones, Progresso p) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        boolean sco = micPhones && ligarSco(am);
        if (sco) dormir(900);
        int fonte = sco ? MediaRecorder.AudioSource.VOICE_COMMUNICATION
                : MediaRecorder.AudioSource.VOICE_RECOGNITION;
        int min = AudioRecord.getMinBufferSize(TAXA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord ar = null;
        try {
            ar = new AudioRecord(fonte, TAXA, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, TAXA));
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) return null;
            ar.startRecording();

            short[] bloco = new short[SALTO];
            List<short[]> blocos = new ArrayList<>();
            List<Double> energias = new ArrayList<>();
            double ruido = -1;
            boolean falou = false;
            int silencio = 0, fala = 0, inicio = -1;
            long limite = System.currentTimeMillis() + 5000;

            while (true) {
                int n = ar.read(bloco, 0, bloco.length);
                if (n <= 0) break;
                short[] copia = Arrays.copyOf(bloco, n);
                blocos.add(copia);
                double e = rms(copia);
                energias.add(e);
                int i = blocos.size();
                if (i <= 15) {                                     // primeiros 150 ms: medir o ruído
                    ruido = ruido < 0 ? e : Math.min(ruido, e) * 0.5 + ruido * 0.5;
                    continue;
                }
                double limiar = Math.max(ruido * 3.0, 150);
                if (!falou) {
                    if (e > limiar) {
                        fala++;
                        if (fala >= 4) {                           // 40 ms seguidos de som
                            falou = true;
                            inicio = Math.max(0, i - 15);
                            if (p != null) p.falaDetectada();
                        }
                    } else fala = 0;
                    if (System.currentTimeMillis() > limite) break;
                } else {
                    silencio = e > limiar * 0.6 ? 0 : silencio + 1;
                    if (silencio >= 70) break;                     // 0,7 s de silêncio
                    if (i - inicio > 350) break;                   // 3,5 s de fala
                }
            }
            if (!falou) return null;
            int fim = Math.max(inicio + 20, blocos.size() - silencio + 10);
            fim = Math.min(fim, blocos.size());
            short[] out = new short[(fim - inicio) * SALTO];
            int pos = 0;
            for (int k = inicio; k < fim; k++) {
                short[] b = blocos.get(k);
                System.arraycopy(b, 0, out, pos, Math.min(b.length, out.length - pos));
                pos += b.length;
            }
            return out;
        } catch (Exception e) {
            return null;
        } finally {
            if (ar != null) {
                try { ar.stop(); } catch (Exception ignored) {}
                ar.release();
            }
            if (sco) desligarSco(am);
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean ligarSco(AudioManager am) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                    if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                        return am.setCommunicationDevice(d);
                    }
                }
                return false;
            } else {
                if (!am.isBluetoothScoAvailableOffCall()) return false;
                am.startBluetoothSco();
                am.setBluetoothScoOn(true);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static void desligarSco(AudioManager am) {
        try {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice();
            else { am.setBluetoothScoOn(false); am.stopBluetoothSco(); }
        } catch (Exception ignored) {}
    }

    private static double rms(short[] s) {
        double t = 0;
        for (short v : s) t += (double) v * v;
        return Math.sqrt(t / Math.max(1, s.length));
    }

    private static void dormir(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    // ================================================================ MFCC

    private static double[][] filtros;
    private static double[] janela;

    private static synchronized void preparar() {
        if (filtros != null) return;
        janela = new double[FRAME];
        for (int i = 0; i < FRAME; i++) janela[i] = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (FRAME - 1));
        double melMin = mel(80), melMax = mel(7600);
        double[] pts = new double[NMEL + 2];
        for (int i = 0; i < pts.length; i++) {
            double m = melMin + (melMax - melMin) * i / (NMEL + 1);
            pts[i] = Math.floor((NFFT + 1) * hz(m) / TAXA);
        }
        filtros = new double[NMEL][NFFT / 2 + 1];
        for (int f = 0; f < NMEL; f++) {
            for (int k = (int) pts[f]; k < pts[f + 1]; k++)
                filtros[f][k] = (k - pts[f]) / Math.max(1, pts[f + 1] - pts[f]);
            for (int k = (int) pts[f + 1]; k < pts[f + 2]; k++)
                filtros[f][k] = (pts[f + 2] - k) / Math.max(1, pts[f + 2] - pts[f + 1]);
        }
    }

    private static double mel(double hz) { return 2595 * Math.log10(1 + hz / 700); }
    private static double hz(double mel) { return 700 * (Math.pow(10, mel / 2595) - 1); }

    /** Impressão do som: uma linha de 12 números por cada 10 ms, normalizada. */
    public static float[][] impressao(short[] som) {
        preparar();
        int nFrames = (som.length - FRAME) / SALTO + 1;
        if (nFrames < 5) return null;
        double[] x = new double[som.length];
        x[0] = som[0];
        for (int i = 1; i < som.length; i++) x[i] = som[i] - 0.97 * som[i - 1];

        float[][] out = new float[nFrames][NCEP - 1];
        double[] re = new double[NFFT], im = new double[NFFT];
        double[] logMel = new double[NMEL];
        for (int f = 0; f < nFrames; f++) {
            Arrays.fill(re, 0);
            Arrays.fill(im, 0);
            for (int i = 0; i < FRAME; i++) re[i] = x[f * SALTO + i] * janela[i];
            fft(re, im);
            for (int m = 0; m < NMEL; m++) {
                double s = 0;
                for (int k = 0; k <= NFFT / 2; k++) {
                    if (filtros[m][k] == 0) continue;
                    s += filtros[m][k] * (re[k] * re[k] + im[k] * im[k]);
                }
                logMel[m] = Math.log(s + 1e-6);
            }
            for (int c = 1; c < NCEP; c++) {      // salta c0 (volume)
                double s = 0;
                for (int m = 0; m < NMEL; m++) s += logMel[m] * Math.cos(Math.PI * c * (m + 0.5) / NMEL);
                out[f][c - 1] = (float) s;
            }
        }
        // retirar a média (compensa diferenças de microfone)
        for (int c = 0; c < NCEP - 1; c++) {
            double media = 0;
            for (float[] fr : out) media += fr[c];
            media /= nFrames;
            for (float[] fr : out) fr[c] -= (float) media;
        }
        return out;
    }

    private static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int j = 0; j < len / 2; j++) {
                    int a = i + j, b = i + j + len / 2;
                    double ur = re[a], ui = im[a];
                    double vr = re[b] * cr - im[b] * ci, vi = re[b] * ci + im[b] * cr;
                    re[a] = ur + vr; im[a] = ui + vi;
                    re[b] = ur - vr; im[b] = ui - vi;
                    double ncr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = ncr;
                }
            }
        }
    }

    // ================================================================ comparação (DTW)

    public static double distancia(float[][] a, float[][] b) {
        int n = a.length, m = b.length;
        double inf = Double.MAX_VALUE / 4;
        double[] prev = new double[m + 1], cur = new double[m + 1];
        Arrays.fill(prev, inf);
        prev[0] = 0;
        int faixa = Math.max(Math.abs(n - m) + 10, Math.max(n, m) / 3);
        for (int i = 1; i <= n; i++) {
            Arrays.fill(cur, inf);
            int jIni = Math.max(1, i * m / n - faixa), jFim = Math.min(m, i * m / n + faixa);
            for (int j = jIni; j <= jFim; j++) {
                double d = 0;
                float[] x = a[i - 1], y = b[j - 1];
                for (int k = 0; k < x.length; k++) { double t = x[k] - y[k]; d += t * t; }
                d = Math.sqrt(d);
                cur[j] = d + Math.min(prev[j - 1], Math.min(prev[j], cur[j - 1]));
            }
            double[] t = prev; prev = cur; cur = t;
        }
        return prev[m] / (n + m);
    }

    // ================================================================ guardar gravações

    private static File pasta(Context c) {
        File d = new File(c.getFilesDir(), "vozes");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static String chave(String link) {
        return Integer.toHexString(link.trim().hashCode());
    }

    public static List<float[][]> modelos(Context c, String link) {
        List<float[][]> l = new ArrayList<>();
        File[] fs = pasta(c).listFiles((d, n) -> n.startsWith(chave(link) + "_"));
        if (fs == null) return l;
        Arrays.sort(fs);
        for (File f : fs) {
            try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
                int n = in.readInt(), k = in.readInt();
                float[][] m = new float[n][k];
                for (int i = 0; i < n; i++) for (int j = 0; j < k; j++) m[i][j] = in.readFloat();
                l.add(m);
            } catch (Exception ignored) {}
        }
        return l;
    }

    public static int quantos(Context c, String link) {
        File[] fs = pasta(c).listFiles((d, n) -> n.startsWith(chave(link) + "_"));
        return fs == null ? 0 : fs.length;
    }

    public static void guardar(Context c, String link, float[][] m) throws Exception {
        File f = new File(pasta(c), chave(link) + "_" + System.currentTimeMillis() + ".bin");
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(f))) {
            out.writeInt(m.length);
            out.writeInt(m[0].length);
            for (float[] fr : m) for (float v : fr) out.writeFloat(v);
        }
    }

    public static void apagar(Context c, String link) {
        File[] fs = pasta(c).listFiles((d, n) -> n.startsWith(chave(link) + "_"));
        if (fs != null) for (File f : fs) f.delete();
    }

    public static boolean haModelos(Context c) {
        String[] n = pasta(c).list();
        return n != null && n.length > 0;
    }

    // ================================================================ decidir

    public static final class Resultado {
        public Dados.Projecto projecto;
        public double distancia, limite;
    }

    /** Escolhe o projecto mais parecido com o que foi dito, ou devolve projecto=null. */
    public static Resultado escolher(Context c, List<Dados.Projecto> lista, float[][] dito) {
        Resultado r = new Resultado();
        r.distancia = Double.MAX_VALUE;
        double segunda = Double.MAX_VALUE;
        for (Dados.Projecto p : lista) {
            List<float[][]> ms = modelos(c, p.link);
            if (ms.isEmpty()) continue;
            double[] ds = new double[ms.size()];
            for (int i = 0; i < ms.size(); i++) ds[i] = distancia(dito, ms.get(i));
            Arrays.sort(ds);
            double d = ds.length >= 2 ? (ds[0] + ds[1]) / 2 : ds[0];   // média das 2 mais parecidas
            if (d < r.distancia) {
                segunda = r.distancia;
                r.distancia = d;
                r.projecto = p;
                r.limite = limite(ms);
            } else if (d < segunda) {
                segunda = d;
            }
        }
        if (r.projecto != null && r.distancia > r.limite) r.projecto = null;
        return r;
    }

    /** Até onde uma gravação nova pode ser diferente das guardadas e ainda contar. */
    private static double limite(List<float[][]> ms) {
        if (ms.size() < 2) return 9e9;
        double soma = 0;
        int n = 0;
        for (int i = 0; i < ms.size(); i++)
            for (int j = i + 1; j < ms.size(); j++) { soma += distancia(ms.get(i), ms.get(j)); n++; }
        return Math.max(soma / n * 1.8, soma / n + 3.0);
    }
}
