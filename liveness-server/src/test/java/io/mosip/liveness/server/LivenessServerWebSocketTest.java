package io.mosip.liveness.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LivenessServerWebSocketTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private LivenessServerApp serverApp;
    private Javalin app;
    private int port;

    @BeforeEach
    void setUp() {
        ServerConfig config = new ServerConfig(0, true, null, null, 30, true);
        serverApp = new LivenessServerApp(config);
        app = serverApp.createApp();
        app.start(0);
        port = app.port();
    }

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
        serverApp.stop();
    }

    @Test
    @DisplayName("WebSocket connection receives initial state and handles cancel message")
    void testWebSocketFlow() throws Exception {
        CompletableFuture<String> firstMessageFuture = new CompletableFuture<>();
        CompletableFuture<Integer> closeFuture = new CompletableFuture<>();

        WebSocket.Listener listener = new WebSocket.Listener() {
            private final StringBuilder sb = new StringBuilder();

            @Override
            public void onOpen(WebSocket webSocket) {
                webSocket.request(1);
                WebSocket.Listener.super.onOpen(webSocket);
            }

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                sb.append(data);
                if (last) {
                    firstMessageFuture.complete(sb.toString());
                    sb.setLength(0);
                }
                webSocket.request(1);
                return WebSocket.Listener.super.onText(webSocket, data, last);
            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                closeFuture.complete(statusCode);
                return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
            }
        };

        HttpClient client = HttpClient.newHttpClient();
        WebSocket ws = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/liveness?workflow=RESIDENT_AUTH&lang=en"), listener)
                .get(5, TimeUnit.SECONDS);

        // Verify initial state message
        String msg = firstMessageFuture.get(5, TimeUnit.SECONDS);
        JsonNode json = mapper.readTree(msg);
        assertThat(json.path("type").asText()).isEqualTo("state");
        assertThat(json.path("state").asText()).isEqualTo("FACE_POSITIONING");

        // Send cancel message
        ws.sendText("{\"type\":\"cancel\"}", true);

        // Wait for socket closure
        Integer status = closeFuture.get(5, TimeUnit.SECONDS);
        assertThat(status).isNotNull();
    }
}
