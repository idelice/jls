package org.javacs.lsp;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LSP {
    private static final Gson gson = new Gson();

    private static String readHeader(InputStream client) {
        var line = new StringBuilder();
        for (var next = read(client); true; next = read(client)) {
            if (next == '\r') {
                var last = read(client);
                assert last == '\n';
                break;
            }
            line.append(next);
        }
        return line.toString();
    }

    private static int parseHeader(String header) {
        var contentLength = "Content-Length: ";
        if (header.startsWith(contentLength)) {
            var tail = header.substring(contentLength.length());
            var length = Integer.parseInt(tail);
            return length;
        }
        return -1;
    }

    static class EndOfStream extends RuntimeException {}

    // TODO this seems like it's probably really inefficient. Read in bulk?
    private static char read(InputStream client) {
        try {
            var c = client.read();
            if (c == -1) {
                LOG.warning("Stream from client has been closed, throwing kill exception...");
                throw new EndOfStream();
            }
            return (char) c;
        } catch (IOException e) {
            LOG.log(Level.SEVERE, e.getMessage(), e);
            throw new EndOfStream();
        }
    }

    private static String readLength(InputStream client, int byteLength) {
        // Eat whitespace
        try {
            return new String(client.readNBytes(byteLength), StandardCharsets.UTF_8).stripLeading();
        } catch (IOException e) {
            throw new RuntimeException("An error occurred during the reading of client data", e);
        }
    }

    static String nextToken(InputStream client) {
        var contentLength = -1;
        while (true) {
            var line = readHeader(client);
            // If header is empty, next line is the start of the message
            if (line.isEmpty()) return readLength(client, contentLength);
            // If header contains length, save it
            var maybeLength = parseHeader(line);
            if (maybeLength != -1) contentLength = maybeLength;
        }
    }

    static Message parseMessage(String token) {
        return gson.fromJson(token, Message.class);
    }

    private static final Charset UTF_8 = StandardCharsets.UTF_8;

    private static final java.util.concurrent.ExecutorService workerPool = java.util.concurrent.Executors.newCachedThreadPool();
    private static final java.util.concurrent.ConcurrentHashMap<Integer, java.util.concurrent.CompletableFuture<?>> activeRequests = new java.util.concurrent.ConcurrentHashMap<>();

    private static void writeClient(OutputStream client, String messageText) {
        var messageBytes = messageText.getBytes(UTF_8);
        var headerText = String.format("Content-Length: %d\r\n\r\n", messageBytes.length);
        var headerBytes = headerText.getBytes(UTF_8);
        synchronized (client) {
            try {
                client.write(headerBytes);
                client.write(messageBytes);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    static String toJson(Object message) {
        return gson.toJson(message);
    }

    private static <T, R> void asyncRequest(
            OutputStream client,
            Message message,
            Class<T> paramsClass,
            Function<T, R> handler) {
        try {
            var params = gson.fromJson(message.params, paramsClass);
            var future = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                if (Thread.currentThread().isInterrupted()) return null;
                return handler.apply(params);
            }, workerPool);
            if (message.id != null) {
                activeRequests.put(message.id, future);
            }
            future.thenAccept(result -> {
                        if (message.id != null) activeRequests.remove(message.id);
                        respond(client, message.id, result);
                    })
                    .exceptionally(
                            ex -> {
                                if (message.id != null) activeRequests.remove(message.id);
                                var cause = ex instanceof java.util.concurrent.CompletionException ? ex.getCause() : ex;
                                if (cause instanceof java.util.concurrent.CancellationException) {
                                    LOG.info(String.format("Request %d was cancelled", message.id));
                                } else {
                                    LOG.log(Level.SEVERE, cause.getMessage(), cause);
                                    error(
                                            client,
                                            message.id,
                                            new ResponseError(ErrorCodes.InternalError, cause.getMessage(), null));
                                }
                                return null;
                            });
        } catch (Exception e) {
            LOG.log(Level.SEVERE, e.getMessage(), e);
            error(client, message.id, new ResponseError(ErrorCodes.InternalError, e.getMessage(), null));
        }
    }

    @SuppressWarnings("unchecked")
    static void respond(OutputStream client, int requestId, Object params) {
        if (params instanceof ResponseError) {
            throw new RuntimeException("Errors should be sent using LSP.error(...)");
        }
        if (params instanceof Optional) {
            var option = (Optional) params;
            params = option.orElse(null);
        }
        var jsonText = toJson(params);
        var messageText = String.format("{\"jsonrpc\":\"2.0\",\"id\":%d,\"result\":%s}", requestId, jsonText);
        writeClient(client, messageText);
    }

    static void error(OutputStream client, int requestId, ResponseError error) {
        var jsonText = toJson(error);
        var messageText = String.format("{\"jsonrpc\":\"2.0\",\"id\":%d,\"error\":%s}", requestId, jsonText);
        writeClient(client, messageText);
    }

    @SuppressWarnings("unchecked")
    private static void notifyClient(OutputStream client, String method, Object params) {
        if (params instanceof Optional) {
            var option = (Optional) params;
            params = option.orElse(null);
        }
        var jsonText = toJson(params);
        var messageText = String.format("{\"jsonrpc\":\"2.0\",\"method\":\"%s\",\"params\":%s}", method, jsonText);
        writeClient(client, messageText);
    }

    private static class RealClient implements LanguageClient {
        final OutputStream send;

        RealClient(OutputStream send) {
            this.send = send;
        }

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams params) {
            notifyClient(send, "textDocument/publishDiagnostics", params);
        }

        @Override
        public void showMessage(ShowMessageParams params) {
            notifyClient(send, "window/showMessage", params);
        }

        @Override
        public void registerCapability(String method, JsonElement options) {
            var params = new RegistrationParams();
            var registration = new RegistrationParams.Registration();
            registration.id = UUID.randomUUID().toString();
            registration.method = method;
            registration.registerOptions = options;
            params.registrations.add(registration);
            var jsonText = toJson(params);
            var requestMethod = "client/registerCapability";
            // The request should contain the id param. Otherwise, it will be considered a notification.
            var id = new Random().nextInt();
            var messageText =
                    String.format(
                            "{\"jsonrpc\":\"2.0\",\"id\":\"%d\",\"method\":\"%s\",\"params\":%s}",
                            id, requestMethod, jsonText);
            writeClient(send, messageText);
        }

        @Override
        public void customNotification(String method, JsonElement params) {
            notifyClient(send, method, params);
        }

        @Override
        public void workDoneProgressCreate(Object token) {
            var params = new WorkDoneProgressCreateParams();
            params.token = token;
            var jsonText = toJson(params);
            var method = "window/workDoneProgress/create";
            var id = new Random().nextInt();
            var messageText =
                    String.format(
                            "{\"jsonrpc\":\"2.0\",\"id\":\"%d\",\"method\":\"%s\",\"params\":%s}",
                            id, method, jsonText);
            writeClient(send, messageText);
        }

        @Override
        public void workDoneProgressNotify(ProgressParams params) {
            notifyClient(send, "$/progress", params);
        }
    }

    public static void connect(
            Function<LanguageClient, LanguageServer> serverFactory, InputStream receive, OutputStream send) {
        var server = serverFactory.apply(new RealClient(send));
        var pending = new ArrayBlockingQueue<Message>(10);
        var endOfStream = new Message();

        // Read messages and process cancellations on a separate thread
        class MessageReader implements Runnable {
            void peek(Message message) {
                if ("$/cancelRequest".equals(message.method)) {
                    var params = gson.fromJson(message.params, CancelParams.class);
                    var removed = pending.removeIf(r -> r.id != null && r.id.equals(params.id));
                    var active = activeRequests.remove(params.id);
                    if (removed) {
                        LOG.info(String.format("Cancelled request %d, which had not yet started", params.id));
                    } else if (active != null) {
                        active.cancel(true);
                        LOG.info(String.format("Cancelled request %d, which was in-progress", params.id));
                    } else {
                        LOG.info(String.format("Cannot cancel request %d because it is not active", params.id));
                    }
                }
            }

            private boolean kill() {
                LOG.info("Read stream has been closed, putting kill message onto queue...");
                try {
                    pending.put(endOfStream);
                    return true;
                } catch (Exception e) {
                    LOG.log(Level.SEVERE, "Failed to put kill message onto queue, will try again...", e);
                    return false;
                }
            }

            @Override
            public void run() {
                LOG.info("Placing incoming messages on queue...");

                while (true) {
                    try {
                        var token = nextToken(receive);
                        var message = parseMessage(token);
                        peek(message);
                        pending.put(message);
                    } catch (EndOfStream __) {
                        if (kill()) return;
                    } catch (Exception e) {
                        LOG.log(Level.SEVERE, e.getMessage(), e);
                    }
                }
            }
        }
        Thread reader = new Thread(new MessageReader(), "reader");
        reader.setDaemon(true);
        reader.start();

        // Process messages on main thread
        LOG.info("Reading messages from queue...");
        var hasAsyncWork = false;
        processMessages:
        while (true) {
            Message r;
            try {
                // Take a break periodically
                r = pending.poll(200, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                LOG.log(Level.SEVERE, e.getMessage(), e);
                continue;
            }
            // If receive has been closed, exit
            if (r == endOfStream) {
                LOG.warning("Stream from client has been closed, exiting...");
                break processMessages;
            }
            // If poll(_) failed, loop again
            if (r == null) {
                if (hasAsyncWork) {
                    server.doAsyncWork();
                    hasAsyncWork = false;
                }
                continue;
            }
            // Otherwise, process the new message
            hasAsyncWork = true;
            if (r.method == null) {
                LOG.fine(String.format("Ignoring response id %s with no method", r.id));
                continue;
            }
            try {
                switch (r.method) {
                    case "initialize":
                        {
                            var params = gson.fromJson(r.params, InitializeParams.class);
                            var response = server.initialize(params);
                            respond(send, r.id, response);
                            break;
                        }
                    case "initialized":
                        {
                            server.initialized();
                            break;
                        }
                    case "shutdown":
                        {
                            LOG.warning("Got shutdown message");
                            respond(send, r.id, null);
                            break;
                        }
                    case "exit":
                        {
                            LOG.warning("Got exit message, exiting...");
                            break processMessages;
                        }
                    case "workspace/didChangeWorkspaceFolders":
                        {
                            var params = gson.fromJson(r.params, DidChangeWorkspaceFoldersParams.class);
                            server.didChangeWorkspaceFolders(params);
                            break;
                        }
                    case "workspace/didChangeConfiguration":
                        {
                            var params = gson.fromJson(r.params, DidChangeConfigurationParams.class);
                            server.didChangeConfiguration(params);
                            break;
                        }
                    case "workspace/didChangeWatchedFiles":
                        {
                            var params = gson.fromJson(r.params, DidChangeWatchedFilesParams.class);
                            server.didChangeWatchedFiles(params);
                            break;
                        }
                    case "workspace/symbol":
                        {
                            asyncRequest(send, r, WorkspaceSymbolParams.class, server::workspaceSymbols);
                            break;
                        }
                    case "textDocument/documentLink":
                        {
                            asyncRequest(send, r, DocumentLinkParams.class, server::documentLink);
                            break;
                        }
                    case "textDocument/didOpen":
                        {
                            var params = gson.fromJson(r.params, DidOpenTextDocumentParams.class);
                            server.didOpenTextDocument(params);
                            break;
                        }
                    case "textDocument/didChange":
                        {
                            var params = gson.fromJson(r.params, DidChangeTextDocumentParams.class);
                            server.didChangeTextDocument(params);
                            break;
                        }
                    case "textDocument/willSave":
                        {
                            var params = gson.fromJson(r.params, WillSaveTextDocumentParams.class);
                            server.willSaveTextDocument(params);
                            break;
                        }
                    case "textDocument/willSaveWaitUntil":
                        {
                            asyncRequest(send, r, WillSaveTextDocumentParams.class, server::willSaveWaitUntilTextDocument);
                            break;
                        }
                    case "textDocument/didSave":
                        {
                            var params = gson.fromJson(r.params, DidSaveTextDocumentParams.class);
                            server.didSaveTextDocument(params);
                            break;
                        }
                    case "textDocument/didClose":
                        {
                            var params = gson.fromJson(r.params, DidCloseTextDocumentParams.class);
                            server.didCloseTextDocument(params);
                            break;
                        }
                    case "textDocument/completion":
                        {
                            asyncRequest(send, r, TextDocumentPositionParams.class, server::completion);
                            break;
                        }
                    case "completionItem/resolve":
                        {
                            asyncRequest(send, r, CompletionItem.class, server::resolveCompletionItem);
                            break;
                        }
                    case "textDocument/hover":
                        {
                            asyncRequest(send, r, TextDocumentPositionParams.class, server::hover);
                            break;
                        }
                    case "textDocument/signatureHelp":
                        {
                            asyncRequest(send, r, TextDocumentPositionParams.class, server::signatureHelp);
                            break;
                        }
                    case "textDocument/inlayHint":
                        {
                            asyncRequest(send, r, InlayHintParams.class, server::inlayHint);
                            break;
                        }
                    case "textDocument/semanticTokens/full":
                        {
                            asyncRequest(send, r, SemanticTokensParams.class, server::semanticTokensFull);
                            break;
                        }
                    case "textDocument/definition":
                        {
                            asyncRequest(send, r, TextDocumentPositionParams.class, server::gotoDefinition);
                            break;
                        }
                    case "textDocument/references":
                        {
                            asyncRequest(send, r, ReferenceParams.class, server::findReferences);
                            break;
                        }
                    case "textDocument/documentSymbol":
                        {
                            asyncRequest(send, r, DocumentSymbolParams.class, server::documentSymbol);
                            break;
                        }
                    case "textDocument/codeAction":
                        {
                            asyncRequest(send, r, CodeActionParams.class, server::codeAction);
                            break;
                        }
                    case "codeAction/resolve":
                        {
                            asyncRequest(send, r, CodeAction.class, server::codeActionResolve);
                            break;
                        }
                    case "textDocument/codeLens":
                        {
                            asyncRequest(send, r, CodeLensParams.class, server::codeLens);
                            break;
                        }
                    case "codeLens/resolve":
                        {
                            asyncRequest(send, r, CodeLens.class, server::resolveCodeLens);
                            break;
                        }
                    case "textDocument/prepareRename":
                        {
                            asyncRequest(send, r, TextDocumentPositionParams.class, server::prepareRename);
                            break;
                        }
                    case "textDocument/rename":
                        {
                            asyncRequest(send, r, RenameParams.class, server::rename);
                            break;
                        }
                    case "textDocument/formatting":
                        {
                            asyncRequest(send, r, DocumentFormattingParams.class, server::formatting);
                            break;
                        }
                    case "textDocument/foldingRange":
                        {
                            asyncRequest(send, r, FoldingRangeParams.class, server::foldingRange);
                            break;
                        }
                    case "$/cancelRequest":
                        // Already handled in peek(message)
                        break;
                    default:
                        LOG.warning(String.format("Don't know what to do with method `%s`", r.method));
                }
            } catch (Exception e) {
                LOG.log(Level.SEVERE, e.getMessage(), e);
                if (r.id != null) {
                    error(send, r.id, new ResponseError(ErrorCodes.InternalError, e.getMessage(), null));
                }
            }
        }
    }

    private static final Logger LOG = Logger.getLogger("main");
}
