# Android Integration Guide

To enable the Capacitor Meteor WebApp plugin to serve Meteor assets and bypass CORS restrictions, you need to:
1. Set up a custom WebViewClient in your MainActivity with **CORS proxy** (required)
2. Optionally configure your Capacitor URL scheme

## Prerequisites

### 1. Configure Capacitor Scheme (Optional)

In your Meteor app's `capacitor.config.json`, you can optionally set:

```json
{
  "appId": "your.app.id",
  "appName": "Your App",
  "webDir": "www-dist",
  "server": {
    "androidScheme": "https"  // or "capacitor" - both work with the CORS proxy
  }
}
```

**Important:** CORS bypass works with **any scheme** because the MainActivity's CORS proxy intercepts and modifies HTTP responses. The proxy is what bypasses CORS, not the URL scheme.

- ✅ `https://localhost` works (default)
- ✅ `http://localhost` works
- ✅ `capacitor://localhost` works

Choose the scheme that works best for your app. The `https` scheme has better compatibility with some web features.

After changing this, run:
```bash
npx cap sync android
```

### 2. Set Up MainActivity

#### Option 1: Use the Example MainActivity (Recommended)

1. Copy the example MainActivity to your app:
   ```bash
   cp ExampleMainActivity.java android/app/src/main/java/your/package/name/MainActivity.java
   ```

2. Update the package name at the top to match your app's package

3. Build and run

#### Option 2: Manual Integration

If you have custom code in your MainActivity, see the `ExampleMainActivity.java` file for the complete implementation including:

1. **CORS Proxy** - Intercepts external HTTP requests and injects CORS headers
2. **Asset Serving** - Serves Meteor assets from downloaded bundles
3. **WebViewClient wrapping** - Forwards callbacks to Capacitor's original client

Key imports you'll need:
```java
import android.util.Log;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import androidx.annotation.Nullable;
import com.banjerluke.capacitormeteorwebapp.CapacitorMeteorWebAppPlugin;
import com.getcapacitor.BridgeActivity;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
```

**⚠️ Important:** The CORS proxy implementation is critical for Android. Without it, Meteor DDP connections will fail with CORS errors. See `ExampleMainActivity.java` for the complete `proxyRequestWithCORS()` method.

## How It Works

### Custom WebViewClient Integration

1. In `onStart()`, we get the WebView and its existing WebViewClient
2. We wrap it with a custom WebViewClient that intercepts ALL requests
3. Request handling order:
   - **External HTTP/HTTPS requests** → CORS Proxy (adds CORS headers)
   - **Local assets** → Meteor Plugin (serves from bundles)
   - **Everything else** → Capacitor's original client

### CORS Proxy (Critical for Android)

Android's WebView enforces CORS for all cross-origin requests, regardless of the URL scheme (`https://`, `http://`, or `capacitor://`). The CORS proxy bypasses this:

1. **Intercepts** external HTTP/HTTPS requests (to your Meteor server)
2. **Proxies** them through native `HttpURLConnection`
3. **Injects** CORS headers into the response:
   - `Access-Control-Allow-Origin: <origin>` (matches the request origin)
   - `Access-Control-Allow-Credentials: true`
   - `Access-Control-Allow-Methods`, `Access-Control-Allow-Headers`, etc.
4. **Returns** the modified response to the WebView

This makes Meteor DDP connections work from **any origin**, bypassing CORS restrictions that would otherwise block all requests to external servers.

**Why iOS doesn't need this:** iOS's WKWebView allows cross-origin requests from `capacitor://` scheme by default. Android's WebView doesn't have this exemption, requiring the proxy workaround.

### Asset Serving

The Meteor plugin serves assets from:
- Downloaded asset bundles (for hot code push)
- Initial bundled assets
- Static files from public/www directories

## Troubleshooting

### CORS Errors (Most Common)

**Symptom:** Logs show `Access to XMLHttpRequest has been blocked by CORS policy`

**Root Cause:** The CORS proxy in MainActivity is missing or not working correctly.

**Solutions:**
1. ✅ Verify CORS proxy code is in your MainActivity (see MainActivity.java example)
2. ✅ Check logs for `[CORS Proxy] Intercepting external request` messages
3. ✅ Look for `[CORS Proxy] ✅ Proxied request with CORS headers` in logs
4. ✅ Rebuild the app completely (`./gradlew clean assembleDebug`)
5. ✅ Ensure imports are correct (HttpURLConnection, Map, etc.)

**Important:** The URL scheme (`https://`, `http://`, or `capacitor://`) doesn't matter for CORS. The CORS proxy in MainActivity is what bypasses CORS by intercepting HTTP requests and injecting CORS headers into responses.

**Note:** If you see CORS errors, the proxy is either missing or not intercepting external requests properly.

### Assets aren't loading

1. Check logcat for `MeteorWebApp` tagged messages
2. Verify that the plugin initialized successfully (look for "✅ CapacitorMeteorWebAppPlugin initialized successfully")
3. Ensure your Meteor app is built and in `android/src/main/assets/public/`
4. Verify `program.json` exists in the assets directory

### Build errors

- Make sure all imports are present (see list above)
- If `bridge` is not accessible, ensure you're extending `BridgeActivity`
- Check that you have Java 8+ language features enabled in your `build.gradle`

### Runtime errors

**NullPointerException on first run:**
- This is normal - first run has no previous configuration
- Look for "No previous ROOT_URL configured (first run)" in logs

**App crashes when downloading updates:**
- Check for null pointer exceptions in `AssetBundleDownloader`
- Verify the Meteor server is serving valid `program.json` and `manifest.json`

**CORS proxy not working:**
- Verify external requests are being intercepted (look for `[CORS Proxy] Intercepting external request` in logs)
- Check that the proxy isn't catching localhost requests
- Ensure `HttpURLConnection` can access the network (check permissions)

## What's Next

Once integrated, the plugin will:
- ✅ Serve your Meteor app assets
- ✅ Check for updates from your server
- ✅ Download new versions in the background
- ✅ Switch to new versions on app reload
- ✅ Rollback to last known good version on errors

Use the JavaScript API to control updates:
```javascript
import { CapacitorMeteorWebApp } from 'capacitor-meteor-webapp';

// Check for updates
await CapacitorMeteorWebApp.checkForUpdates();

// Listen for new versions
CapacitorMeteorWebApp.addListener('newVersionReady', (info) => {
  console.log('New version available:', info.version);
  // Optionally reload to use the new version
  await CapacitorMeteorWebApp.reload();
});

// Mark startup as complete (important for version validation)
await CapacitorMeteorWebApp.startupDidComplete();
```
