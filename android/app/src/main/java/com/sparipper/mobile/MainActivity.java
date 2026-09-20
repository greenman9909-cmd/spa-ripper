package com.sparipper.mobile;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

public class MainActivity extends Activity {
    private EditText urlInput;
    private Button cloneButton;
    private Button copyPathButton;
    private Button startServerButton;
    private Button openSiteButton;
    private Button stopServerButton;
    private ProgressBar progressBar;
    private TextView statusText;
    private TextView logText;

    private String lastOutputPath;
    private File lastOutputDir;
    private LocalSpaServer localServer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));

        TextView title = new TextView(this);
        title.setText("SPA-Ripper");
        title.setTextSize(26);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Clone and run modern SPA frontends directly on Android.");
        subtitle.setTextSize(14);
        subtitle.setPadding(0, dp(4), 0, dp(12));
        root.addView(subtitle);

        TextView notice = new TextView(this);
        notice.setText("Use only on sites you own or have permission to archive.");
        notice.setTextSize(12);
        notice.setPadding(0, 0, 0, dp(14));
        root.addView(notice);

        urlInput = new EditText(this);
        urlInput.setHint("https://example.com/");
        urlInput.setSingleLine(true);
        urlInput.setText("https://");
        root.addView(
            urlInput,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        );

        LinearLayout cloneActions = new LinearLayout(this);
        cloneActions.setOrientation(LinearLayout.HORIZONTAL);
        cloneActions.setPadding(0, dp(10), 0, dp(6));

        cloneButton = new Button(this);
        cloneButton.setText("Clone Frontend");
        cloneActions.addView(
            cloneButton,
            new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        );

        copyPathButton = new Button(this);
        copyPathButton.setText("Copy Path");
        copyPathButton.setEnabled(false);
        cloneActions.addView(copyPathButton);

        root.addView(cloneActions);

        LinearLayout serverActions = new LinearLayout(this);
        serverActions.setOrientation(LinearLayout.HORIZONTAL);
        serverActions.setPadding(0, 0, 0, dp(10));

        startServerButton = new Button(this);
        startServerButton.setText("Start Localhost");
        startServerButton.setEnabled(false);
        serverActions.addView(
            startServerButton,
            new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        );

        openSiteButton = new Button(this);
        openSiteButton.setText("Open Site");
        openSiteButton.setEnabled(false);
        serverActions.addView(
            openSiteButton,
            new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        );

        stopServerButton = new Button(this);
        stopServerButton.setText("Stop");
        stopServerButton.setEnabled(false);
        serverActions.addView(stopServerButton);

        root.addView(serverActions);

        progressBar = new ProgressBar(this);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar);

        statusText = new TextView(this);
        statusText.setText("Ready");
        statusText.setTextIsSelectable(true);
        statusText.setPadding(0, dp(8), 0, dp(8));
        root.addView(statusText);

        ScrollView scroll = new ScrollView(this);
        logText = new TextView(this);
        logText.setTextSize(12);
        logText.setTextIsSelectable(true);
        logText.setPadding(dp(10), dp(10), dp(10), dp(10));
        scroll.addView(logText);

        root.addView(
            scroll,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        );

        setContentView(root);

        cloneButton.setOnClickListener(v -> startClone());
        copyPathButton.setOnClickListener(v -> copyOutputPath());
        startServerButton.setOnClickListener(v -> startLocalhost());
        openSiteButton.setOnClickListener(v -> openLocalhost());
        stopServerButton.setOnClickListener(v -> stopLocalhost());
    }

    private void startClone() {
        String rawUrl = urlInput.getText().toString().trim();
        if (rawUrl.isEmpty() || rawUrl.equals("https://")) {
            Toast.makeText(this, "Enter a website URL.", Toast.LENGTH_SHORT).show();
            return;
        }

        stopLocalhost();

        cloneButton.setEnabled(false);
        copyPathButton.setEnabled(false);
        startServerButton.setEnabled(false);
        openSiteButton.setEnabled(false);
        stopServerButton.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        statusText.setText("Cloning…");
        logText.setText("");
        lastOutputPath = null;
        lastOutputDir = null;

        SpaRipperEngine engine = new SpaRipperEngine(this);

        new Thread(() -> {
            try {
                engine.cloneSite(rawUrl, new SpaRipperEngine.Callback() {
                    @Override
                    public void onLog(String message) {
                        runOnUiThread(() -> appendLog(message));
                    }

                    @Override
                    public void onDone(File outputDir, int files, int failures) {
                        runOnUiThread(() -> {
                            lastOutputDir = outputDir;
                            lastOutputPath = outputDir.getAbsolutePath();
                            progressBar.setVisibility(View.GONE);
                            cloneButton.setEnabled(true);
                            copyPathButton.setEnabled(true);
                            startServerButton.setEnabled(true);

                            statusText.setText(
                                "Done — " + files + " files, " + failures +
                                " failed\nOutput: " + lastOutputPath
                            );

                            appendLog(
                                "\n[✓] Clone complete\n" +
                                "[✓] " + lastOutputPath + "\n" +
                                "[*] Tap Start Localhost to run it on your phone.\n"
                            );
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                            progressBar.setVisibility(View.GONE);
                            cloneButton.setEnabled(true);
                            statusText.setText("Clone failed");
                            appendLog("\n[!] " + message + "\n");
                            Toast.makeText(
                                MainActivity.this,
                                message,
                                Toast.LENGTH_LONG
                            ).show();
                        });
                    }
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    cloneButton.setEnabled(true);
                    statusText.setText("Clone failed");
                    appendLog("\n[!] " + ex.getMessage() + "\n");
                });
            }
        }).start();
    }

    private void startLocalhost() {
        if (lastOutputDir == null || !lastOutputDir.exists()) {
            Toast.makeText(this, "Clone a frontend first.", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            if (localServer != null && localServer.isRunning()) {
                openLocalhost();
                return;
            }

            localServer = new LocalSpaServer(lastOutputDir);
            String url = localServer.start();

            startServerButton.setEnabled(false);
            openSiteButton.setEnabled(true);
            stopServerButton.setEnabled(true);

            statusText.setText(
                "Localhost running\n" +
                url + "\n" +
                "Serving: " + lastOutputDir.getAbsolutePath()
            );

            appendLog(
                "\n[✓] Localhost started\n" +
                "[✓] " + url + "\n"
            );

            Toast.makeText(
                this,
                "Localhost started on " + url,
                Toast.LENGTH_SHORT
            ).show();
        } catch (Exception ex) {
            appendLog("\n[!] Localhost error: " + ex.getMessage() + "\n");
            Toast.makeText(
                this,
                "Could not start localhost: " + ex.getMessage(),
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void openLocalhost() {
        if (localServer == null || !localServer.isRunning()) {
            Toast.makeText(this, "Start localhost first.", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            Intent intent = new Intent(
                Intent.ACTION_VIEW,
                Uri.parse(localServer.getUrl())
            );
            startActivity(intent);
        } catch (Exception ex) {
            Toast.makeText(
                this,
                "Could not open browser.",
                Toast.LENGTH_SHORT
            ).show();
        }
    }

    private void stopLocalhost() {
        if (localServer != null) {
            boolean wasRunning = localServer.isRunning();
            localServer.stop();
            localServer = null;

            if (wasRunning) {
                appendLog("\n[*] Localhost stopped\n");
            }
        }

        openSiteButton.setEnabled(false);
        stopServerButton.setEnabled(false);
        startServerButton.setEnabled(lastOutputDir != null && lastOutputDir.exists());
    }

    private void appendLog(String text) {
        logText.append(text);
    }

    private void copyOutputPath() {
        if (lastOutputPath == null) {
            return;
        }

        ClipboardManager clipboard =
            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(
            ClipData.newPlainText("SPA-Ripper output", lastOutputPath)
        );
        Toast.makeText(this, "Output path copied.", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        if (localServer != null) {
            localServer.stop();
            localServer = null;
        }
        super.onDestroy();
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
