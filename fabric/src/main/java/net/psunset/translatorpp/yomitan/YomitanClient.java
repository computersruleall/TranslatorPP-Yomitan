package net.psunset.translatorpp.yomitan;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Talks to the player's own Yomitan through yomitan-api
 * (https://github.com/yomidevs/yomitan-api), a local HTTP bridge that the
 * browser extension starts when "Enable Yomitan API" is on.
 */
public final class YomitanClient {
    public static final URI BASE = URI.create("http://127.0.0.1:19633/");

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1) // the bridge is Python's http.server
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private YomitanClient() {
    }

    /**
     * Looks up the text the way Yomitan's popup does: entries whose headword
     * matches a prefix of {@code text}, deinflected, longest match first.
     */
    public static CompletableFuture<List<TermEntry>> termEntries(String text) {
        JsonObject body = new JsonObject();
        body.addProperty("term", text);
        return post("termEntries", body).thenApply(TermEntry::parseResponse);
    }

    public static CompletableFuture<JsonElement> post(String path, JsonObject body) {
        HttpRequest request = HttpRequest.newBuilder(BASE.resolve(path))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() / 100 != 2) {
                        throw new CompletionException(new IOException(
                                "Yomitan API HTTP " + response.statusCode() + ": " + response.body()));
                    }
                    return JsonParser.parseString(response.body());
                });
    }
}
