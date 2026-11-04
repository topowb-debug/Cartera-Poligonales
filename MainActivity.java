package com.wbtopografia.poligonales;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.webkit.DownloadListener;
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String BASE = "https://" + HOST + "/";
    private static final int FILE_REQUEST = 42;
    private static final int LOCATION_REQUEST = 43;
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private GeolocationPermissions.Callback geoCallback;
    private String geoOrigin;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setGeolocationEnabled(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        web.addJavascriptInterface(new NativeBridge(), "WBAndroid");
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (!HOST.equals(u.getHost()) || !"https".equals(u.getScheme())) return null;
                String path = u.getEncodedPath();
                if (path == null || path.equals("/")) path = "/index.html";
                try {
                    String decoded = Uri.decode(path);
                    if (decoded.contains("..") || decoded.contains("\\") || decoded.indexOf('\0') >= 0) return missing();
                    String rel = decoded.startsWith("/") ? decoded.substring(1) : decoded;
                    InputStream stream = getAssets().open("www/" + rel);
                    String extension = MimeTypeMap.getFileExtensionFromUrl(rel);
                    String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase(Locale.ROOT));
                    if (mime == null) mime = "application/octet-stream";
                    if (".js".equals("." + extension)) mime = "application/javascript";
                    if (".webmanifest".equals("." + extension)) mime = "application/manifest+json";
                    if (".json".equals("." + extension)) mime = "application/json";
                    return new WebResourceResponse(mime, "UTF-8", stream);
                } catch (Exception ex) { return missing(); }
            }
            private WebResourceResponse missing() { return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", java.util.Collections.emptyMap(), new ByteArrayInputStream(new byte[0])); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost()) && "https".equals(u.getScheme())) return false;
                if (!"https".equals(u.getScheme()) && !"mailto".equals(u.getScheme()) && !"tel".equals(u.getScheme())) return true;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (url.startsWith(BASE)) injectDownloads();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try { startActivityForResult(params.createIntent(), FILE_REQUEST); }
                catch (Exception ex) { fileCallback = null; callback.onReceiveValue(null); Toast.makeText(MainActivity.this, "No se pudo abrir el selector de archivos", Toast.LENGTH_LONG).show(); }
                return true;
            }
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (!BASE.startsWith(origin) && !origin.startsWith(BASE.substring(0, BASE.length()-1))) { callback.invoke(origin, false, false); return; }
                if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    geoOrigin = origin; geoCallback = callback;
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_REQUEST);
                } else callback.invoke(origin, true, false);
            }
        });
        web.setDownloadListener((url, agent, disposition, mime, size) -> {
            if (!url.startsWith("https://")) { Toast.makeText(this, "Descarga no compatible: utilice los botones de exportación", Toast.LENGTH_LONG).show(); return; }
            try {
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "WBTopografia_" + System.currentTimeMillis());
                ((DownloadManager)getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
            } catch (Exception ex) { Toast.makeText(this, "No se pudo descargar", Toast.LENGTH_LONG).show(); }
        });
        web.loadUrl(BASE + "index.html");
    }

    private void injectDownloads() {
        // Intercept local blob/data links so the Android Downloads directory receives CSV/JSON exports.
        String js = "(function(){if(window.__wbAndroidDownloads)return;window.__wbAndroidDownloads=true;window.print=function(){WBAndroid.printPage()};"+
            "document.addEventListener('click',function(e){var a=e.target.closest&&e.target.closest('a[download]');if(!a)return;"+
            "var u=a.href;if(!u||(!u.startsWith('blob:')&&!u.startsWith('data:')))return;e.preventDefault();"+
            "var name=a.download||'WBTopografia_exportacion';fetch(u).then(function(r){return r.blob()}).then(function(b){"+
            "var f=new FileReader();f.onload=function(){WBAndroid.saveBase64(name,String(f.result).split(',')[1]);};f.readAsDataURL(b);"+
            "}).catch(function(err){console.error(err)});},true);})();";
        web.evaluateJavascript(js, null);
    }

    public final class NativeBridge {
        @JavascriptInterface public void saveBase64(String fileName, String encoded) {
            try {
                String clean = fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
                if (clean.isEmpty() || clean.equals(".") || clean.equals("..")) clean = "WBTopografia_exportacion.txt";
                if (encoded.length() > 25_000_000) throw new Exception("Archivo demasiado grande");
                byte[] data = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT);
                String finalName = System.currentTimeMillis() + "_" + clean;
                if (Build.VERSION.SDK_INT >= 29) {
                    android.content.ContentValues values = new android.content.ContentValues();
                    values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, finalName);
                    values.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
                    values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) throw new Exception("No se pudo crear la descarga");
                    try (java.io.OutputStream stream = getContentResolver().openOutputStream(uri)) { if (stream == null) throw new Exception("No se pudo abrir la descarga"); stream.write(data); }
                } else {
                    File folder = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (folder == null) throw new Exception("No hay almacenamiento disponible");
                    File target = new File(folder, finalName);
                    try (FileOutputStream stream = new FileOutputStream(target)) { stream.write(data); }
                }
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Guardado: " + finalName, Toast.LENGTH_LONG).show());
            } catch (Exception ex) { runOnUiThread(() -> Toast.makeText(MainActivity.this, "Error al exportar: " + ex.getMessage(), Toast.LENGTH_LONG).show()); }
        }
        @JavascriptInterface public void printPage() { runOnUiThread(() -> {
            PrintManager manager = (PrintManager)getSystemService(Context.PRINT_SERVICE);
            manager.print("WB TOPOGRAFÍA", web.createPrintDocumentAdapter("WB TOPOGRAFÍA"), null);
        }); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == FILE_REQUEST && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            fileCallback = null;
        }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == LOCATION_REQUEST && geoCallback != null) {
            boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            geoCallback.invoke(geoOrigin, ok, false); geoCallback = null; geoOrigin = null;
        }
    }
    @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else super.onBackPressed(); }
    @Override protected void onDestroy() { if (web != null) web.destroy(); super.onDestroy(); }
}
