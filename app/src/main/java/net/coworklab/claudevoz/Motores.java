package net.coworklab.claudevoz;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.List;

/**
 * Lista de combinações (motor de voz + língua) a experimentar.
 * Lembra-se da última que funcionou e começa sempre por ela.
 */
public final class Motores {

    public static final class Combo {
        public final ComponentName motor; // null = predefinido do sistema
        public final String lingua;       // null = língua do telemóvel
        Combo(ComponentName m, String l) { motor = m; lingua = l; }

        public String descricao() {
            String m = motor == null ? "predefinido" : motor.getPackageName().replace("com.google.android.", "google ");
            return m + " / " + (lingua == null ? "língua do telemóvel" : lingua);
        }
    }

    private Motores() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("claudevoz", Context.MODE_PRIVATE);
    }

    public static List<ComponentName> servicos(Context c) {
        List<ComponentName> google = new ArrayList<>();
        List<ComponentName> outros = new ArrayList<>();
        try {
            List<ResolveInfo> ri = c.getPackageManager().queryIntentServices(
                    new Intent(RecognitionService.SERVICE_INTERFACE), 0);
            for (ResolveInfo r : ri) {
                ComponentName cn = new ComponentName(r.serviceInfo.packageName, r.serviceInfo.name);
                if (r.serviceInfo.packageName.startsWith("com.google")) google.add(cn); else outros.add(cn);
            }
        } catch (Exception ignored) {}
        google.addAll(outros);
        return google;
    }

    public static List<Combo> combos(Context c) {
        List<Combo> l = new ArrayList<>();
        String[] linguas = {"pt-PT", "pt-BR", null};
        List<ComponentName> sv = servicos(c);
        for (String lg : linguas) {
            for (ComponentName cn : sv) l.add(new Combo(cn, lg));
        }
        l.add(new Combo(null, null));
        return l;
    }

    /** Índice por onde começar: a última combinação que funcionou. */
    public static int inicio(Context c, int total) {
        int i = prefs(c).getInt("combo_ok", 0);
        return (i >= 0 && i < total) ? i : 0;
    }

    public static void funcionou(Context c, int i, Combo combo) {
        prefs(c).edit().putInt("combo_ok", i).putString("combo_ok_txt", combo.descricao()).apply();
    }

    public static String descricaoOk(Context c) {
        return prefs(c).getString("combo_ok_txt", "");
    }

    public static SpeechRecognizer criar(Context c, Combo combo) {
        return combo.motor == null
                ? SpeechRecognizer.createSpeechRecognizer(c)
                : SpeechRecognizer.createSpeechRecognizer(c, combo.motor);
    }

    public static Intent pedido(Context c, Combo combo) {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        if (combo.lingua != null) {
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, combo.lingua);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, combo.lingua);
        }
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, c.getPackageName());
        return i;
    }
}
