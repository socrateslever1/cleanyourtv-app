package tv.cleanyour.bridge;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.media.projection.MediaProjectionConfig;
import android.os.Bundle;
import android.os.Build;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Explicit per-session user consent on the TV. No background or silent capture. */
public final class MirrorActivity extends Activity {
    private static final int CAPTURE = 4711;
    private String sessionKey;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sessionKey = getIntent().getStringExtra("session_key");
        if (!BridgeProtocol.isValidHexKey(sessionKey)) {
            finish();
            return;
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(64, 40, 64, 40);
        TextView heading = new TextView(this);
        heading.setText("CleanYourTV Bridge");
        heading.setTextSize(28);
        root.addView(heading);
        TextView description = new TextView(this);
        description.setText("Compartilhar a tela desta TV com o celular autorizado?\n" +
            "A captura só começará com sua autorização e poderá ser interrompida a qualquer momento. " +
            "Imagens protegidas podem permanecer pretas.");
        description.setTextSize(18);
        description.setPadding(0, 22, 0, 22);
        root.addView(description);
        Button start = new Button(this);
        start.setText("Autorizar espelhamento");
        start.setOnClickListener(v -> {
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            if (manager == null) { finish(); return; }
            start.setEnabled(false);
            Intent request = Build.VERSION.SDK_INT >= 34
                ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                : manager.createScreenCaptureIntent();
            startActivityForResult(request, CAPTURE);
        });
        root.addView(start);
        Button cancel = new Button(this);
        cancel.setText("Cancelar");
        cancel.setOnClickListener(v -> finish());
        root.addView(cancel);
        setContentView(root);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        recreate();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != CAPTURE || resultCode != RESULT_OK || data == null ||
            !BridgeProtocol.isValidHexKey(sessionKey)) {
            finish();
            return;
        }
        Intent start = new Intent(this, ProjectionService.class);
        start.putExtra("session_key", sessionKey);
        start.putExtra("projection_result", resultCode);
        start.putExtra("projection_data", data);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(start);
        else startService(start);
        sessionKey = null;
        finish();
    }

    @Override protected void onDestroy() {
        sessionKey = null;
        super.onDestroy();
    }
}
