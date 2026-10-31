package net.psunset.translatorpp.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.psunset.translatorpp.api.IServiceProvider;
import net.psunset.translatorpp.config.TPPConfig;
import net.psunset.translatorpp.exception.ServiceException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Azure Translator v3 with the player's own key. Source {@code ja-Latn} means
 * romaji: Japanese written in Latin letters, which Azure accepts through its
 * {@code fromScript} parameter.
 */
public final class AzureTranslationProvider implements IServiceProvider {
    public static final String ROMAJI = "ja-Latn";
    private static final String ENDPOINT = "https://api.cognitive.microsofttranslator.com";
    private static final AzureTranslationProvider INSTANCE = new AzureTranslationProvider();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** Azure's guess at the language of a text, with its confidence from 0 to 1. */
    public record Detection(String language, double score) {
    }

    public static AzureTranslationProvider getInstance() {
        return INSTANCE;
    }

    private AzureTranslationProvider() {
    }

    @Override
    public String separator() {
        return "\n";
    }

    @Override
    public String translate(String q, String sl, String tl) throws IOException, InterruptedException {
        StringBuilder url = new StringBuilder(ENDPOINT).append("/translate?api-version=3.0&to=").append(encode(azureLanguage(tl)));
        if (ROMAJI.equals(sl)) {
            url.append("&from=ja&fromScript=Latn");
        } else if (!"auto".equals(sl)) {
            url.append("&from=").append(encode(azureLanguage(sl)));
        }
        JsonObject first = post(url.toString(), q).get(0).getAsJsonObject();
        return first.getAsJsonArray("translations").get(0).getAsJsonObject().get("text").getAsString();
    }

    public Detection detect(String text) throws IOException, InterruptedException {
        JsonObject first = post(ENDPOINT + "/detect?api-version=3.0", text).get(0).getAsJsonObject();
        return new Detection(first.get("language").getAsString(), first.get("score").getAsDouble());
    }

    private JsonArray post(String url, String text) throws IOException, InterruptedException {
        TPPConfig config = TPPConfig.getInstance();
        String key = config.getAzureKey().strip();
        if (key.isEmpty()) {
            throw new ServiceException("No Azure key set. Add your Azure Translator key in the Translator++ settings.", 0, 0);
        }
        JsonObject item = new JsonObject();
        item.addProperty("Text", text);
        JsonArray body = new JsonArray();
        body.add(item);

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Ocp-Apim-Subscription-Key", key);
        String region = config.getAzureRegion().strip();
        if (!region.isEmpty()) {
            request.header("Ocp-Apim-Subscription-Region", region);
        }
        HttpResponse<String> response = HTTP.send(request.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            long retryAfter = response.headers().firstValueAsLong("Retry-After").orElse(0);
            throw new ServiceException(describe(response.statusCode()), response.statusCode(), retryAfter);
        }
        return JsonParser.parseString(response.body()).getAsJsonArray();
    }

    private static String describe(int status) {
        return switch (status) {
            case 401 -> "Azure rejected the key or region. Check both in the Translator++ settings.";
            case 403 -> "The Azure quota is used up for this period.";
            case 429 -> "Azure is rate limiting requests. Translation resumes shortly.";
            default -> "Azure returned an error.";
        };
    }

    /** Azure names the two Chinese scripts rather than regions. */
    private static String azureLanguage(String language) {
        return switch (language) {
            case "zh-CN", "zh-SG" -> "zh-Hans";
            case "zh-TW", "zh-HK" -> "zh-Hant";
            default -> language;
        };
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
