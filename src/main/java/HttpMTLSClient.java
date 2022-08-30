import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;

import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HeaderElementIterator;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.*;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicHeaderElementIterator;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;

/**
 * Sample code for pooled HTTP connections, with MTLS support.
 * SSL KeyStore is using PKCS12 format.
 */
public class HttpMTLSClient {
    // get client keystore password from java property CLIENT_KEYSTORE_PASSWORD
    private static final String clientKeyStorePassword = System.getProperty("CLIENT_KEYSTORE_PASSWORD");
    // get client keystore path from java property CLIENT_KEYSTORE_PATH
    private static final String clientKeyStorePath = System.getProperty("CLIENT_KEYSTORE_PATH");
    // get authorization header from java property AUTHORIZATION_HEADER
    private static final String authorizationHeader = System.getProperty("AUTHORIZATION_HEADER");

    String serverTrustStorePath = null;
    private static SSLContext sslContext = null;
    private static PoolingHttpClientConnectionManager cm = null;

    private static final String KEYSTORE_TYPE = "pkcs12";
    private static final String TLS_VERSION = "TLSv1.3";
    private int MAX_CONN_SIZE = 20;
    private int MAX_CONN_PER_ROUTE = 4;
    private int DEFAULT_KEEP_ALIVE = 30000;  // 30 seconds
    private int VALIDATE_INACTIVITY_INTERVAL_MS = 10000;
    private int NUM_CONN_RETRIES = 2;

    char[] clientKeyStorePasswordChars = null;

    HttpMTLSClient  singleton = null;
    public HttpMTLSClient getInstance() {
        if (singleton == null) {
            singleton = new HttpMTLSClient();
        }
        return singleton;
    }

    private KeyStore getClientKeyStore(String _clientKeyStorePath, String _clientKeyStorePassword) throws Exception {
        if (_clientKeyStorePath == null) {
            return null;
        }
        KeyStore clientKeyStore = KeyStore.getInstance(KEYSTORE_TYPE);
        clientKeyStorePasswordChars = null;
        if (_clientKeyStorePassword != null) {
            clientKeyStorePasswordChars = _clientKeyStorePassword.toCharArray();
        }
        clientKeyStore.load(new FileInputStream(_clientKeyStorePath), clientKeyStorePasswordChars);
        return clientKeyStore;
    }

    private KeyStore getServerTrustStore(String _serverTrustStorePath) throws Exception {
        if (_serverTrustStorePath == null) {
            return null;
        }
        KeyStore serverTrustStore = KeyStore.getInstance(KEYSTORE_TYPE);
        serverTrustStore.load(new FileInputStream(_serverTrustStorePath), null); // no password for trust store
        return serverTrustStore;
    }

    /**
     * create SSL context with pkcs12 client key store file and trusted server trust store file
     */
    private SSLContext getSSLContext() throws Exception {
        if (sslContext != null) {
            return sslContext;
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());

        kmf.init(getClientKeyStore(clientKeyStorePath, clientKeyStorePassword), clientKeyStorePasswordChars);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(getServerTrustStore(serverTrustStorePath));
        sslContext = SSLContext.getInstance(TLS_VERSION);
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return sslContext;
    }

    /**
     *   Get a pooling http connection manager
     */
    public PoolingHttpClientConnectionManager getPoolingHttpClientConnectionManager() throws Exception {
        if (cm != null) {
            return cm;
        }
        SSLContext sslContext = getSSLContext();
        SSLConnectionSocketFactory sslsf = new SSLConnectionSocketFactory(sslContext, new String[]{TLS_VERSION}, null,
                SSLConnectionSocketFactory.getDefaultHostnameVerifier());
        Registry<ConnectionSocketFactory> socketFactoryRegistry = RegistryBuilder.<ConnectionSocketFactory>create()
                .register("https", sslsf)
                .build();
        cm = new PoolingHttpClientConnectionManager(socketFactoryRegistry);
        cm.setMaxTotal(MAX_CONN_SIZE);
        cm.setDefaultMaxPerRoute(MAX_CONN_PER_ROUTE);
        cm.setValidateAfterInactivity(VALIDATE_INACTIVITY_INTERVAL_MS);
        return cm;
    }

    public CloseableHttpClient getHttpClient() throws Exception {
        return getHttpClient(1000, 2000, 2000);
    }

    public CloseableHttpClient getHttpClient(int getClientConnTimeout, int serverConnTimeout, int serverReadTimeout) throws Exception {
        PoolingHttpClientConnectionManager cm = getPoolingHttpClientConnectionManager();
        // a better keepalive strategy, which is based on server response header
        // when response does not have keepa-live header, use DEFAULT_KEEP_ALIVE.
        ConnectionKeepAliveStrategy myStrategy = new ConnectionKeepAliveStrategy() {
            @Override
            public long getKeepAliveDuration(HttpResponse response, HttpContext context) {
                HeaderElementIterator it = new BasicHeaderElementIterator
                        (response.headerIterator(HTTP.CONN_KEEP_ALIVE));
                while (it.hasNext()) {
                    HeaderElement he = it.nextElement();
                    String param = he.getName();
                    String value = he.getValue();
                    if (value != null && param.equalsIgnoreCase
                            ("timeout")) {
                        return Long.parseLong(value) * 1000;
                    }
                }
                return DEFAULT_KEEP_ALIVE;
            }
        };

        DefaultHttpRequestRetryHandler retryHandler = new DefaultHttpRequestRetryHandler(NUM_CONN_RETRIES, true);
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(serverConnTimeout)    // timeout to connect to server
                .setSocketTimeout(serverReadTimeout)     // timeout to read server responses
                .setConnectionRequestTimeout(getClientConnTimeout) // timeout to get a connection from connection pool
                .build();

        HttpClientBuilder httpClientBuilder = HttpClients.custom()
                .setConnectionManager(cm)
                .setKeepAliveStrategy(myStrategy)
                .setRetryHandler(retryHandler)
                .setConnectionTimeToLive(DEFAULT_KEEP_ALIVE, TimeUnit.MILLISECONDS)
        //        .disableCookieManagement()    // do not disable cookie for server affinity in load balancer
                .disableRedirectHandling()
                .setDefaultRequestConfig(requestConfig);

        return httpClientBuilder.build();
    }

    public static void main(String[] args) throws Exception {
        String url = args[0];
        // print what is default keystore type
        System.out.println("Default keystore type: " + KeyStore.getDefaultType());
        // print what is default keystore provider
        System.out.println("Default keystore provider: " + KeyStore.getDefaultType());
        // print what is default key manager factory algorithm
        System.out.println("Default key manager factory algorithm: " + KeyManagerFactory.getDefaultAlgorithm());
        // print what is default trust manager factory algorithm
        System.out.println("Default trust manager factory algorithm: " + TrustManagerFactory.getDefaultAlgorithm());
        // print what is default ssl context algorithm
        System.out.println("Default ssl context algorithm: " + SSLContext.getDefault().getProtocol());
        // print what is default ssl connection socket factory algorithm
        System.out.println("Default ssl connection socket factory algorithm: " + SSLConnectionSocketFactory.getDefaultHostnameVerifier());

        HttpMTLSClient client = new HttpMTLSClient();
        CloseableHttpClient httpClient = client.getHttpClient();
        // execute an HTTP POST request
        try {
            HttpGet httpGet = new HttpGet(url);
            //httpPost.addHeader("content-type", "application/json");
            httpGet.addHeader("authorization", authorizationHeader);
            //String postBody = "{\"name\":\"John\"}";
            //httpPost.setEntity(new StringEntity(postBody, StandardCharsets.UTF_8));
            CloseableHttpResponse response = httpClient.execute(httpGet);
            System.out.println("In the middle: connection stats: " + client.getPoolingHttpClientConnectionManager().getTotalStats().toString());
            System.out.println("Response status: " + response.getStatusLine());

            // print response headers
            Header[] headers = response.getAllHeaders();
            for (Header header : headers) {
                System.out.println("    " + header.getName() + ": " + header.getValue());
            }
            if (response.getEntity() != null) {
                // print the first 128 char of the response body, or the entire body if less than 128 char
                String responseBody = EntityUtils.toString(response.getEntity());
                if (responseBody.length() > 128) {
                    System.out.println("Response body: " + responseBody.substring(0, 128) + "...");
                } else {
                    System.out.println("Response body: " + responseBody);
                }
            }
            response.close();

            System.out.println("Connection Manager Stats: " + client.getPoolingHttpClientConnectionManager().getTotalStats().toString());
        } catch(Exception e) {
            System.err.println("Exception: " + e.getMessage());
            e.printStackTrace();
        } finally {
            httpClient.close();
            System.out.println("After finally stats: " + client.getPoolingHttpClientConnectionManager().getTotalStats().toString());
        }
    }
}
