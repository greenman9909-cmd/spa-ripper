package com.sparipper.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(8, 12, 18);
    private static final int SURFACE = Color.rgb(17, 24, 39);
    private static final int SURFACE_2 = Color.rgb(24, 33, 48);
    private static final int BORDER = Color.rgb(43, 55, 72);
    private static final int TEXT = Color.rgb(248, 250, 252);
    private static final int MUTED = Color.rgb(148, 163, 184);
    private static final int ACCENT = Color.rgb(20, 184, 166);
    private static final int ACCENT_DARK = Color.rgb(15, 118, 110);
    private static final int DANGER = Color.rgb(239, 68, 68);
    private static final int SUCCESS = Color.rgb(34, 197, 94);
    private static final int DISABLED = Color.rgb(42, 51, 65);

    private static final int REQ_SAVE_ZIP = 4101;
    private static final int REQ_EXPORT_FOLDER = 4102;
    private static final int REQ_SAVE_FILE = 4103;

    private EditText urlInput;
    private Button cloneButton;
    private Button cloneRunButton;
    private Button copyPathButton;
    private Button browseFilesButton;
    private Button extractFolderButton;
    private Button saveZipButton;
    private Button startServerButton;
    private Button openSiteButton;
    private Button stopServerButton;
    private ProgressBar progressBar;
    private TextView statusTitle;
    private TextView statusText;
    private TextView logText;
    private ScrollView logScroll;

    private String lastOutputPath;
    private File lastOutputDir;
    private File pendingZipFile;
    private File pendingSingleFile;
    private LocalSpaServer localServer;
    private boolean autoRunAfterClone;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        page.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(28));
        page.addView(root);

        root.addView(buildHeader());
        root.addView(space(18));
        root.addView(buildTargetCard());
        root.addView(space(12));
        root.addView(buildStatusCard());
        root.addView(space(12));
        root.addView(buildServerCard());
        root.addView(space(12));
        root.addView(buildLogCard());

        setContentView(page);
    }

    private View buildHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_logo_mark);
        logo.setPadding(dp(13), dp(13), dp(13), dp(13));
        logo.setBackground(roundRect(ACCENT, 17, 0, 0));
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(58), dp(58));
        logoParams.setMarginEnd(dp(14));
        row.addView(logo, logoParams);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);

        TextView title = label("SPA-Ripper", 27, TEXT, Typeface.BOLD);
        TextView subtitle = label("Clone • inspect • export • run locally", 13, MUTED, Typeface.NORMAL);
        subtitle.setPadding(0, dp(3), 0, 0);

        titles.addView(title);
        titles.addView(subtitle);
        row.addView(
            titles,
            new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        );

        TextView badge = label("v1.2", 11, ACCENT, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(10), dp(6), dp(10), dp(6));
        badge.setBackground(roundRect(Color.rgb(12, 54, 51), 99, 1, ACCENT_DARK));
        row.addView(badge);

        return row;
    }

    private View buildTargetCard() {
        LinearLayout card = card();

        TextView eyebrow = label("TARGET WEBSITE", 11, ACCENT, Typeface.BOLD);
        card.addView(eyebrow);

        TextView helper = label(
            "Enter a URL. SPA-Ripper will discover and download same-origin frontend assets.",
            13,
            MUTED,
            Typeface.NORMAL
        );
        helper.setPadding(0, dp(5), 0, dp(12));
        card.addView(helper);

        urlInput = new EditText(this);
        urlInput.setHint("https://example.com");
        urlInput.setHintTextColor(Color.rgb(100, 116, 139));
        urlInput.setTextColor(TEXT);
        urlInput.setTextSize(16);
        urlInput.setSingleLine(true);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setPadding(dp(14), dp(13), dp(14), dp(13));
        urlInput.setBackground(roundRect(SURFACE_2, 14, 1, BORDER));
        card.addView(
            urlInput,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            )
        );

        cloneButton = actionButton("Clone frontend", ACCENT, Color.BLACK);
        LinearLayout.LayoutParams cloneParams = fullWidthButtonParams();
        cloneParams.topMargin = dp(12);
        card.addView(cloneButton, cloneParams);

        cloneRunButton = actionButton("Clone & run localhost", SURFACE_2, TEXT);
        LinearLayout.LayoutParams cloneRunParams = fullWidthButtonParams();
        cloneRunParams.topMargin = dp(8);
        card.addView(cloneRunButton, cloneRunParams);

        TextView note = label(
            "Use only on websites you own or have permission to archive.",
            11,
            MUTED,
            Typeface.NORMAL
        );
        note.setPadding(0, dp(10), 0, 0);
        card.addView(note);

        cloneButton.setOnClickListener(v -> startClone(false));
        cloneRunButton.setOnClickListener(v -> startClone(true));

        return card;
    }

    private View buildStatusCard() {
        LinearLayout card = card();

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView section = label("CLONE OUTPUT", 11, MUTED, Typeface.BOLD);
        top.addView(
            section,
            new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        );

        copyPathButton = smallButton("Copy path");
        copyPathButton.setEnabled(false);
        copyPathButton.setOnClickListener(v -> copyOutputPath());
        top.addView(copyPathButton);

        card.addView(top);

        statusTitle = label("Ready", 20, TEXT, Typeface.BOLD);
        statusTitle.setPadding(0, dp(10), 0, dp(3));
        card.addView(statusTitle);

        statusText = label(
            "Paste a website URL to begin.",
            13,
            MUTED,
            Typeface.NORMAL
        );
        statusText.setTextIsSelectable(true);
        card.addView(statusText);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(4)
        );
        progressParams.topMargin = dp(14);
        card.addView(progressBar, progressParams);

        LinearLayout fileActions = new LinearLayout(this);
        fileActions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams fileActionParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        fileActionParams.topMargin = dp(14);

        browseFilesButton = actionButton("Browse", SURFACE_2, TEXT);
        extractFolderButton = actionButton("Extract", SURFACE_2, TEXT);
        saveZipButton = actionButton("Save ZIP", SURFACE_2, TEXT);
        browseFilesButton.setEnabled(false);
        extractFolderButton.setEnabled(false);
        saveZipButton.setEnabled(false);

        fileActions.addView(browseFilesButton, weightedButtonParams(1f, 0));
        fileActions.addView(extractFolderButton, weightedButtonParams(1f, dp(8)));
        fileActions.addView(saveZipButton, weightedButtonParams(1f, dp(8)));
        card.addView(fileActions, fileActionParams);

        TextView outputHint = label(
            "Browse opens the real cloned files. Extract and Save ZIP let you choose a normal folder in Android Files.",
            11,
            MUTED,
            Typeface.NORMAL
        );
        outputHint.setPadding(0, dp(9), 0, 0);
        card.addView(outputHint);

        browseFilesButton.setOnClickListener(v -> browseCloneFiles());
        extractFolderButton.setOnClickListener(v -> chooseExtractDestination());
        saveZipButton.setOnClickListener(v -> prepareZipExport());

        return card;
    }

    private View buildServerCard() {
        LinearLayout card = card();

        TextView section = label("LOCAL SERVER", 11, MUTED, Typeface.BOLD);
        card.addView(section);

        TextView helper = label(
            "Serve the cloned frontend directly on this phone.",
            13,
            MUTED,
            Typeface.NORMAL
        );
        helper.setPadding(0, dp(5), 0, dp(10));
        card.addView(helper);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        startServerButton = actionButton("Start", ACCENT, Color.BLACK);
        startServerButton.setEnabled(false);

        openSiteButton = actionButton("Open site", SURFACE_2, TEXT);
        openSiteButton.setEnabled(false);

        stopServerButton = actionButton("Stop", Color.rgb(69, 26, 31), Color.rgb(254, 202, 202));
        stopServerButton.setEnabled(false);

        row.addView(startServerButton, weightedButtonParams(1f, 0));
        row.addView(openSiteButton, weightedButtonParams(1f, dp(8)));
        row.addView(stopServerButton, weightedButtonParams(0.75f, dp(8)));
        card.addView(row);

        TextView hint = label(
            "Usually available at http://127.0.0.1:8080",
            11,
            MUTED,
            Typeface.NORMAL
        );
        hint.setPadding(0, dp(9), 0, 0);
        card.addView(hint);

        startServerButton.setOnClickListener(v -> startLocalhost());
        openSiteButton.setOnClickListener(v -> openLocalhost());
        stopServerButton.setOnClickListener(v -> stopLocalhost());

        return card;
    }

    private View buildLogCard() {
        LinearLayout card = card();

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView section = label("LIVE ACTIVITY", 11, MUTED, Typeface.BOLD);
        header.addView(
            section,
            new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        );

        TextView live = label("● LIVE", 10, SUCCESS, Typeface.BOLD);
        header.addView(live);

        card.addView(header);

        logScroll = new ScrollView(this);
        logScroll.setFillViewport(true);
        logScroll.setBackground(roundRect(Color.rgb(5, 9, 14), 12, 1, Color.rgb(31, 41, 55)));

        logText = new TextView(this);
        logText.setText("Waiting for a clone…");
        logText.setTextColor(Color.rgb(180, 195, 214));
        logText.setTextSize(11);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextIsSelectable(true);
        logText.setPadding(dp(12), dp(12), dp(12), dp(12));
        logScroll.addView(logText);

        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(300)
        );
        logParams.topMargin = dp(10);
        card.addView(logScroll, logParams);

        return card;
    }

    private void startClone(boolean runAfter) {
        String rawUrl = urlInput.getText().toString().trim();
        if (rawUrl.isEmpty()) {
            Toast.makeText(this, "Enter a website URL.", Toast.LENGTH_SHORT).show();
            return;
        }

        autoRunAfterClone = runAfter;
        stopLocalhost();

        setCloneControlsEnabled(false);
        setOutputActionsEnabled(false);
        startServerButton.setEnabled(false);
        openSiteButton.setEnabled(false);
        stopServerButton.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        statusTitle.setText(runAfter ? "Cloning, then launching…" : "Cloning frontend…");
        statusTitle.setTextColor(ACCENT);
        statusText.setText("Discovering assets and saving them to your phone.");
        logText.setText("");
        lastOutputPath = null;
        lastOutputDir = null;
        pendingZipFile = null;
        pendingSingleFile = null;

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
                            setCloneControlsEnabled(true);
                            setOutputActionsEnabled(true);
                            startServerButton.setEnabled(true);

                            statusTitle.setText("Clone complete");
                            statusTitle.setTextColor(SUCCESS);
                            statusText.setText(
                                files + " files saved • " + failures + " failed\n" +
                                "Tap Browse, Extract, or Save ZIP below."
                            );

                            appendLog(
                                "\n[✓] Clone complete\n" +
                                "[✓] Private working folder: " + lastOutputPath + "\n" +
                                "[✓] Use Browse / Extract / Save ZIP to access the files.\n"
                            );

                            if (autoRunAfterClone) {
                                startLocalhost();
                                if (localServer != null && localServer.isRunning()) {
                                    openLocalhost();
                                }
                            }
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                            progressBar.setVisibility(View.GONE);
                            setCloneControlsEnabled(true);
                            setOutputActionsEnabled(false);
                            statusTitle.setText("Clone failed");
                            statusTitle.setTextColor(DANGER);
                            statusText.setText(message);
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
                    setCloneControlsEnabled(true);
                    setOutputActionsEnabled(false);
                    statusTitle.setText("Clone failed");
                    statusTitle.setTextColor(DANGER);
                    statusText.setText(ex.getMessage());
                    appendLog("\n[!] " + ex.getMessage() + "\n");
                });
            }
        }).start();
    }

    private void setCloneControlsEnabled(boolean enabled) {
        cloneButton.setEnabled(enabled);
        cloneRunButton.setEnabled(enabled);
        urlInput.setEnabled(enabled);
    }

    private void setOutputActionsEnabled(boolean enabled) {
        copyPathButton.setEnabled(enabled);
        browseFilesButton.setEnabled(enabled);
        extractFolderButton.setEnabled(enabled);
        saveZipButton.setEnabled(enabled);
    }

    private void browseCloneFiles() {
        if (!hasClone()) {
            Toast.makeText(this, "Clone a frontend first.", Toast.LENGTH_SHORT).show();
            return;
        }
        browseDirectory(lastOutputDir);
    }

    private void browseDirectory(File directory) {
        File[] children = directory.listFiles();
        if (children == null) {
            Toast.makeText(this, "Could not read this folder.", Toast.LENGTH_SHORT).show();
            return;
        }

        Arrays.sort(children, Comparator
            .comparing((File file) -> !file.isDirectory())
            .thenComparing(file -> file.getName().toLowerCase()));

        String[] labels = new String[children.length];
        for (int i = 0; i < children.length; i++) {
            File child = children[i];
            labels[i] = child.isDirectory()
                ? "📁  " + child.getName()
                : "📄  " + child.getName() + "  •  " + CloneExportUtils.humanSize(child.length());
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(relativeTitle(directory))
            .setItems(labels, (dialog, which) -> {
                File selected = children[which];
                if (selected.isDirectory()) {
                    browseDirectory(selected);
                } else {
                    showFileActions(selected);
                }
            })
            .setNegativeButton("Close", null);

        if (!sameFile(directory, lastOutputDir)) {
            builder.setNeutralButton("Up", (dialog, which) -> {
                File parent = directory.getParentFile();
                if (parent != null && isInsideClone(parent)) {
                    browseDirectory(parent);
                }
            });
        }

        if (children.length == 0) {
            builder.setMessage("This folder is empty.");
        }

        builder.show();
    }

    private void showFileActions(File file) {
        String[] actions = {"Open file", "Save a copy", "Copy path"};
        new AlertDialog.Builder(this)
            .setTitle(file.getName())
            .setMessage(
                CloneExportUtils.humanSize(file.length()) + "\n" +
                relativePath(file)
            )
            .setItems(actions, (dialog, which) -> {
                if (which == 0) {
                    openFile(file);
                } else if (which == 1) {
                    chooseSingleFileDestination(file);
                } else {
                    copyText("SPA-Ripper file", file.getAbsolutePath(), "File path copied.");
                }
            })
            .setNegativeButton("Close", null)
            .show();
    }

    private void openFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(
                this,
                getPackageName() + ".files",
                file
            );

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, CloneExportUtils.mimeFor(file));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Open " + file.getName()));
        } catch (Exception ex) {
            Toast.makeText(
                this,
                "No app could open this file. Use Save a copy instead.",
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void chooseSingleFileDestination(File file) {
        pendingSingleFile = file;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(CloneExportUtils.mimeFor(file));
        intent.putExtra(Intent.EXTRA_TITLE, file.getName());
        startActivityForResult(intent, REQ_SAVE_FILE);
    }

    private void chooseExtractDestination() {
        if (!hasClone()) {
            Toast.makeText(this, "Clone a frontend first.", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION |
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        );
        startActivityForResult(intent, REQ_EXPORT_FOLDER);
    }

    private void prepareZipExport() {
        if (!hasClone()) {
            Toast.makeText(this, "Clone a frontend first.", Toast.LENGTH_SHORT).show();
            return;
        }

        saveZipButton.setEnabled(false);
        statusTitle.setText("Preparing ZIP…");
        statusTitle.setTextColor(ACCENT);

        new Thread(() -> {
            try {
                File zip = CloneExportUtils.createZip(this, lastOutputDir);
                runOnUiThread(() -> {
                    pendingZipFile = zip;
                    saveZipButton.setEnabled(true);
                    statusTitle.setText("ZIP ready");
                    statusTitle.setTextColor(SUCCESS);

                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/zip");
                    intent.putExtra(Intent.EXTRA_TITLE, lastOutputDir.getName() + ".zip");
                    startActivityForResult(intent, REQ_SAVE_ZIP);
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    saveZipButton.setEnabled(true);
                    statusTitle.setText("ZIP export failed");
                    statusTitle.setTextColor(DANGER);
                    statusText.setText(ex.getMessage());
                    Toast.makeText(this, ex.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        Uri destination = data.getData();

        if (requestCode == REQ_SAVE_ZIP && pendingZipFile != null) {
            File zip = pendingZipFile;
            pendingZipFile = null;
            copyFileToChosenUri(zip, destination, "ZIP saved");
            return;
        }

        if (requestCode == REQ_SAVE_FILE && pendingSingleFile != null) {
            File source = pendingSingleFile;
            pendingSingleFile = null;
            copyFileToChosenUri(source, destination, "File saved");
            return;
        }

        if (requestCode == REQ_EXPORT_FOLDER && hasClone()) {
            try {
                getContentResolver().takePersistableUriPermission(
                    destination,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                );
            } catch (Exception ignored) {
            }
            exportFolderToTree(destination);
        }
    }

    private void copyFileToChosenUri(File source, Uri destination, String successText) {
        statusTitle.setText("Saving " + source.getName() + "…");
        statusTitle.setTextColor(ACCENT);

        new Thread(() -> {
            try {
                CloneExportUtils.copyFileToUri(getContentResolver(), source, destination);
                runOnUiThread(() -> {
                    statusTitle.setText(successText);
                    statusTitle.setTextColor(SUCCESS);
                    statusText.setText(source.getName() + " is now in the location you selected.");
                    Toast.makeText(this, successText + ".", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    statusTitle.setText("Save failed");
                    statusTitle.setTextColor(DANGER);
                    statusText.setText(ex.getMessage());
                    Toast.makeText(this, ex.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void exportFolderToTree(Uri treeUri) {
        extractFolderButton.setEnabled(false);
        statusTitle.setText("Extracting clone…");
        statusTitle.setTextColor(ACCENT);
        statusText.setText("Copying the cloned files into the folder you selected.");

        new Thread(() -> {
            try {
                Uri exported = CloneExportUtils.copyDirectoryToTree(
                    getContentResolver(),
                    treeUri,
                    lastOutputDir
                );
                runOnUiThread(() -> {
                    extractFolderButton.setEnabled(true);
                    statusTitle.setText("Extract complete");
                    statusTitle.setTextColor(SUCCESS);
                    statusText.setText(
                        "The full clone was copied to your chosen Files location.\n" +
                        "Folder: " + lastOutputDir.getName()
                    );
                    appendLog("\n[✓] Exported clone folder to " + exported + "\n");
                    Toast.makeText(this, "Clone extracted successfully.", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    extractFolderButton.setEnabled(true);
                    statusTitle.setText("Extract failed");
                    statusTitle.setTextColor(DANGER);
                    statusText.setText(ex.getMessage());
                    Toast.makeText(this, ex.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void startLocalhost() {
        if (!hasClone()) {
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

            statusTitle.setText("Localhost online");
            statusTitle.setTextColor(SUCCESS);
            statusText.setText(url + "\nServing " + lastOutputDir.getName());

            appendLog(
                "\n[✓] Localhost started\n" +
                "[✓] " + url + "\n"
            );
        } catch (Exception ex) {
            statusTitle.setText("Localhost error");
            statusTitle.setTextColor(DANGER);
            statusText.setText(ex.getMessage());
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
            Toast.makeText(this, "Could not open browser.", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopLocalhost() {
        if (localServer != null) {
            boolean wasRunning = localServer.isRunning();
            localServer.stop();
            localServer = null;

            if (wasRunning && logText != null) {
                appendLog("\n[*] Localhost stopped\n");
                if (lastOutputDir != null) {
                    statusTitle.setText("Clone ready");
                    statusTitle.setTextColor(TEXT);
                    statusText.setText("Use Browse, Extract, or Save ZIP to access the clone.");
                }
            }
        }

        if (openSiteButton != null) openSiteButton.setEnabled(false);
        if (stopServerButton != null) stopServerButton.setEnabled(false);
        if (startServerButton != null) {
            startServerButton.setEnabled(hasClone());
        }
    }

    private void appendLog(String text) {
        logText.append(text);
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void copyOutputPath() {
        if (lastOutputPath == null) {
            return;
        }
        copyText("SPA-Ripper output", lastOutputPath, "Output path copied.");
    }

    private void copyText(String label, String value, String toast) {
        ClipboardManager clipboard =
            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
    }

    private boolean hasClone() {
        return lastOutputDir != null && lastOutputDir.exists() && lastOutputDir.isDirectory();
    }

    private boolean sameFile(File left, File right) {
        try {
            return left.getCanonicalFile().equals(right.getCanonicalFile());
        } catch (Exception ex) {
            return left.equals(right);
        }
    }

    private boolean isInsideClone(File file) {
        if (!hasClone()) return false;
        try {
            File root = lastOutputDir.getCanonicalFile();
            File target = file.getCanonicalFile();
            String rootPath = root.getPath();
            String targetPath = target.getPath();
            return targetPath.equals(rootPath) ||
                targetPath.startsWith(rootPath + File.separator);
        } catch (Exception ex) {
            return false;
        }
    }

    private String relativeTitle(File directory) {
        if (sameFile(directory, lastOutputDir)) {
            return lastOutputDir.getName();
        }
        return "…/" + relativePath(directory);
    }

    private String relativePath(File file) {
        try {
            String root = lastOutputDir.getCanonicalPath();
            String path = file.getCanonicalPath();
            if (path.equals(root)) return lastOutputDir.getName();
            if (path.startsWith(root + File.separator)) {
                return path.substring(root.length() + 1);
            }
        } catch (Exception ignored) {
        }
        return file.getName();
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(roundRect(SURFACE, 18, 1, BORDER));
        card.setElevation(dp(2));
        return card;
    }

    private TextView label(String text, float size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans", style));
        view.setLineSpacing(0, 1.08f);
        return view;
    }

    private Button actionButton(String text, int normalColor, int textColor) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(13);
        button.setTextColor(buttonTextColors(textColor));
        button.setTypeface(Typeface.create("sans", Typeface.BOLD));
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setBackground(buttonBackground(normalColor));
        button.setStateListAnimator(null);
        return button;
    }

    private Button smallButton(String text) {
        Button button = actionButton(text, SURFACE_2, TEXT);
        button.setTextSize(11);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(36)
        );
        button.setLayoutParams(params);
        return button;
    }

    private StateListDrawable buttonBackground(int normalColor) {
        StateListDrawable states = new StateListDrawable();
        states.addState(
            new int[]{-android.R.attr.state_enabled},
            roundRect(DISABLED, 13, 1, DISABLED)
        );
        states.addState(
            new int[]{android.R.attr.state_pressed},
            roundRect(darken(normalColor), 13, 1, darken(normalColor))
        );
        states.addState(
            new int[]{},
            roundRect(normalColor, 13, 1, normalColor == SURFACE_2 ? BORDER : normalColor)
        );
        return states;
    }

    private ColorStateList buttonTextColors(int normalColor) {
        return new ColorStateList(
            new int[][]{
                new int[]{-android.R.attr.state_enabled},
                new int[]{}
            },
            new int[]{
                Color.rgb(100, 116, 139),
                normalColor
            }
        );
    }

    private GradientDrawable roundRect(int color, int radiusDp, int strokeDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) {
            drawable.setStroke(dp(strokeDp), strokeColor);
        }
        return drawable;
    }

    private LinearLayout.LayoutParams fullWidthButtonParams() {
        return new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(50)
        );
    }

    private LinearLayout.LayoutParams weightedButtonParams(float weight, int startMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            0,
            dp(48),
            weight
        );
        params.setMarginStart(startMargin);
        return params;
    }

    private View space(int heightDp) {
        View view = new View(this);
        view.setLayoutParams(
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(heightDp)
            )
        );
        return view;
    }

    private int darken(int color) {
        return Color.rgb(
            Math.max(0, (int)(Color.red(color) * 0.82f)),
            Math.max(0, (int)(Color.green(color) * 0.82f)),
            Math.max(0, (int)(Color.blue(color) * 0.82f))
        );
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
