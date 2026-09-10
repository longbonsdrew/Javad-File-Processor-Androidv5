package com.forsite.javadprocessor;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int PICK_INPUT = 201;
    private static final int PICK_OUTPUT = 202;
    private static final String UPLOAD_URL = "https://app.javad.com/jca/#/dpos/upload";
    private static final String REPORT_URL = "https://app.javad.com/jca/#/dpos/report/list/%d/100/0/d/0";
    private static final int MAX_REPORT_PAGES = 10;

    private WebView web;
    private ScrollView controls;
    private FrameLayout browserPanel;
    private TextView inputPath, outputPath, uploadText, downloadText, status, failedText, log;
    private StripedProgressView uploadBar, downloadBar;
    private Button start, stop;
    private CheckBox reprocess;
    private EditText maxWait;
    private Uri inputTree, outputTree;
    private final List<Observation> observations = new ArrayList<>();
    private final List<Observation> submitted = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private int uploadIndex = 0, downloadIndex = 0, reportPage = 1;
    private int uploadAttempt = 0;
    private int downloadSuccess = 0;
    private long reportDeadline = 0;
    private boolean running = false, stopping = false, selectingForUpload = false;
    private Phase phase = Phase.IDLE;
    private ValueCallback<Uri[]> pendingFileCallback;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        bindViews();

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowContentAccess(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);
        web.addJavascriptInterface(new WebBridge(), "AndroidProcessor");
        web.setWebViewClient(new ProcessorWebClient());
        web.setWebChromeClient(new ProcessorChromeClient());
        web.setDownloadListener(new ReportDownloadListener());

        findViewById(R.id.inputButton).setOnClickListener(v -> chooseTree(PICK_INPUT));
        findViewById(R.id.outputButton).setOnClickListener(v -> chooseTree(PICK_OUTPUT));
        findViewById(R.id.loginButton).setOnClickListener(v -> showBrowser(UPLOAD_URL));
        findViewById(R.id.backToControls).setOnClickListener(v -> showControls());
        start.setOnClickListener(v -> startRun());
        stop.setOnClickListener(v -> { stopping = true; setStatus("Stopping safely after the current step…"); });
        restoreFolders();
    }

    private void bindViews() {
        web = findViewById(R.id.webView); controls = findViewById(R.id.controlScroll);
        browserPanel = findViewById(R.id.browserPanel); inputPath = findViewById(R.id.inputPath);
        outputPath = findViewById(R.id.outputPath); uploadText = findViewById(R.id.uploadText);
        downloadText = findViewById(R.id.downloadText); status = findViewById(R.id.statusText);
        failedText = findViewById(R.id.failedText); log = findViewById(R.id.logText);
        uploadBar = findViewById(R.id.uploadProgress); downloadBar = findViewById(R.id.downloadProgress);
        start = findViewById(R.id.startButton); stop = findViewById(R.id.stopButton);
        reprocess = findViewById(R.id.reprocessCheck); maxWait = findViewById(R.id.maxWait);
    }

    private void chooseTree(int request) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, request);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        getContentResolver().takePersistableUriPermission(uri, flags);
        if (request == PICK_INPUT) { inputTree = uri; inputPath.setText(displayTree(uri)); }
        if (request == PICK_OUTPUT) { outputTree = uri; outputPath.setText(displayTree(uri)); }
        SharedPreferences.Editor edit = getPreferences(MODE_PRIVATE).edit();
        if (inputTree != null) edit.putString("input", inputTree.toString());
        if (outputTree != null) edit.putString("output", outputTree.toString());
        edit.apply();
    }

    private void restoreFolders() {
        SharedPreferences p = getPreferences(MODE_PRIVATE);
        String in = p.getString("input", ""), out = p.getString("output", "");
        if (!in.isEmpty()) { inputTree = Uri.parse(in); inputPath.setText(displayTree(inputTree)); }
        if (!out.isEmpty()) { outputTree = Uri.parse(out); outputPath.setText(displayTree(outputTree)); }
    }

    private String displayTree(Uri uri) {
        String id = DocumentsContract.getTreeDocumentId(uri);
        return id.replace("primary:", "Internal storage/");
    }

    private void startRun() {
        if (inputTree == null || outputTree == null) { toast("Choose both folders first."); return; }
        observations.clear(); submitted.clear(); failures.clear(); uploadIndex = 0; downloadIndex = 0; downloadSuccess = 0;
        scanTree(inputTree, DocumentsContract.getTreeDocumentId(inputTree), observations);
        if (!reprocess.isChecked()) {
            Set<String> completed = new HashSet<>();
            scanTxtNames(outputTree, DocumentsContract.getTreeDocumentId(outputTree), completed);
            observations.removeIf(item -> completed.contains(stem(item.name).toLowerCase(Locale.US)));
        }
        observations.sort((a,b) -> a.name.compareToIgnoreCase(b.name));
        if (observations.isEmpty()) { toast("No .jps observation files were found."); return; }
        running = true; stopping = false; phase = Phase.UPLOAD;
        start.setEnabled(false); stop.setEnabled(true); failedText.setText(""); log.setText("");
        updateProgress(true, 0, observations.size()); updateProgress(false, 0, observations.size());
        appendLog("UPLOAD PHASE — " + observations.size() + " observation file(s)");
        showBrowser(UPLOAD_URL);
    }

    private void scanTree(Uri tree, String parentId, List<Observation> found) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        try (Cursor c = getContentResolver().query(children,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) scanTree(tree, id, found);
                else if (name != null && name.toLowerCase(Locale.US).endsWith(".jps"))
                    found.add(new Observation(name, DocumentsContract.buildDocumentUriUsingTree(tree, id)));
            }
        } catch (Exception e) { appendLog("Folder scan warning: " + e.getMessage()); }
    }

    private void scanTxtNames(Uri tree, String parentId, Set<String> found) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        try (Cursor c = getContentResolver().query(children,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) scanTxtNames(tree, id, found);
                else if (name != null && name.toLowerCase(Locale.US).endsWith(".txt")) found.add(stem(name).toLowerCase(Locale.US));
            }
        } catch (Exception e) { appendLog("Results scan warning: " + e.getMessage()); }
    }

    private void showBrowser(String url) {
        controls.setVisibility(View.GONE); browserPanel.setVisibility(View.VISIBLE);
        if (url != null) web.loadUrl(url);
    }
    private void showControls() { browserPanel.setVisibility(View.GONE); controls.setVisibility(View.VISIBLE); }

    private class ProcessorWebClient extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) { return false; }
        @Override public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            CookieManager.getInstance().flush();
            // Keep JAVAD authentication links in this WebView so the resulting
            // session cookies belong to the processor instead of external Chrome.
            view.evaluateJavascript("(function(){document.querySelectorAll('a[target]').forEach(function(a){a.removeAttribute('target');});window.open=function(u){if(u)location.href=u;return window;};})();", null);
            if (!running && url.contains("#/dpos/upload")) {
                handler.postDelayed(() -> verifyLoginPage(), 3000);
            }
            if (!running || stopping) return;
            if (phase == Phase.UPLOAD && url.contains("#/dpos/upload")) handler.postDelayed(() -> prepareUploadPage(), 5000);
            else if (phase == Phase.DOWNLOAD && url.contains("#/dpos/report")) handler.postDelayed(() -> checkReportPage(), 1800);
        }
    }

    private void verifyLoginPage() {
        String js = "(function(){var text=(document.body.innerText||'');" +
                "if(/drop files/i.test(text))AndroidProcessor.loginReady();" +
                "else AndroidProcessor.loginWaiting();})();";
        web.evaluateJavascript(js, null);
    }

    private class ProcessorChromeClient extends WebChromeClient {
        @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (!running || !selectingForUpload || uploadIndex >= observations.size()) return false;
            if (pendingFileCallback != null) pendingFileCallback.onReceiveValue(null);
            pendingFileCallback = callback; selectingForUpload = false;
            callback.onReceiveValue(new Uri[]{observations.get(uploadIndex).uri}); pendingFileCallback = null;
            return true;
        }
    }

    private void prepareUploadPage() {
        if (!running || stopping) { finishStopped(); return; }
        if (uploadIndex >= observations.size()) { beginDownloads(); return; }
        Observation item = observations.get(uploadIndex);
        setStatus("Uploading " + (uploadIndex + 1) + " of " + observations.size() + ": " + item.name);
        appendLog("[Upload " + (uploadIndex + 1) + "/" + observations.size() + "] " + item.name);
        selectingForUpload = true;
        String filename = jsQuote(item.name);
        String js = "(function(){var tries=0,name='" + filename + "'.toLowerCase();" +
                "var findDrop=function(){var all=[].slice.call(document.querySelectorAll('div,span,p,a,button'));" +
                "return all.filter(function(x){return /drop files.*click here/i.test((x.innerText||'').trim());})" +
                ".sort(function(a,b){return (a.innerText||'').length-(b.innerText||'').length;})[0];};" +
                "var open=setInterval(function(){tries++;var inputs=document.querySelectorAll('input[type=file]'),drop=findDrop();" +
                "if(drop){clearInterval(open);drop.click();afterPick();}" +
                "else if(inputs.length){clearInterval(open);inputs[inputs.length-1].click();afterPick();}" +
                "else if(tries>=45){clearInterval(open);AndroidProcessor.uploadError('Drop files area did not appear');}},1000);" +
                "function afterPick(){var n=0,added=setInterval(function(){n++;var body=(document.body.innerText||'').toLowerCase();" +
                "if(body.indexOf(name)>=0){clearInterval(added);submit();}" +
                "else if(n>=30){clearInterval(added);AndroidProcessor.uploadError('JAVAD did not display the selected file');}},1000);}" +
                "function submit(){var b=[].slice.call(document.querySelectorAll('button,a')).find(function(x){return /^submit$/i.test((x.innerText||'').trim())});" +
                "if(!b||b.disabled){AndroidProcessor.uploadError('Submit button not ready');return;}b.click();var n=0,t=setInterval(function(){n++;var x=(document.body.innerText||'').toLowerCase();" +
                "if(/observe the progress|has been submitted|submitted successfully/.test(x)){clearInterval(t);AndroidProcessor.submitted();}" +
                "else if(n>=60){clearInterval(t);AndroidProcessor.uploadError('JAVAD did not confirm submission');}},1000);}})();";
        web.evaluateJavascript(js, null);
    }

    private class WebBridge {
        @JavascriptInterface public void loginReady() { runOnUiThread(() -> {
            setStatus("JAVAD login confirmed — Upload Data is ready.");
            toast("JAVAD login confirmed. Tap Return to Processor.");
        }); }
        @JavascriptInterface public void loginWaiting() { runOnUiThread(() ->
                setStatus("JAVAD is not signed in yet. Complete the login in this app.")); }
        @JavascriptInterface public void submitted() { runOnUiThread(() -> {
            Observation item = observations.get(uploadIndex); submitted.add(item);
            uploadAttempt = 0;
            appendLog("CONFIRMED submitted: " + item.name); uploadIndex++; updateProgress(true, uploadIndex, observations.size());
            if (stopping) { finishStopped(); return; }
            if (uploadIndex >= observations.size()) { handler.postDelayed(() -> beginDownloads(), 3000); return; }
            setStatus("Resetting Upload Data for the next plot…");
            web.loadUrl(String.format(Locale.US, REPORT_URL, 1));
            handler.postDelayed(() -> web.loadUrl(UPLOAD_URL), 10000);
        }); }
        @JavascriptInterface public void uploadError(String message) { runOnUiThread(() -> failUpload(message)); }
        @JavascriptInterface public void reportReady() { runOnUiThread(() -> clickTxtDownload()); }
        @JavascriptInterface public void reportMissing() { runOnUiThread(() -> nextReportPage()); }
    }

    private void failUpload(String message) {
        Observation item = observations.get(uploadIndex);
        uploadAttempt++;
        if (uploadAttempt < 3) {
            appendLog("JAVAD was not ready for " + item.name + "; retrying (" + uploadAttempt + "/3)");
            web.loadUrl(String.format(Locale.US, REPORT_URL, 1));
            handler.postDelayed(() -> web.loadUrl(UPLOAD_URL), 10000);
            return;
        }
        failures.add(item.name + " — upload: " + message);
        appendLog("UPLOAD FAILED " + item.name + ": " + message);
        uploadAttempt = 0; uploadIndex++; updateProgress(true, uploadIndex, observations.size());
        handler.postDelayed(() -> web.loadUrl(UPLOAD_URL), 2000);
    }

    private void beginDownloads() {
        if (!running) return;
        phase = Phase.DOWNLOAD;
        appendLog("DOWNLOAD PHASE — " + submitted.size() + " submitted file(s)");
        if (submitted.isEmpty()) { finishRun(); return; }
        downloadIndex = 0; reportPage = 1;
        resetReportDeadline();
        web.loadUrl(String.format(Locale.US, REPORT_URL, reportPage));
    }

    private void checkReportPage() {
        if (!running || downloadIndex >= submitted.size()) return;
        String name = jsQuote(submitted.get(downloadIndex).name);
        setStatus("Checking reports for " + submitted.get(downloadIndex).name + " — page " + reportPage);
        String js = "(function(){var n='" + name + "'.toLowerCase(),rows=[].slice.call(document.querySelectorAll('tbody tr'));" +
                "var r=rows.find(function(x){var c=x.querySelectorAll('td');return c.length>1&&(c[1].innerText||'').trim().toLowerCase()===n});" +
                "if(r&&r.querySelectorAll('save-as').length>=2)AndroidProcessor.reportReady();else AndroidProcessor.reportMissing();})();";
        web.evaluateJavascript(js, null);
    }

    private void clickTxtDownload() {
        String name = jsQuote(submitted.get(downloadIndex).name);
        String js = "(function(){var n='" + name + "'.toLowerCase(),rows=[].slice.call(document.querySelectorAll('tbody tr'));" +
                "var r=rows.find(function(x){var c=x.querySelectorAll('td');return c.length>1&&(c[1].innerText||'').trim().toLowerCase()===n});" +
                "if(r){var s=r.querySelectorAll('save-as');if(s.length>1)s[1].click();}})();";
        web.evaluateJavascript(js, null);
    }

    private void nextReportPage() {
        if (System.currentTimeMillis() >= reportDeadline) {
            completeDownload(submitted.get(downloadIndex), "No downloadable TXT report appeared within the maximum wait");
            return;
        }
        int pages = Math.min(MAX_REPORT_PAGES, Math.max(1, (submitted.size() + 99) / 100));
        if (reportPage < pages) { reportPage++; web.loadUrl(String.format(Locale.US, REPORT_URL, reportPage)); }
        else handler.postDelayed(() -> { reportPage = 1; web.loadUrl(String.format(Locale.US, REPORT_URL, 1)); }, 15000);
    }

    private class ReportDownloadListener implements DownloadListener {
        @Override public void onDownloadStart(String url, String userAgent, String disposition, String mime, long length) {
            if (!running || downloadIndex >= submitted.size()) return;
            if ((mime != null && mime.toLowerCase(Locale.US).contains("pdf")) || url.toLowerCase(Locale.US).contains(".pdf")) {
                appendLog("Ignored PDF download"); return;
            }
            Observation item = submitted.get(downloadIndex);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url));
            request.addRequestHeader("User-Agent", userAgent);
            request.setMimeType("text/plain");
            request.setDestinationInExternalFilesDir(MainActivity.this, Environment.DIRECTORY_DOWNLOADS, txtName(item.name));
            long id = ((DownloadManager)getSystemService(DOWNLOAD_SERVICE)).enqueue(request);
            waitForDownload(id, item);
        }
    }

    private void waitForDownload(long id, Observation item) {
        new Thread(() -> {
            DownloadManager dm = (DownloadManager)getSystemService(DOWNLOAD_SERVICE);
            for (int tries=0; tries<180; tries++) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
                    if (c != null && c.moveToFirst()) {
                        int state = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        if (state == DownloadManager.STATUS_SUCCESSFUL) {
                            Uri local = Uri.parse(c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)));
                            boolean copied = copyResult(local, txtName(item.name));
                            runOnUiThread(() -> completeDownload(item, copied ? null : "Could not copy TXT to results folder")); return;
                        }
                        if (state == DownloadManager.STATUS_FAILED) { runOnUiThread(() -> completeDownload(item, "Android download failed")); return; }
                    }
                }
            }
            runOnUiThread(() -> completeDownload(item, "TXT download timed out"));
        }).start();
    }

    private boolean copyResult(Uri source, String filename) {
        try {
            String parent = DocumentsContract.getTreeDocumentId(outputTree);
            Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(outputTree, parent);
            Uri file = DocumentsContract.createDocument(getContentResolver(), parentUri, "text/plain", filename);
            if (file == null) return false;
            try (FileInputStream in = new FileInputStream(new File(source.getPath())); OutputStream out = getContentResolver().openOutputStream(file, "w")) {
                byte[] buf = new byte[8192]; int n; while ((n=in.read(buf))>0) out.write(buf,0,n);
            }
            return true;
        } catch (Exception e) { appendLog("Save error: " + e.getMessage()); return false; }
    }

    private void completeDownload(Observation item, String error) {
        if (error == null) { downloadSuccess++; appendLog("Downloaded TXT report for " + item.name); }
        else { failures.add(item.name + " — download: " + error); appendLog("DOWNLOAD FAILED " + item.name + ": " + error); }
        downloadIndex++; updateProgress(false, downloadIndex, submitted.size());
        if (downloadIndex >= submitted.size() || stopping) finishRun();
        else { reportPage = 1; resetReportDeadline(); web.loadUrl(String.format(Locale.US, REPORT_URL, 1)); }
    }

    private void finishRun() {
        running = false; start.setEnabled(true); stop.setEnabled(false); showControls();
        failedText.setText(failures.isEmpty() ? "No files failed." : "Failed files:\n" + String.join("\n", failures));
        setStatus("Finished: " + downloadSuccess + " succeeded, " + failures.size() + " failed.");
        saveFailureList();
    }
    private void finishStopped() { running = false; start.setEnabled(true); stop.setEnabled(false); showControls(); setStatus("Processing stopped safely."); }

    private void saveFailureList() {
        try {
            String parent = DocumentsContract.getTreeDocumentId(outputTree);
            Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(outputTree, parent);
            Uri file = DocumentsContract.createDocument(getContentResolver(), parentUri, "text/plain", "failed_files_latest.txt");
            if (file == null) return;
            String body = failures.isEmpty() ? "No files failed during the latest run.\n" : "Failed files from the latest run:\n\n" + String.join("\n", failures) + "\n";
            try (OutputStream out = getContentResolver().openOutputStream(file, "w")) { out.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        } catch (Exception e) { appendLog("Could not save failure list: " + e.getMessage()); }
    }

    private void updateProgress(boolean upload, int current, int total) {
        int pct = total == 0 ? 0 : Math.round(current * 100f / total);
        if (upload) { uploadBar.setProgress(pct); uploadText.setText("Uploading: " + pct + "% — " + current + " of " + total); }
        else { downloadBar.setProgress(pct); downloadText.setText("Downloading: " + pct + "% — " + current + " of " + total); }
    }
    private void setStatus(String message) { status.setText(message); }
    private void appendLog(String message) {
        runOnUiThread(() -> { String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()); log.append(time + "  " + message + "\n"); });
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private static String jsQuote(String value) { return value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " "); }
    private static String txtName(String value) {
        int dot = value.lastIndexOf('.');
        return (dot > 0 ? value.substring(0, dot) : value) + ".txt";
    }
    private static String stem(String value) {
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }
    private void resetReportDeadline() {
        int minutes = 30;
        try { minutes = Math.max(1, Integer.parseInt(maxWait.getText().toString())); } catch (Exception ignored) {}
        reportDeadline = System.currentTimeMillis() + minutes * 60_000L;
    }

    private static class Observation {
        final String name; final Uri uri;
        Observation(String name, Uri uri) { this.name = name; this.uri = uri; }
    }
    private enum Phase { IDLE, UPLOAD, DOWNLOAD }
}
