import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Calendar;
import java.util.concurrent.TimeUnit;

import org.apache.http.*;
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
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.client.*;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicHeaderElementIterator;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;

/**
 * Sample code for pooled HTTP connections, with MTLS support.
 * SSL KeyStore is using PKCS12 format.
 *
 * To create a PKCS12 keystore, use the following sample command:
 *     cat client.key  client.pem  intermediate.pem > mykeycert.pem.txt
 *     openssl pkcs12 -export -in mykeycert.pem.txt -out mykeystore.pkcs12  -name myCert -noiter -nomaciter -password pass:myPass
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
    private static final String TLS_VERSION = "TLSv1.2"; // or TLSv1.3
    private int MAX_CONN_SIZE = 8;
    private int MAX_CONN_PER_ROUTE = 1;
    private int DEFAULT_KEEP_ALIVE = 10000;  // 10 seconds
    private int VALIDATE_INACTIVITY_INTERVAL_MS = 10000;
    private int NUM_CONN_RETRIES = 2;
    /**
     *  Increase to avoid "org.apache.http.conn.ConnectionPoolTimeoutException: Timeout waiting for connection from pool"
     */
    private static int DEFAULT_CLIENT_GET_CONNECTION_TIMEROUT = 2000;
    /**
     *  Increase to avoid "org.apache.http.conn.ConnectTimeoutException: Connect to 10.10.1.1:443 [/10.10.1.1] failed"
     */
    private static int DEFAULT_SERVER_CONNECT_TIMEOUT = 10000;
    /**
     *  Increase to avoid:  "java.net.SocketTimeoutException: Read timed out"
     */
    private static int DEFAULT_SERVER_RESPONSE_TIMEOUT = 4000;

    char[] clientKeyStorePasswordChars = null;

    static HttpMTLSClient  singleton = null;
    static public HttpMTLSClient getInstance() {
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
            // log("connection pool manager stats: " + cm.getTotalStats().toString());
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
        return getHttpClient(DEFAULT_CLIENT_GET_CONNECTION_TIMEROUT, DEFAULT_SERVER_CONNECT_TIMEOUT, DEFAULT_SERVER_RESPONSE_TIMEOUT);
    }

    public synchronized CloseableHttpClient getHttpClient(int getClientConnTimeout, int serverConnTimeout, int serverReadTimeout) throws Exception {
        PoolingHttpClientConnectionManager cm = getPoolingHttpClientConnectionManager();
        // a better keepalive strategy, which is based on server response header
        // when response does not have keepa-live header, use DEFAULT_KEEP_ALIVE.
        ConnectionKeepAliveStrategy myKeepAliveStrategy = new ConnectionKeepAliveStrategy() {
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
                        log("set keep-alive: " + value.toString());
                        return Long.parseLong(value) * 1000;
                    }
                }
                Header ka = response.getFirstHeader(HTTP.CONN_DIRECTIVE);
                if (ka != null) {
                    if (ka.getValue().equalsIgnoreCase("keep-alive")) {
                        log("set default keep-alive:" + DEFAULT_KEEP_ALIVE);
                        return DEFAULT_KEEP_ALIVE;
                    }
                }
                // all other conditions, do nto keep alive
                log("do not keep alive");
                return 0L;
            }
        };

        ConnectionReuseStrategy myReuseStrategy = new DefaultConnectionReuseStrategy() {
            @Override
            public boolean keepAlive(HttpResponse response, HttpContext context) {
                Header[] kas = response.getHeaders(HTTP.CONN_DIRECTIVE);
                for (int i=0; i < kas.length; i++) {
                    Header ka = kas[i];
                    if (ka.getValue().equalsIgnoreCase("keep-alive")) {
                        log("reuse keep-alive");
                        return true;
                    }
                }
                // all other conditions, do nto keep alive
                log("do not reuse keep-alive");
                return false;
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
                .setConnectionReuseStrategy(myReuseStrategy)
                .setKeepAliveStrategy(myKeepAliveStrategy)
                .setRetryHandler(retryHandler)
                .setConnectionTimeToLive(DEFAULT_KEEP_ALIVE, TimeUnit.MILLISECONDS)  // max time in the connection pool
        //        .disableCookieManagement()    // do not disable cookie for server affinity in load balancer
                .disableRedirectHandling()
                .setDefaultRequestConfig(requestConfig);

        return httpClientBuilder.build();
    }

    /**
     * get a multiline of string representation for the stack trace
     *
     * @param e    the exception object
     * @param depth   stack trace up to depth, if depth==0, print the entire stack
     * @return   string presentation of the exception
     */
    static private String getStackTrace(Exception e, int depth) {
        StackTraceElement[] st = e.getStackTrace();
        StringBuffer sb = new StringBuffer();
        int d = st.length;
        if (depth > 0 && depth < st.length)   d = depth;
        sb.append(e.getClass().getName()).append("\n");
        for (int i=0; i<d; i++) {
            sb.append("    ").append(st[i].toString()).append("\n");
        }
        return sb.toString();
    }

    /**
     * get  current timestamp string
     */
    static private String getTimeString() {
        Calendar cal = Calendar.getInstance();
        return cal.getTime().toString();
    }
    /**
     * format output lines
     */
    static synchronized void log(String s) {
        System.out.println(getTimeString() + " Line:" + Thread.currentThread().getStackTrace()[2].getLineNumber() + " [" + Thread.currentThread().getName() + "] " + s);
    }
    /**
     *  Test HttpMTLSClient
     *  sample parameters:
     *   -DCLIENT_KEYSTORE_PASSWORD=myPass
     *   -DCLIENT_KEYSTORE_PATH=/Users/garyzhu/mykeystore.pkcs12
     *   -DAUTHORIZATION_HEADER="Basic UkJf........"
     *   HttpMTLSClient https://somewhere.garyzhu.net/test/
     *
     * @param args
     * @throws Exception
     */
    public static void main(String[] args) throws Exception {
        String url = args[0];
        // final String url = "https://www.google.com/";
        // final String url = "https://tls13.cloudflare.com/";
        // final String url = "https://www.baeldung.com/api/country-code/";   // with Connection: close
        // final String url = "https://apis.cmp.quantcast.com/geoip";  // has keep-alive header
        // final String url = "https://garyzhu.net/logo.gif";    // Connection: Keep-Alive, Keep-Alive: timeout=5,max=98
        // final String url = "https://10.10.1.1/";

        /**
        // print what is default keystore type -- pkcs12
        log("Default keystore type: " + KeyStore.getDefaultType());
        // print what is default keystore provider  -- pkcs12
        log("Default keystore provider: " + KeyStore.getDefaultType());
        // print what is default key manager factory algorithm -- SunX509
        log("Default key manager factory algorithm: " + KeyManagerFactory.getDefaultAlgorithm());
        // print what is default trust manager factory algorithm -- PKIX
        log("Default trust manager factory algorithm: " + TrustManagerFactory.getDefaultAlgorithm());
        // print what is default ssl context algorithm  -- Default
        log("Default ssl context algorithm: " + SSLContext.getDefault().getProtocol());
        // print what is default ssl connection socket factory algorithm  -- org.apache.http.conn.ssl.DefaultHostnameVerifier
        log("Default ssl connection socket factory algorithm: " + SSLConnectionSocketFactory.getDefaultHostnameVerifier());
        **/
        // execute an HTTP POST request
        Runnable r = () -> {
            try {
                // do NOT close this httpclient, it is managed by Connection Manager
                CloseableHttpClient httpClient = getInstance().getHttpClient();
                HttpGet httpGet = new HttpGet(url);
                CloseableHttpResponse response = null;
                //httpPost.addHeader("content-type", "application/json");
                httpGet.addHeader("authorization", authorizationHeader);
                //String postBody = "{\"name\":\"John\"}";
                //httpPost.setEntity(new StringEntity(postBody, StandardCharsets.UTF_8));
                // below is the time actually get  a connection from the pool
                log("Before execute HttpGet " + httpGet.toString() + " connection stats: "
                        + getInstance().getPoolingHttpClientConnectionManager().getTotalStats().toString());
                response = httpClient.execute(httpGet);
                log("Finished, Response status: " + response.getStatusLine());

                // print response headers
                Header[] headers = response.getAllHeaders();

                for (Header header : headers) {
                    log("    " + header.getName() + ": " + header.getValue());
                }

                if (response.getEntity() != null) {
                    // print the first 128 char of the response body, or the entire body if less than 128 char
                    String responseBody = EntityUtils.toString(response.getEntity());
                    if (responseBody.length() > 64) {
                        log("Response body: " + responseBody.substring(0, 64) + "...");
                    } else {
                        log("Response body: " + responseBody);
                    }
                }
                response.close();

                log("Successful,  Connection Manager Stats: "
                        + getInstance().getPoolingHttpClientConnectionManager().getTotalStats().toString());
            } catch (Exception e) {
                log("Failed Exception: "
                        + e.getMessage() + " ==> " + getStackTrace(e, 0));
            }
        };
        final int TCOUNT = 20;
        Thread[] ts = new Thread[TCOUNT];
        for (int i=0; i< TCOUNT; i++) {
            ts[i] = new Thread(r);
            if (i%2 == 0)   ts[i].start();
        }
        Thread.sleep(800);
        System.out.println("--------------");
        for (int i=0; i< TCOUNT; i++) {
            if (i%2 == 1)   ts[i].start();
        }
        for (int i=0; i< TCOUNT; i++) {
            ts[i].join();
        }

        Thread.sleep(3000);
    }
}
