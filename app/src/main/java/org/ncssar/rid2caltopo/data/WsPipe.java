package org.ncssar.rid2caltopo.data;
// You will also need:
import java.security.KeyPairGenerator;
import java.security.KeyPair;

import java.net.InetAddress;

import okhttp3.Request;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

import java.net.ServerSocket;


import java.net.NetworkInterface;
import java.security.cert.CertificateException;
import java.util.Collections;
import java.util.List;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTError;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.ncssar.rid2caltopo.app.R2CActivity;
import org.opendroneid.android.data.Util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocketFactory;

import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.OkHttpClient;

import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import okio.ByteString;
import okhttp3.mockwebserver.MockWebServer;


/** Implement a bidirectional websockets connection.
  *   Pipe can be initiated either from client side (outbound)
  * or the server side (inbound), but in all cases serves as a
  * bidirectional, asynchronous, point-to-point connection
  * between application instances.
  *   Though the pipe offers no such constraints, for simplicity,
  * and flexibility, this implementation encodes all outbound
  * and inbound messages as JSONObjects that are sequenced by
  * the implementation and delivered to the receiving end's
  * event queue.
  *   If performance becomes a big enough concern eventually,
  * these messages should be standardized and serialized/deserialized
  * instead.
  */
public class WsPipe extends WebSocketListener {
    private static final String TAG = "WsPipe";
    private static final String WS_PROTOCOL = "wss"; // FIXME: use "wss" for production and "ws" for test.
    private static final int WS_PORT = 8443;
    private static ExecutorService ExecutorPool = Executors.newFixedThreadPool(1);
    private static MockWebServer Server = null;
    private static OkHttpClient Client = null;
    private static final ArrayList<WsPipe> WsPipes = new ArrayList<>();
    private static Handler MainThreadHandler;
    private static int WsPipeCount = 0;
    private static SSLSocketFactory ClientSslSocketFactory;
    private static SSLSocketFactory ServerSslSocketFactory;
    private static ServerSocket MyServerSocket;
    private static X509TrustManager ClientTrustManager;
    private final Util.SimpleMovingAverage peerSmaRtt = new Util.SimpleMovingAverage(20);
    private int sendMsgCount = 0;
    private String peerName;
    private WebSocket webSocket;
    private String wsHexStr; // hex representation of the socket identifier
    private WsMsgListener msgListener;

    // mutex to protect access by multiple threads.
    private final Object bgLock = new Object();
    private HashMap<Integer, WsOutboundMessage> outboundMessages = new HashMap<>(10);
    private static X509Certificate CaCert;
    private static KeyPair CaKeyPair;

    public int pendingResponseCount() { return outboundMessages.size(); }

/*
        // --- CLIENT-SIDE "TRUST ALL" SETUP ---
        if (null == Client) {
            try {
                // Create a trust manager that does not validate certificate chains
                final TrustManager[] trustAllCerts = new TrustManager[]{
                        new X509TrustManager() {
                            @Override
                            public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                            @Override
                            public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                            @Override
                            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                                return new java.security.cert.X509Certificate[]{};
                            }
                        }
                };

                // Install the all-trusting trust manager
                final SSLContext sslContext = SSLContext.getInstance("SSL");
                sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
                final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

                Client = new OkHttpClient.Builder()
                        .sslSocketFactory(sslSocketFactory, (X509TrustManager) trustAllCerts[0])
                        .hostnameVerifier((hostname, session) -> true)
                        .build();

            } catch (Exception e) {
                CTError(TAG, "FATAL: Failed to create 'trust-all' OkHttpClient.", e);
            }
        }
        }
 */

    private WsPipe(WsMsgListener listener) {
        WsPipeCount++;
        CTDebug(TAG, "XYZZY: WsPipeCount: " + WsPipeCount);
        CTDebug(TAG, "WsPipe(): setting listener to: " + listener.toString());
        msgListener = listener;
    }  // constructor used only by the server.

    // outbound pipe constructor
    public WsPipe(String ipaddr, WsMsgListener msgListener) {
        WsPipeCount++;
        CTDebug(TAG, "XYZZY: WsPipeCount: " + WsPipeCount);
        this.msgListener = msgListener;
        String url = String.format(Locale.US, "%s://%s:%d/R2CRestV1", WS_PROTOCOL, ipaddr, WS_PORT);
        CTDebug(TAG, "Trying to connect to: " + url);
        Request request = new Request.Builder()
                .url(url)
                .build();
        setSocket(Client.newWebSocket(request, this));
        WsPipes.add(this);
    }

    public void closeSocket(int code, String reason) {
        if (null == webSocket) return;
        synchronized (bgLock) {
            webSocket.close(code, reason);
            webSocket = null;
        }
    }

    private void setSocket(WebSocket socket) {
        synchronized (bgLock) {
            webSocket = socket;
            wsHexStr = String.format(Locale.US, "0x%x", System.identityHashCode(socket));
        }
    }

    public static void Shutdown() {
        while (!WsPipes.isEmpty()) {
            WsPipe pipe = WsPipes.remove(0);
            // Use 1000 for a normal closure.
            pipe.closeSocket(1000, "Activity stopped");
        }

        if (Server != null) try {
            Server.shutdown();
        } catch (Exception e) {
            CTError(TAG, "server.shutdown() raised.", e);
        }

        if (null != Client) try {
            Client.dispatcher().executorService().shutdown();
        } catch (Exception e) {
            CTError(TAG, "Client.shutdown() raised.", e);
        }
        if (null != ExecutorPool) ExecutorPool.shutdown();
    }

    protected void finalize() {
        WsPipeCount--;
        CTDebug(TAG, "XYZZY: WsPipeCount: " + WsPipeCount);
    }

    /** N.B. Must be called before any other interaction with this class.
     *
     */
    public static void Init () {
        CreateCerts();
        if (null == Client) {
            try {
                final TrustManager[] myTrustManager = new TrustManager[]{
                        new X509TrustManager() {
                            @Override
                            public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                                CTDebug(TAG, "In myTrustManager() checkClientTrusted()");
                            }

                            @Override
                            public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {
                                CTDebug(TAG, "In myTrustManager() checkServerTrusted()");
                                for (java.security.cert.X509Certificate serverCert : chain) {
                                    if (serverCert.equals(CaCert)) {
                                        CTDebug(TAG, "checkServerTrusted() Found our CA.");
                                        return;
                                    } else {
                                        // CTDebug(TAG, String.format(Locale.US, "checkServerTrusted() Rejecting %s authType for serverCert:'%s', looking for '%s'", authType, serverCert, CaCert));
                                        return;
                                    }
                                }
                                throw new CertificateException("checkServerTrusted() Did not find our CA.");
                            }

                            @Override
                            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                                CTDebug(TAG, "In myTrustManager() getAcceptedIssuers()");
                                return new java.security.cert.X509Certificate[]{CaCert};
                            }
                        }
                };
                final SSLContext sslContext = SSLContext.getInstance("SSL");
                sslContext.init(null, myTrustManager, new java.security.SecureRandom());
                final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

                // clientHandshake.sslSocketFactory()
                Client = new OkHttpClient.Builder()
                        .callTimeout(60, TimeUnit.SECONDS)
                        .connectTimeout(60, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS)
                        .writeTimeout(60, TimeUnit.SECONDS)
                        .sslSocketFactory(sslSocketFactory, (X509TrustManager) myTrustManager[0])
                        .hostnameVerifier((hostname, session) -> {
                            // The hostname should be the IP address from the certificate
                            // This check is now meaningful.
                            try {
                                // CTDebug(TAG, "okHttpClient.Builder(): made it to serverVerifier().");
                                X509Certificate cert = (X509Certificate) session.getPeerCertificates()[0];
                                // Check if the hostname (the IP we connected to) is in the cert's SAN list.
                                for (List<?> san : cert.getSubjectAlternativeNames()) {
                                    //CTDebug(TAG, "okHttpClient.Builder(): Checking rfc822Name: " + san.get(1));
                                    if (san.get(1).equals("kjtsar@gmail.com")) {
                                        CTDebug(TAG, "okHttpClient.Builder(): accepting rfc822Name: " + san.get(1));
                                        return true;  // FIXME: Until we can agree on underlying trust cert, this is better than nothing.
                                    } else {
                                        CTError(TAG, "okHttpClient.Builder(): rejecting: " + san.get(1));
                                    }
                                }
                            } catch (Exception e) {
                                CTError(TAG, "Server verification failed");
                            }
                            return false;
                        })
                        .build();

            } catch (Exception e) {
                CTError(TAG, "FATAL: Failed to create CA-trusting OkHttpClient.", e);
            }
        }
    }


    public static void StartServer(WsMsgListener msgListener) {
        if (null == msgListener) {
            CTError(TAG, "Can't start a server without a listener");
            return;
        }
        ExecutorPool.submit(() -> BgStartServer(msgListener));
    }

    @Nullable
    private static String getLocalIpAddress() {
        try {
            List<NetworkInterface> allNetworkInterfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface networkInterface : allNetworkInterfaces) {
                // We are looking for a non-loopback, active network interface
                if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;

                List<InetAddress> allInetAddresses = Collections.list(networkInterface.getInetAddresses());
                for (InetAddress address : allInetAddresses) {
                    // Find the first IPv4 address
                    if (!address.isLoopbackAddress() && address.getAddress().length == 4) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ex) {
            CTError(TAG, "Failed to get IP address", ex);
        }
        return null;
    }

    /** FIXME:
     * Gemini is truely a moron if this is the best idea it can come up with to build a trust relationship
     * between two application instances.  This will never work.   This app needs to rely on something built
     * external to the app for the trust relationship between the client and server.   Can we use an
     * externally generated keystore that is bound at compile time with password protected access to
     * maintain the fiction of security?
     */
    private static void CreateCaCert() {
        try {
            // 1. Generate a key pair for our new CA
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            CaKeyPair = keyPairGenerator.generateKeyPair();
            // 2. Define the CA's identity
            X500Name caName = new X500Name("CN=WsPipePrivateCA");

            // 3. Build the CA certificate
            // It's valid for 10 years and signs itself.
            X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                    caName, // Issuer (itself)
                    BigInteger.valueOf(System.currentTimeMillis()), // Serial Number
                    new java.util.Date(System.currentTimeMillis() - 1000L * 60 * 60 * 24), // Not Before
                    new java.util.Date(System.currentTimeMillis() + 1000L * 3600 * 24 * 365 * 10), // Not After (10 years)
                    caName, // Subject (itself)
                    CaKeyPair.getPublic()); // Public Key
            // Make it a CA certificate
            certBuilder.addExtension(org.bouncycastle.asn1.x509.Extension.basicConstraints, true, new org.bouncycastle.asn1.x509.BasicConstraints(true));

            ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256WithRSA").build(CaKeyPair.getPrivate());
            CaCert = new JcaX509CertificateConverter().getCertificate(certBuilder.build(contentSigner));

        } catch (Exception e) {
            CTError(TAG, "FATAL: Could not create private CA.", e);
        }
    }

    private static void CreateCerts() {
        try {
            if (CaCert == null) {
                // Ensure the CA is created first
                CreateCaCert();
            }

            // --- SERVER-SIDE CERTIFICATE CREATION ---

            // Generate a new key pair for the server
            KeyPair serverKeyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();

            // Build the server certificate, signed by the CA
            X509v3CertificateBuilder serverCertBuilder = new JcaX509v3CertificateBuilder(
                    new X500Name("CN=WsPipePrivateCA"), // The ISSUER is our CA
                    BigInteger.valueOf(System.currentTimeMillis()), // Serial Number
                    new java.util.Date(System.currentTimeMillis() - 1000L * 60 * 60 * 24), // Not Before
                    new java.util.Date(System.currentTimeMillis() + 1000L * 3600 * 24 * 365), // Not After (1 year)
                    new X500Name("CN=kjtsar@gmail.com"), // The SUBJECT is the user assigned device name
                    serverKeyPair.getPublic()); // Server's public key

            serverCertBuilder.addExtension(org.bouncycastle.asn1.x509.Extension.subjectAlternativeName,
                    false, new org.bouncycastle.asn1.x509.GeneralNames(
                            new org.bouncycastle.asn1.x509.GeneralName(
                                    org.bouncycastle.asn1.x509.GeneralName.rfc822Name, "kjtsar@gmail.com")));

            // Sign the new server cert with the CA's PRIVATE key
            ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256WithRSA").build(CaKeyPair.getPrivate());
            X509Certificate serverCert = new JcaX509CertificateConverter().getCertificate(serverCertBuilder.build(contentSigner));
            HeldCertificate heldCertificate = new HeldCertificate(serverKeyPair, serverCert);
            HandshakeCertificates serverHandshake = new HandshakeCertificates.Builder()
                    .heldCertificate(heldCertificate)
                    .build();
            ServerSslSocketFactory = serverHandshake.sslSocketFactory();

        } catch (Exception e) {
            CTError(TAG, "FATAL: Failed to create CA-signed server certificates.", e);
        }
    }

    private static void BgStartServer(WsMsgListener msgListener) {
        if (null != Server) {
            CTError(TAG, "BgStartServer(): Only one server supported today.");
            return;
        }
        if (null == Client) {
            CTError(TAG, "Can't start the server without first calling Init.");
            return;
        }

        MainThreadHandler = new Handler(Looper.getMainLooper());
        Dispatcher dispatcher = new Dispatcher() {
            @NonNull
            @Override
            public MockResponse dispatch(@NonNull RecordedRequest request) {
                WsPipe inbound = new WsPipe(msgListener);
                return new MockResponse().withWebSocketUpgrade(inbound);
            }
        };
        Server = new MockWebServer();
        Server.useHttps(ServerSslSocketFactory, false); // Use self-signed certs
        Server.setDispatcher(dispatcher);
        // Start the server on any address and specified port
        try {
            byte[] anyAddr = {0,0,0,0};
            Server.start(InetAddress.getByAddress(anyAddr), WS_PORT); // Example port
        } catch (Exception e) {
            CTError(TAG, "Server.start() raised: ", e);
        }
        CTDebug(TAG, WS_PROTOCOL + "Server started on: " + Server.url("/"));
    }

    public void setNewMsgListener(@NonNull WsMsgListener newMsgListener) {
        msgListener = newMsgListener;
    }

    public interface WsMsgListener {
        void newInboundConnection(@NonNull WsPipe wsPipe);

        void pipeIsClosing(@NonNull WsPipe wsPipe);

        void inboundMessage(@NonNull WsPipe wsPipe, @NonNull Integer seqnum, @NonNull JSONObject payload);

        void outboundResponse(@NonNull JSONObject payload, int tag, long avgRttInMsec);
    }

    /** send an outbound message asynchronously.
     * @param jsonPayload is arbitrary JSONObject content to be forwarded to remote.
     * @param tag user-defined argument that can be passed thru to outboundResponse.
     * @param bgResponseOk true means you're willing to accept response from background thread.
     */
    public void sendMessage(@NonNull JSONObject jsonPayload, int tag, boolean bgResponseOk) {
        if (null == webSocket) {
            CTError(TAG, "sendMessage(): Can't publish on a closed socket. Message ignored: " + jsonPayload);
            return;
        }
        if (pendingResponseCount() > 3) {
            // FIXME: Either we or our peer are suffering network connectivity problems.
            //        U/I should already make clear that no updates are coming in/going out
            //        of affected devices.
            CTDebug(TAG, String.format(Locale.US,
                    "Blocking further messages to %s due to outstanding responses.",
                    peerName));
            return;
        }


        WsOutboundMessage msg = new WsOutboundMessage(jsonPayload, this, tag, bgResponseOk);
        if (msg.seqnum == 1) {
            msg.msgOut.put("my-name", R2CActivity.MyDeviceName);
        }
        CTDebug(TAG, String.format(Locale.US, "[%s]%s-->%s: %s", wsHexStr, R2CActivity.MyDeviceName,
                (null != peerName) ? peerName : "<unknown>", msg.msgOut));
        webSocket.send(msg.msgOut.toString());
    }

    public static class WsOutboundMessage {
        private Integer seqnum;
        private Util.SafeJSONObject msgOut;
        private long sentTimestampMsec;
        private long recvTimestampMsec;
        int tag;
        private boolean bgResponseOk;
        public WsOutboundMessage(@NonNull JSONObject msgPayload, WsPipe wsPipe, int tag, boolean bgResponseOk) {
            seqnum = ++wsPipe.sendMsgCount;
            wsPipe.addOutboundMessage(this);
            this.tag = tag;
            this.bgResponseOk = bgResponseOk;
            msgOut = new Util.SafeJSONObject();
            msgOut.put( "seq", seqnum);
            msgOut.put("response", false);
            msgOut.put("payload", msgPayload);
            this.sentTimestampMsec = System.currentTimeMillis();
        }
        public long rttInMsec() {return recvTimestampMsec - sentTimestampMsec;}

    }

    private WsOutboundMessage removeOutboundMessage(Integer seqnum) {
        WsOutboundMessage msg;
        synchronized (bgLock) {
            msg = outboundMessages.remove(seqnum);
            if (null != msg) {
                msg.recvTimestampMsec = System.currentTimeMillis();
                if (msg.sentTimestampMsec < msg.recvTimestampMsec )
                    peerSmaRtt.next(msg.recvTimestampMsec - msg.sentTimestampMsec);
            }
        }
        if (null == msg) CTError(TAG, "Not able to find outbound message w/seq # " + seqnum.toString());
        return msg;
    }

    @NonNull
    public String getPeerName() {
        if (null == peerName) return "<unknown>";
        return peerName;
    }

    private void addOutboundMessage(WsOutboundMessage msg) {
        synchronized (bgLock) {
            outboundMessages.put(msg.seqnum, msg);
        }
    }

    public void sendResponse(@NonNull Integer seqnum, @NonNull JSONObject responseJson) {
        if (null == webSocket) {
            CTError(TAG, "sendResponse(): Can't publish on a closed socket.  Message ignored: " + responseJson);
            return;
        }

        Util.SafeJSONObject jo = new Util.SafeJSONObject();
        jo.put("seq", seqnum.toString());
        jo.put("response", true);
        jo.put("payload", responseJson);
        if (seqnum == 1) jo.put("my-name", R2CActivity.MyDeviceName);
        CTDebug(TAG, String.format(Locale.US, "[%s]%s-->%s: %s", wsHexStr, R2CActivity.MyDeviceName,
                (null != peerName) ? peerName : "<unknown>", jo));
        webSocket.send(jo.toString());
    }

    @Override
    public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
        setSocket(webSocket);
        // Called when the connection is successfully established.
        if (null == msgListener) {
            CTError(TAG, "onOpen(): Connection opened on server - no listener configured.");
            return;
        }
        CTDebug(TAG, "onOpen(" + wsHexStr + ")");
        MainThreadHandler.post(() -> msgListener.newInboundConnection(this));
    }

    @Override
    public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
        JSONObject payload;
        JSONObject jo;
        boolean responseFlag;
        Integer seqnum;
        try {
            jo = new JSONObject(text);
            payload = jo.optJSONObject("payload");
            responseFlag = jo.optBoolean("response");
            seqnum = jo.optInt("seq");
            if (null == peerName) peerName = jo.optString("my-name", null);
            CTDebug(TAG, String.format(Locale.US, "[%s]%s<--%s: %s", wsHexStr,
                    R2CActivity.MyDeviceName, (null != peerName) ? peerName : "<unknown>", jo));
        } catch (Exception e) {
            CTError(TAG, "onMessage(); Error parsing incoming message: " + text, e);
            return;
        }
        if (null == msgListener) {
            CTError(TAG, "onMessage() no listener for message - ignoring: " + text);
            return;
        }
        if (null == payload) {
            CTError(TAG, "onMessage(): missing required payload");
            return;
        }
        JSONObject finalPayload = payload;
        Integer finalSeqnum = seqnum;

        if (!responseFlag) {
            MainThreadHandler.post(() -> msgListener.inboundMessage(this, finalSeqnum, finalPayload));
            return;
        }
        WsOutboundMessage msg = removeOutboundMessage(seqnum);
        if (null == msg) {
            CTError(TAG, "received response to outbound message w/invalid seqnum: " + seqnum);
            return;
        }
        long rttInMsec = msg.rttInMsec();
        int msgTag = msg.tag;
        if (msg.bgResponseOk)
            msgListener.outboundResponse(payload, msgTag, rttInMsec);
        else
            MainThreadHandler.post(() -> msgListener.outboundResponse(finalPayload, msgTag, rttInMsec));
    }

    @Override
    public void onMessage(@NonNull WebSocket webSocket, @NonNull ByteString bytes) {
        // Called when a binary message is received.
        CTError(TAG, "Received bytes not implemented -yet: " + bytes.hex());
    }

    @Override
    public void onClosing(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
        // Called when the server is about to close the connection.
        CTDebug(TAG, String.format(Locale.US, "Connection to %s closing: %d/%s",peerName, code, reason));
        MainThreadHandler.post(() -> msgListener.pipeIsClosing(this));
        MainThreadHandler.post(() -> this.closeSocket(1000, "Received onClosing()."));

    }

    @Override
    public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
        // Called when the connection is fully closed.
        CTDebug(TAG, String.format(Locale.US, "Connection to %s closed: %d/%s",peerName, code, reason));
    }

    @Override
    public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
        // Called when the connection fails (e.g., network error).
        CTError(TAG, "Network Error: " + t.getMessage());
        MainThreadHandler.post(() -> msgListener.pipeIsClosing(this));
    }
}
