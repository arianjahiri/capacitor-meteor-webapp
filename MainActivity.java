package buzz.buzzy.my1;  // Change this to your package name

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

/**
 * Example MainActivity that integrates the Capacitor Meteor WebApp plugin
 * to enable hot code push, asset serving, and CORS bypass for external requests.
 * 
 * SETUP INSTRUCTIONS:
 * 1. Copy this file to your app's MainActivity.java
 * 2. Update the package name at the top to match your app
 * 3. Build and run your app
 * 
 * FEATURES:
 * - Serves Meteor assets from downloaded bundles (hot code push)
 * - Proxies external HTTP requests with CORS headers to bypass CORS restrictions
 * - Enables Meteor DDP connections from any origin (https://, http://, capacitor://)
 * 
 * IMPORTANT: The CORS bypass works with ANY URL scheme because it intercepts and
 * modifies HTTP responses at the native level. You can use https://localhost (default),
 * http://localhost, or capacitor://localhost - all will work with this proxy.
 */
public class MainActivity extends BridgeActivity {

    private static final String TAG = "MainActivity";

    @Override
    public void onStart() {
        super.onStart();

        // Set up custom WebViewClient to intercept requests for the Meteor plugin
        if (bridge != null && bridge.getWebView() != null) {
            WebView webView = bridge.getWebView();
            final WebViewClient originalClient = webView.getWebViewClient();

            webView.setWebViewClient(new WebViewClient() {
                @Override
                @Nullable
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    String url = request.getUrl().toString();
                    
                    // ============================================================================
                    // CORS BYPASS: Intercept external HTTP/HTTPS requests and add CORS headers
                    // ============================================================================
                    // This proxies external requests through the native HTTP client and injects
                    // CORS headers into the response, making the browser think the server allows
                    // cross-origin requests from capacitor:// scheme.
                    // This is necessary because Android WebView enforces CORS even for custom schemes.
                    // ============================================================================
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        // Check if this is an external request (not localhost)
                        if (!url.contains("localhost") && !url.contains("127.0.0.1")) {
                            Log.d(TAG, "[CORS Proxy] Intercepting external request: " + url);
                            return proxyRequestWithCORS(url, request);
                        }
                    }
                    
                    // Try the Meteor plugin for local assets
                    CapacitorMeteorWebAppPlugin meteorPlugin = CapacitorMeteorWebAppPlugin.getInstance();
                    if (meteorPlugin != null && request.getUrl() != null) {
                        String path = request.getUrl().getPath();
                        if (path != null && !path.isEmpty()) {
                            if (path.startsWith("/")) {
                                path = path.substring(1);
                            }

                            WebResourceResponse response = meteorPlugin.handleRequest(path);
                            if (response != null) {
                                return response;
                            }
                        }
                    }

                    // Fall back to the original Capacitor WebViewClient
                    if (originalClient != null) {
                        return originalClient.shouldInterceptRequest(view, request);
                    }

                    return null;
                }
                
                /**
                 * Proxy an external HTTP request and inject CORS headers to bypass CORS restrictions.
                 * This is critical for making Meteor DDP connections work from capacitor:// origin.
                 */
                private WebResourceResponse proxyRequestWithCORS(String urlString, WebResourceRequest request) {
                    try {
                        URL url = new URL(urlString);
                        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                        
                        // Copy request headers
                        Map<String, String> requestHeaders = request.getRequestHeaders();
                        if (requestHeaders != null) {
                            for (Map.Entry<String, String> entry : requestHeaders.entrySet()) {
                                connection.setRequestProperty(entry.getKey(), entry.getValue());
                            }
                        }
                        
                        // Set request method
                        connection.setRequestMethod(request.getMethod());
                        connection.setConnectTimeout(10000);
                        connection.setReadTimeout(10000);
                        
                        // Execute request
                        connection.connect();
                        
                        int responseCode = connection.getResponseCode();
                        String contentType = connection.getContentType();
                        String encoding = connection.getContentEncoding();
                        
                        // Get response headers and inject CORS headers
                        Map<String, String> responseHeaders = new HashMap<>();
                        Map<String, List<String>> headerFields = connection.getHeaderFields();
                        if (headerFields != null) {
                            for (Map.Entry<String, List<String>> entry : headerFields.entrySet()) {
                                String key = entry.getKey();
                                if (key != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
                                    responseHeaders.put(key, entry.getValue().get(0));
                                }
                            }
                        }
                        
                        // ============================================================================
                        // INJECT CORS HEADERS - Allow specific origin with credentials
                        // ============================================================================
                        // CRITICAL: When credentials are included, Access-Control-Allow-Origin CANNOT be '*'
                        // It must be the specific origin making the request (capacitor://)
                        // See: https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS#credentialed_requests
                        // ============================================================================
                        String origin = request.getRequestHeaders().get("Origin");
                        if (origin == null || origin.isEmpty()) {
                            origin = "capacitor://localhost"; // Default to capacitor origin
                        }
                        responseHeaders.put("Access-Control-Allow-Origin", origin);
                        responseHeaders.put("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, PATCH");
                        responseHeaders.put("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With, Accept, Origin");
                        responseHeaders.put("Access-Control-Allow-Credentials", "true");
                        responseHeaders.put("Access-Control-Max-Age", "86400");
                        responseHeaders.put("Vary", "Origin"); // Important: indicate that response varies by origin
                        // ============================================================================
                        
                        // Get response stream
                        InputStream inputStream;
                        if (responseCode >= 400) {
                            inputStream = connection.getErrorStream();
                        } else {
                            inputStream = connection.getInputStream();
                        }
                        
                        Log.d(TAG, "[CORS Proxy] ✅ Proxied request with CORS headers: " + urlString);
                        
                        // Return response with CORS headers
                        return new WebResourceResponse(
                            contentType != null ? contentType : "text/plain",
                            encoding != null ? encoding : "UTF-8",
                            responseCode,
                            "OK",
                            responseHeaders,
                            inputStream
                        );
                        
                    } catch (Exception e) {
                        Log.e(TAG, "[CORS Proxy] ❌ Failed to proxy request: " + urlString, e);
                        return null;
                    }
                }

                // Important: Forward other WebViewClient methods to the original client
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    if (originalClient != null) {
                        return originalClient.shouldOverrideUrlLoading(view, request);
                    }
                    return super.shouldOverrideUrlLoading(view, request);
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    if (originalClient != null) {
                        originalClient.onPageFinished(view, url);
                    }
                    super.onPageFinished(view, url);
                }
            });
        }
    }
}
