/*
 * Copyright (c) 2017 Sergey Chernov, iCodici S.n.C, All Rights Reserved
 *
 * Written by Sergey Chernov <real.sergeych@gmail.com>, August 2017.
 *
 */

package com.icodici.universa.node.network;

import com.icodici.crypto.*;
import com.icodici.universa.ErrorRecord;
import com.icodici.universa.Errors;
import com.icodici.universa.node.LocalNode;
import com.icodici.universa.node.network.microhttpd.InMemoryTempFile;
import com.icodici.universa.node.network.microhttpd.MicroHTTPD;
import net.sergeych.boss.Boss;
import net.sergeych.tools.Binder;
import net.sergeych.tools.Do;
import net.sergeych.utils.LogPrinter;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.nanohttpd.protocols.http.HTTPSession;
import org.nanohttpd.protocols.http.IHTTPSession;
import org.nanohttpd.protocols.http.response.IStatus;
import org.nanohttpd.protocols.http.response.Response;
import org.nanohttpd.protocols.http.response.Status;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.Arrays.asList;

/**
 * HTTP endpoint for client requests.
 * <p>
 * Key authentication, two steps, client calls serber:
 * <p>
 * connect(my_public_key, client_salt) -> server_nonce get_token(signed(my_public_key, server_nonce, client_nonce)) ->
 * signed(node_key, server_nonce, encrypted(my_public_key, session_key))
 * <p>
 * Threadpool is used, and controlled by setting THREAD_LIMIT to some specific value,
 * or to null for CachedThreadPool.
 */
public class ClientEndpoint {

    static private LogPrinter log = new LogPrinter("CLEP");

    /**
     * Any uploads larger than HARD_UPLOAD_LIMIT (verified against "content-length" header)
     * will be forcibly cut off.
     */
    private static final long HARD_UPLOAD_LIMIT = 2 * 1024 * 1024;
    /**
     * Limit of threads in threadpool; set to null for no limit (to use CachedThreadPool).
     * */
    private static final Integer THREAD_LIMIT = 16;

    public void changeKeyFor(PublicKey clientKey) throws EncryptionError {
        Session session = sessionsByKey.get(clientKey);
        if (session != null)
            session.sessionKey = null;
    }

    private interface Implementor {
        Binder apply(Session session) throws Exception;
    }

    private Server instance;
    private final Map<String, NetConfig.NodeInfo> roster;
    private LocalNode localNode;
    private int port;
    private PrivateKey myKey;

    public void shutdown() {
        if (instance != null)
            synchronized (instance) {
                instance.shutdown();
                instance = null;
            }
    }

    public class Server extends MicroHTTPD {
        Server(int portToListen) {
            super(portToListen, THREAD_LIMIT);
        }

        class BinaryResponse extends Response {
            public BinaryResponse(IStatus status, byte[] data) {
                super(status,
                        "application/octet-stream",
                        new ByteArrayInputStream(data),
                        data.length
                );
            }
        }

        @Override
        public Response handle(IHTTPSession session) {
            Map<String, String> filesMap = new HashMap<>();
            long bodySize = ((HTTPSession) session).getBodySize();
            if (bodySize > HARD_UPLOAD_LIMIT) {
                return errorResponse(
                        Status.NOT_ACCEPTABLE,
                        new ErrorRecord(Errors.FAILURE, "", String.format("Body too large: %s, while maximum allowed is %s", bodySize, HARD_UPLOAD_LIMIT)));
            } else {
                try {
                    session.parseBody(filesMap);
                    String tempFileName = filesMap.get("requestData");
                    if (tempFileName == null) {
                        return errorResponse(
                                Status.OK,
                                new ErrorRecord(Errors.FAILURE, "", "No requestData")
                        );
                    }

                    @Nullable InMemoryTempFile tempFile = InMemoryTempFile.getFileByName(tempFileName);
                    if (tempFile == null) {
                        return errorResponse(
                                Status.OK,
                                new ErrorRecord(Errors.FAILURE, "", "tempfile missing")
                        );
                    }
                    byte[] data = Do.read(tempFile.getInputByteStream());
                    Binder params = Boss.unpack(data);
                    return processRequest(session.getUri(), params);

                } catch (IOException | ResponseException e) {
                    log.wtf("Problem in client endpoint handle", e);
                    return errorResponse(e);
                }
            }
        }

        private Binder inSession(PublicKey key, Implementor function) throws EncryptionError {
            return inSession(getSession(key), function);
        }

        private Binder inSession(Session s, Implementor processor) {
            synchronized (s) {
                try {
                    s.errors.clear();
                    return s.answer(processor.apply(s));
                } catch (ClientError e) {
                    s.errors.add(e.errorRecord);
                } catch (Exception e) {
                    s.errors.add(new ErrorRecord(Errors.FAILURE, "", e.getMessage()));
                }
                return s.answer(null);
            }
        }

        private Binder inSession(long id, Implementor processor) {
            Session s = sessionsById.get(id);
            if (s == null)
                throw new IllegalArgumentException("bad session number");
            return inSession(s, processor);
        }

        private Response processRequest(String uri, Binder params) {
            try {
                final Binder result;
                switch (uri) {
                    case "/ping":
                        result = Binder.fromKeysValues("ping", "pong");
                        result.putAll(params);
                        break;
                    case "/network":
                        result = getNetworkDirectory();
                        break;
                    case "/connect":
                        try {
                            PublicKey clientKey = new PublicKey(params.getBinaryOrThrow("client_key"));
                            result = inSession(clientKey, session -> session.connect());
                        } catch (Exception e) {
                            return errorResponse(
                                    Status.OK,
                                    new ErrorRecord(Errors.BAD_CLIENT_KEY,
                                            "client_key",
                                            e.getMessage()
                                    )
                            );
                        }
                        break;
                    case "/get_token":
                        result = inSession(params.getLongOrThrow("session_id"), s -> s.getToken(params));
                        break;
                    case "/command":
                        result = inSession(params.getLongOrThrow("session_id"), s -> s.command(params));
                        break;
                    default:
                        return errorResponse(
                                Status.OK,
                                new ErrorRecord(Errors.UNKNOWN_COMMAND,
                                        "uri",
                                        "command not supported: " + uri
                                )
                        );
                }
                return makeResponse(Status.OK, result);
            } catch (Exception e) {
                return errorResponse(e);
            }
        }

        private void shutdown() {
            closeAllConnections();
            stop();
        }

        private Response errorResponse(IStatus code, ErrorRecord er) {
            return reponseKeysValues(code, "errors", asList(er)
            );
        }

        private Response errorResponse(Throwable t) {
            log.wtf("Error response", t);
            return errorResponse(
                    Status.OK,
                    new ErrorRecord(Errors.FAILURE, "", t.getMessage())
            );
        }

        private Response reponseKeysValues(IStatus status, Object... data) {
            return makeResponse(status, Binder.fromKeysValues(data));
        }

        private Response makeResponse(IStatus status, Binder data) {
            return new BinaryResponse(status, Boss.pack(data));
        }
    }


    private Binder networkDirectory = null;
    private Object networkDirectoryLock = new Object();

    private Binder getNetworkDirectory() {
        if (null != networkDirectory) {
            return networkDirectory;
        }
        synchronized (networkDirectoryLock) {
            if (null == networkDirectory) {
                Binder network = new Binder();
                roster.values().forEach(ni -> {
                    network.put(ni.getNodeId(),
                            Binder.fromKeysValues(
                                    "port", ni.getClientPort(),
                                    "ip", ni.getHost(),
                                    "key", ni.getPackedPublicKey()
                            )
                    );
                    networkDirectory = network;
                });
            }
            return networkDirectory;
        }
    }

    private AtomicLong sessionIds = new AtomicLong(
            ZonedDateTime.now().toEpochSecond() +
                    Do.randomInt(0x7FFFffff));

    private class Session {

        private PublicKey publicKey;
        private SymmetricKey sessionKey;
        private byte[] serverNonce;
        private byte[] encryptedAnswer;
        private long sessionId = sessionIds.incrementAndGet();


        Session(PublicKey key) throws EncryptionError {
            publicKey = key;
        }

        private void createSessionKey() throws EncryptionError {
            if (sessionKey == null) {
                sessionKey = new SymmetricKey();
                Binder data = Binder.fromKeysValues(
                        "sk", sessionKey.pack()
                );
                encryptedAnswer = publicKey.encrypt(Boss.pack(data));
            }
        }

        Binder connect() {
            if (serverNonce == null)
                serverNonce = Do.randomBytes(48);
            return Binder.fromKeysValues(
                    "server_nonce", serverNonce,
                    "session_id", sessionId
            );
        }

        Binder getToken(Binder data) {
            // Check the answer is properly signed
            byte[] signedAnswer = data.getBinaryOrThrow("data");
            try {
                if (publicKey.verify(signedAnswer, data.getBinaryOrThrow("signature"), HashType.SHA512)) {
                    Binder params = Boss.unpack(signedAnswer);
                    // now we can check the results
                    if (!Arrays.equals(params.getBinaryOrThrow("server_nonce"), serverNonce))
                        addError(Errors.BAD_VALUE, "server_nonce", "does not match");
                    else {
                        // Nonce is ok, we can return session token
                        createSessionKey();
                        Binder result = Binder.fromKeysValues(
                                "client_nonce", params.getBinaryOrThrow("client_nonce"),
                                "encrypted_token", encryptedAnswer
                        );
                        byte[] packed = Boss.pack(result);
                        return Binder.fromKeysValues(
                                "data", packed,
                                "signature", myKey.sign(packed, HashType.SHA512)
                        );
                    }
                }
            } catch (Exception e) {
                addError(Errors.BAD_VALUE, "signed_data", "wrong or tampered data block:" + e.getMessage());
            }
            return null;
        }

        private Binder answer(Binder result) {
            if (result == null)
                result = new Binder();
            if (!errors.isEmpty()) {
                result.put("errors", errors);
            }
            return result;
        }

        private void addError(Errors code, String object, String message) {
            errors.add(new ErrorRecord(code, object, message));
        }

        private List<ErrorRecord> errors = Collections.synchronizedList(new ArrayList<>());

        public Binder command(Binder params) throws ClientError, EncryptionError {
            // decrypt params and execute command
            Binder result = null;
            try {
                result = Binder.fromKeysValues(
                        "result",
                        executeAuthenticatedCommand(
                                Boss.unpack(
                                        sessionKey.decrypt(params.getBinaryOrThrow("params"))
                                )
                        )
                );
            } catch (Exception e) {
                ErrorRecord r = (e instanceof ClientError) ? ((ClientError) e).getErrorRecord() :
                        new ErrorRecord(Errors.COMMAND_FAILED, "", e.getMessage());
                result = Binder.fromKeysValues(
                        "error", r
                );
            }
            // encrypt and return result
            return Binder.fromKeysValues(
                    "result",
                    sessionKey.encrypt(Boss.pack(result))
            );
        }

        private Binder executeAuthenticatedCommand(Binder params) throws ClientError {
            String cmd = params.getStringOrThrow("command");
            try {
                switch (cmd) {
                    case "hello":
                        return Binder.fromKeysValues(
                                "status", "OK",
                                "message", "welcome to the Universa"
                        );
                    case "sping":
                        return Binder.fromKeysValues("sping", "spong");

                    case "test_error":
                        throw new IllegalAccessException("sample error");
                }
//            } catch (ClientError e) {
//                throw e;
            } catch (Exception e) {
                throw new ClientError(Errors.COMMAND_FAILED, cmd, e.getMessage());
            }
            throw new ClientError(Errors.UNKNOWN_COMMAND, "command", "unknown: " + cmd);
        }
    }

    ConcurrentHashMap<PublicKey, Session> sessionsByKey = new ConcurrentHashMap<>();
    ConcurrentHashMap<Long, Session> sessionsById = new ConcurrentHashMap<>();

    @NonNull
    private Session getSession(PublicKey key) throws EncryptionError {
        synchronized (sessionsByKey) {
            Session r = sessionsByKey.get(key);
            if (r == null) {
                r = new Session(key);
                sessionsByKey.put(key, r);
                sessionsById.put(r.sessionId, r);
            }
            return r;
        }
    }


    public ClientEndpoint(PrivateKey privateKey, int port, LocalNode localNode, Map<String, NetConfig.NodeInfo> roster) throws IOException {
        this.port = port;
        this.roster = roster;
        instance = new Server(port);
        this.localNode = localNode;
        instance.start();
        myKey = privateKey;
    }

    static public class ClientError extends IOException {
        public ErrorRecord getErrorRecord() {
            return errorRecord;
        }

        private final ErrorRecord errorRecord;

        public ClientError(ErrorRecord er) {
            super(er.toString());
            this.errorRecord = er;
        }

        public ClientError(Errors code, String object, String message) {
            this(new ErrorRecord(code, object, message));
        }

        @Override
        public String toString() {
            return "ClientError: " + errorRecord;
        }
    }
}
