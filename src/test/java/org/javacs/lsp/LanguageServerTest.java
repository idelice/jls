package org.javacs.lsp;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.javacs.Main;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class LanguageServerTest {
    PipedInputStream clientToServer = new PipedInputStream(10 * 1024 * 1024),
            serverToClient = new PipedInputStream(10 * 1024 * 1024);
    PipedOutputStream writeClientToServer, writeServerToClient;
    LanguageServer mockServer;
    Thread main;
    CompletableFuture<Void> receivedInitialize = new CompletableFuture<>();

    class TestLanguageServer extends LanguageServer {
        @Override
        public InitializeResult initialize(InitializeParams params) {
            receivedInitialize.complete(null);
            return new InitializeResult();
        }
    }

    class SequentialRequestServer extends TestLanguageServer {
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        final CountDownLatch handled = new CountDownLatch(2);

        @Override
        public Optional<Hover> hover(TextDocumentPositionParams params) {
            enter();
            try {
                sleepBriefly();
                return Optional.empty();
            } finally {
                exit();
            }
        }

        @Override
        public Optional<CompletionList> completion(TextDocumentPositionParams params) {
            enter();
            try {
                sleepBriefly();
                return Optional.of(new CompletionList(false, java.util.List.of()));
            } finally {
                exit();
            }
        }

        private void enter() {
            var active = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(active, Math::max);
        }

        private void exit() {
            inFlight.decrementAndGet();
            handled.countDown();
        }

        private void sleepBriefly() {
            try {
                Thread.sleep(120);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static {
        Main.setRootFormat();
    }

    @Before
    public void connectServerAndInitialize() throws IOException {
        writeClientToServer = new PipedOutputStream(clientToServer);
        writeServerToClient = new PipedOutputStream(serverToClient);
        main = new Thread(this::runServer, "runServer");
        main.start();
    }

    @After
    public void cleanup() throws IOException {
        writeClientToServer.close();
        writeServerToClient.close();
        clientToServer.close();
        serverToClient.close();
    }

    private void runServer() {
        LSP.connect(this::serverFactory, clientToServer, writeServerToClient);
    }

    private LanguageServer serverFactory(LanguageClient client) {
        if (mockServer != null) {
            return mockServer;
        }
        mockServer = new TestLanguageServer();
        return mockServer;
    }

    private void sendToServer(String message) throws IOException {
        var header = String.format("Content-Length: %d\r\n\r\n", message.getBytes().length);
        writeClientToServer.write(header.getBytes());
        writeClientToServer.write(message.getBytes());
    }

    String initializeMessage = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
    String exitMessage = "{\"jsonrpc\":\"2.0\",\"method\":\"exit\"}";

    @Test
    public void exitMessageKillsServer()
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        // Send initialize message and wait for ack
        sendToServer(initializeMessage);
        receivedInitialize.get(10, TimeUnit.SECONDS);
        // Send exit message and wait for exit
        sendToServer(exitMessage);
        main.join(10_000);
        assertThat("Main thread has quit", main.isAlive(), equalTo(false));
    }

    @Test
    public void endOfStreamKillsServer()
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        // Send initialize message and wait for ack
        sendToServer(initializeMessage);
        receivedInitialize.get(10, TimeUnit.SECONDS);
        // Close stream
        writeClientToServer.close();
        clientToServer.close();
        // Wait for exit
        main.join(10_000);
        assertThat("Main thread has quit", main.isAlive(), equalTo(false));
    }

    @Test
    public void requestDispatchIsSequential() throws Exception {
        mockServer = new SequentialRequestServer();
        sendToServer(initializeMessage);
        receivedInitialize.get(10, TimeUnit.SECONDS);

        sendToServer(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/hover\",\"params\":{\"textDocument\":{\"uri\":\"file:///Test.java\"},\"position\":{\"line\":0,\"character\":0}}}");
        sendToServer(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"textDocument/completion\",\"params\":{\"textDocument\":{\"uri\":\"file:///Test.java\"},\"position\":{\"line\":0,\"character\":0}}}");

        var server = (SequentialRequestServer) mockServer;
        assertThat("both requests should complete", server.handled.await(10, TimeUnit.SECONDS), equalTo(true));
        assertThat("LSP request processing should stay single-threaded", server.maxInFlight.get(), equalTo(1));
    }
}
