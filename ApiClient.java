import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * Sends the user's message to the FastAPI server and returns the bot's reply.
 *
 * Request  (POST):  {"message": "my wifi is broken", "session_id": "3f2a..."}
 * Response (200) :  {"reply": "Wi-Fi problem: ..."}
 *
 * If your FastAPI uses other field names, change the 3 constants below.
 */
public class ApiClient {

    private static final String REQUEST_FIELD = "message";
    private static final String SESSION_FIELD = "session_id";
    private static final String RESPONSE_FIELD = "reply";

    private final String url;
    private volatile String sessionId = UUID.randomUUID().toString();   // one id per conversation
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public ApiClient(String url) {
        this.url = url;
    }

    public String getUrl() {
        return url;
    }

    /** Starts a new conversation (new session id), used by the "New chat" button. */
    public void resetSession() {
        sessionId = UUID.randomUUID().toString();
    }

    /** Blocking call: never call it directly from the UI thread. */
    public String chat(String message) throws IOException, InterruptedException {
        String body = "{" + quote(REQUEST_FIELD) + ":" + quote(message) + ","
                + quote(SESSION_FIELD) + ":" + quote(sessionId) + "}";

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != 200) {
            throw new IOException("The server answered with HTTP " + response.statusCode()
                    + " (check the URL and the field names).");
        }

        String reply = extractString(response.body(), RESPONSE_FIELD);
        if (reply == null) {
            throw new IOException("The server answer has no \"" + RESPONSE_FIELD + "\" field.");
        }
        return reply;
    }

    // =====================================================================
    //  Tiny JSON helpers (so the project needs no external library)
    // =====================================================================

    /** Turns a Java String into a JSON string with quotes: He said "hi" -> "He said \"hi\"" */
    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** Reads the text value of a field, e.g. {"reply": "Hello"} with key "reply" -> Hello. */
    static String extractString(String json, String key) {
        String marker = "\"" + key + "\"";
        int i = json.indexOf(marker);
        if (i < 0) return null;
        i += marker.length();

        while (i < json.length() && (Character.isWhitespace(json.charAt(i)) || json.charAt(i) == ':')) i++;
        if (i >= json.length() || json.charAt(i) != '"') return null;
        i++;

        StringBuilder sb = new StringBuilder();
        while (i < json.length()) {
            char c = json.charAt(i++);
            if (c == '"') return sb.toString();
            if (c == '\\' && i < json.length()) {
                char n = json.charAt(i++);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (i + 4 <= json.length()) {
                            sb.append((char) Integer.parseInt(json.substring(i, i + 4), 16));
                            i += 4;
                        }
                        break;
                    default: sb.append(n);   // \" \\ \/
                }
            } else {
                sb.append(c);
            }
        }
        return null;
    }
}