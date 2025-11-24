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
import java.security.KeyStore;
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

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTError;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTWarn;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.ncssar.rid2caltopo.R;
import org.ncssar.rid2caltopo.app.R2CActivity;
import org.opendroneid.android.data.Util;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private static X509TrustManager ClientTrustManager;
    private final Util.SimpleMovingAverage peerSmaRtt = new Util.SimpleMovingAverage(20);
    private int pipeId;
    private int sendMsgCount = 0;
    private String peerName;
    private WebSocket webSocket;
    private WsMsgListener msgListener;

    // mutex to protect access by multiple threads.
    private final Object bgLock = new Object();
    private HashMap<Integer, WsOutboundMessage> outboundMessages = new HashMap<>(10);
    private static X509Certificate CaCert;
    private static KeyPair CaKeyPair;

    public int pendingResponseCount() { return outboundMessages.size(); }

    private WsPipe(@NonNull WsMsgListener listener) {
        WsPipeCount++;
        pipeId = WsPipeCount;
        CTDebug(TAG, String.format(Locale.US, "WsPipe(%d) Setting server listener to '%s'", pipeId, listener));
        msgListener = listener;
    }  // constructor used only by the server.

    // outbound pipe constructor
    public WsPipe(String ipaddr, WsMsgListener msgListener) {
        WsPipeCount++;
        pipeId = WsPipeCount;
        this.msgListener = msgListener;
        String url = String.format(Locale.US, "%s://%s:%d/R2CRestV1", WS_PROTOCOL, ipaddr, WS_PORT);
        CTDebug(TAG, String.format(Locale.US, "WsPipe(%d) Trying to connect to: '%s'", pipeId, url));
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
        synchronized (bgLock) {webSocket = socket;}
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
        CTDebug(TAG, String.format(Locale.US, "WsPipe(%d) Closing.", pipeId));
    }

    /** N.B. Must be called before any other interaction with this class.
     *
     */
    public static void Init () {
        if (null == Client) {
            try {
                // Load the keystore from the app's resources
                Context context = R2CActivity.getAppContext();
                InputStream keystoreInputStream = context.getResources().openRawResource(R.raw.keystore);
                KeyStore keyStore = KeyStore.getInstance("PKCS12");
                char[] password = "rid2caltopo".toCharArray(); // Replace with your actual password
                keyStore.load(keystoreInputStream, password);

                // Create a KeyManagerFactory to manage our private key
                KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyManagerFactory.init(keyStore, password);

                // Create a TrustManagerFactory to trust the certificates in our keystore
                TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trustManagerFactory.init(keyStore);

                // Create an SSLContext that uses our KeyManager and TrustManager
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);

                ClientSslSocketFactory = sslContext.getSocketFactory();
                ClientTrustManager = (X509TrustManager) trustManagerFactory.getTrustManagers()[0];

                Client = new OkHttpClient.Builder()
                        .sslSocketFactory(ClientSslSocketFactory, ClientTrustManager)
                        .hostnameVerifier((hostname, session) -> true) // For development, we can bypass hostname verification
                        .build();

            } catch (Exception e) {
                CTError(TAG, "FATAL: Failed to create OkHttpClient with custom keystore.", e);
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

    private static void BgStartServer(WsMsgListener msgListener) {
        if (null != Server) {
            CTWarn(TAG, "BgStartServer(): Only one server supported today.");
            return;
        }
        if (null == Client) {
            CTWarn(TAG, "Can't start the server without first calling Init.");
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
        Server.useHttps(ClientSslSocketFactory, false); // Use the same SSLSocketFactory as the client
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
            CTWarn(TAG, String.format(Locale.US,
                    "WsPipe(%d).sendMessage(): Can't publish on a closed socket. Message ignored: %s",
                    pipeId, jsonPayload));
            return;
        }
        if (pendingResponseCount() > 3) {
            // FIXME: Either we or our peer are suffering network connectivity problems.
            //        U/I should already make clear that no updates are coming in/going out
            //        of affected devices.
            CTDebug(TAG, String.format(Locale.US,
                    "WsPipe(%d).sendMessage(): Blocking further messages to %s due to outstanding responses.",
                    pipeId, peerName));
            return;
        }


        WsOutboundMessage msg = new WsOutboundMessage(jsonPayload, this, tag, bgResponseOk);
        if (msg.seqnum == 1) {
            msg.msgOut.put("my-name", R2CActivity.MyDeviceName);
        }
        CTDebug(TAG, String.format(Locale.US, "WsPipe(%d).sendMessage(%s-->%s): %s",
                pipeId, R2CActivity.MyDeviceName, (null != peerName) ? peerName : "<unknown>", msg.msgOut));
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
        if (null == msg) CTWarn(TAG, String.format(Locale.US,
                "WsPipe(%d): Not able to find outbound message w/seq # %d", pipeId, seqnum));
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
            CTWarn(TAG, String.format(Locale.US,
                    "WsPipe(%d).sendResponse(): Can't publish on a closed socket.  Message ignored:\n  %s",
                    pipeId, responseJson));
            return;
        }

        Util.SafeJSONObject jo = new Util.SafeJSONObject();
        jo.put("seq", seqnum.toString());
        jo.put("response", true);
        jo.put("payload", responseJson);
        if (seqnum == 1) jo.put("my-name", R2CActivity.MyDeviceName);
        CTDebug(TAG, String.format(Locale.US, "WsPipe(%d).sendResponse(%s-->%s): %s",
                pipeId, R2CActivity.MyDeviceName, (null != peerName) ? peerName : "<unknown>", jo));
        webSocket.send(jo.toString());
    }

    @Override
    public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
        setSocket(webSocket);
        // Called when the connection is successfully established.
        if (null == msgListener) {
            CTWarn(TAG, String.format(Locale.US,
                    "WsPipe(%d).onOpen(): Connection opened on server - no listener configured.", pipeId));
            return;
        }
        CTDebug(TAG, String.format(Locale.US, "WsPipe(%d).onOpen()", pipeId));
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
            CTDebug(TAG, String.format(Locale.US, "WsPipe(%d).onMessage(%s<--%s): %s", pipeId,
                    R2CActivity.MyDeviceName, peerName, jo));
        } catch (Exception e) {
            CTWarn(TAG, String.format(Locale.US, "WsPipe(%d).onMessage(); Error parsing incoming message: %s", pipeId, text), e);
            return;
        }
        if (null == msgListener) {
            CTWarn(TAG, String.format(Locale.US, "WsPipe(%d).onMessage() no listener for message - ignoring: %s", pipeId, text));
            return;
        }
        if (null == payload) {
            CTWarn(TAG, String.format(Locale.US, "WsPipe(%d).onMessage() missing required payload", pipeId));
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
            CTWarn(TAG, String.format(Locale.US, "WsPipe(%d).onMessage() response w/invalid seqnum: %d", pipeId, seqnum));
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
        CTWarn(TAG, String.format(Locale.US, "WsPipe(%d).onMessage() received bytes not implemented - yet", pipeId));
    }

    @Override
    public void onClosing(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
        // Called when the server is about to close the connection.
        CTWarn(TAG, String.format(Locale.US,
                "WsPipe(%d).onClosing() connection to '%s' closing. Reason:%d/%s",
                pipeId, peerName != null? peerName:"<unknown>", code, reason));
        MainThreadHandler.post(() -> msgListener.pipeIsClosing(this));
        MainThreadHandler.post(() -> this.closeSocket(1000, "Received onClosing()."));

    }

    @Override
    public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
        // Called when the connection is fully closed.
        CTDebug(TAG, String.format(Locale.US, "WsPipe(%d).onClosed() connection to '%s' closed.  Reason:%d/%s",
                pipeId, peerName != null? peerName:"<unknown>", code, reason));
    }

    @Override
    public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
        // Called when the connection fails (e.g., network error).
        CTWarn(TAG, String.format(Locale.US,
                "WsPipe(%d).onFailure() connection to '%s' closing. Reason: %s",
                pipeId, peerName != null? peerName:"<unknown>",  t));
        MainThreadHandler.post(() -> msgListener.pipeIsClosing(this));
    }
}
