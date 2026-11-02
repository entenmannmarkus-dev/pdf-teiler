package de.pdfteiler;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Verbindung zu Microsoft Teams über Microsoft Graph (nur Arbeits-/Schulkonten).
 * Anmeldung per Geräte-Code: Die App zeigt einen Code, du meldest dich in Chrome bei Microsoft an.
 * Gespeichert wird nur das Anmelde-Token in den privaten App-Einstellungen – kein Passwort.
 */
final class Teams {
    static String LOGIN = "https://login.microsoftonline.com/";
    static String GRAPH = "https://graph.microsoft.com/v1.0";
    static final String SCOPE = "offline_access User.Read Chat.ReadWrite";

    private final SharedPreferences p;

    Teams(Context c) { p = c.getSharedPreferences("teams", Context.MODE_PRIVATE); }

    String clientId() { return p.getString("client", ""); }
    String tenant() { return p.getString("tenant", "organizations"); }
    boolean configured() { return clientId().length() > 0; }
    boolean signedIn() { return p.getString("refresh", null) != null; }
    String myId() { return p.getString("me", ""); }
    String myName() { return p.getString("meName", ""); }

    void configure(String client, String tenant) {
        p.edit().putString("client", client.trim())
                .putString("tenant", tenant.trim().isEmpty() ? "organizations" : tenant.trim()).apply();
    }

    void signOut() { p.edit().remove("refresh").remove("access").remove("exp").remove("me").remove("meName").apply(); }

    // ------------------------------------------------------------------ Anmeldung (Geräte-Code)
    JSONObject deviceCode() throws Exception {
        return new JSONObject(form(LOGIN + tenant() + "/oauth2/v2.0/devicecode",
                "client_id=" + enc(clientId()) + "&scope=" + enc(SCOPE)));
    }

    /** @return true = angemeldet, false = noch warten; wirft bei Abbruch/Fehler. */
    boolean pollToken(String deviceCode) throws Exception {
        JSONObject o;
        try {
            o = new JSONObject(form(LOGIN + tenant() + "/oauth2/v2.0/token",
                    "grant_type=" + enc("urn:ietf:params:oauth:grant-type:device_code")
                    + "&client_id=" + enc(clientId()) + "&device_code=" + enc(deviceCode)));
        } catch (HttpError e) {
            JSONObject err = parse(e.body);
            String code = err.optString("error");
            if ("authorization_pending".equals(code) || "slow_down".equals(code)) return false;
            throw new IOException(explain(err));
        }
        store(o);
        JSONObject me = new JSONObject(get("/me?$select=id,displayName"));
        p.edit().putString("me", me.optString("id")).putString("meName", me.optString("displayName")).apply();
        return true;
    }

    private void store(JSONObject o) {
        SharedPreferences.Editor e = p.edit().putString("access", o.optString("access_token"))
                .putLong("exp", System.currentTimeMillis() + (o.optLong("expires_in", 3600) - 120) * 1000L);
        if (o.has("refresh_token")) e.putString("refresh", o.optString("refresh_token"));
        e.apply();
    }

    private synchronized String token() throws Exception {
        String a = p.getString("access", null);
        if (a != null && System.currentTimeMillis() < p.getLong("exp", 0)) return a;
        String r = p.getString("refresh", null);
        if (r == null) throw new IOException("Nicht angemeldet.");
        try {
            store(new JSONObject(form(LOGIN + tenant() + "/oauth2/v2.0/token",
                    "grant_type=refresh_token&client_id=" + enc(clientId()) + "&refresh_token=" + enc(r)
                    + "&scope=" + enc(SCOPE))));
        } catch (HttpError e) {
            signOut();
            throw new IOException("Anmeldung abgelaufen – bitte neu anmelden. (" + explain(parse(e.body)) + ")");
        }
        return p.getString("access", null);
    }

    // ------------------------------------------------------------------ Graph
    String get(String path) throws Exception { return http("GET", GRAPH + path, null, "Bearer " + token()); }

    String post(String path, JSONObject body) throws Exception {
        return http("POST", GRAPH + path, body.toString(), "Bearer " + token());
    }

    // ------------------------------------------------------------------ HTTP
    static final class HttpError extends IOException {
        final int code; final String body;
        HttpError(int code, String body) { super("Server antwortet " + code); this.code = code; this.body = body; }
    }

    private static String form(String url, String body) throws IOException { return http("POST", url, body, null); }

    private static String http(String method, String url, String body, String auth) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            c.setRequestMethod(method);
            c.setRequestProperty("User-Agent", "Splitt-Tool-Android");
            if (auth != null) c.setRequestProperty("Authorization", auth);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", auth == null ? "application/x-www-form-urlencoded" : "application/json");
                OutputStream os = c.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.close();
            }
            int code = c.getResponseCode();
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            String text = read(in);
            if (code >= 400) throw new HttpError(code, text);
            return text;
        } finally { c.disconnect(); }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return new String(b.toByteArray(), "UTF-8");
    }

    static JSONObject parse(String s) {
        try { return new JSONObject(s); } catch (Exception e) { return new JSONObject(); }
    }

    /** Verständliche Meldung für typische Microsoft-Fehler. */
    static String explain(JSONObject err) {
        String code = err.optString("error"), d = err.optString("error_description");
        if (err.has("error") && err.opt("error") instanceof JSONObject) {
            JSONObject g = err.optJSONObject("error");
            code = g.optString("code"); d = g.optString("message");
        }
        if ("expired_token".equals(code)) return "Der Code ist abgelaufen. Bitte neu anmelden.";
        if ("authorization_declined".equals(code)) return "Anmeldung abgelehnt.";
        if (d.contains("AADSTS65001") || d.contains("consent") || d.contains("admin"))
            return "Dein Unternehmen muss den Zugriff erst freigeben (Admin-Zustimmung). Details: " + d;
        if (d.contains("AADSTS7000218")) return "In der App-Registrierung „Öffentliche Clientflows zulassen“ einschalten.";
        if (d.contains("AADSTS700016")) return "Die Anwendungs-ID (Client-ID) ist falsch oder nicht in diesem Verzeichnis.";
        return (code.isEmpty() ? "Fehler" : code) + (d.isEmpty() ? "" : ": " + d);
    }

    static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }
}
