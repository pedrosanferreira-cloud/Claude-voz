package net.coworklab.claudevoz;

import android.app.Activity;
import android.os.Bundle;

/** Atalho: começa logo a ouvir e fecha-se. */
public class OuvirActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        VozService.enviar(this, VozService.ACAO_OUVIR);
        finish();
    }
}
