package de.pdfteiler;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Teams-Chat im Splitt-Tool: Chats ansehen, Nachrichten lesen und schreiben (Microsoft Graph). */
public class TeamsActivity extends Activity {
    static final int BLUE = MainActivity.BLUE, BG = MainActivity.BG, MUTED = MainActivity.MUTED;
    static final String GUIDE_URL = "https://github.com/" + Updater.REPO + "/blob/main/TEAMS.md";

    private Teams teams;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private String openChat, openChatTitle;
    private LinearLayout msgBox;
    private ScrollView msgScroll;
    private boolean polling;
    private volatile boolean loginRunning;

    private final Runnable refresher = new Runnable() { public void run() {
        if (openChat != null && polling) { loadMessages(false); main.postDelayed(this, 10000); }
    }};

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        teams = new Teams(this);
        if (getActionBar() != null) {
            getActionBar().setTitle("Teams-Chat");
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        sv.addView(root);
        setContentView(sv);
        show();
    }

    @Override public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) { onBackPressed(); return true; }
        return super.onOptionsItemSelected(item);
    }

    @Override public void onBackPressed() {
        if (openChat != null) { openChat = null; polling = false; show(); return; }
        loginRunning = false;
        super.onBackPressed();
    }

    @Override protected void onResume() { super.onResume(); if (openChat != null && !polling) { polling = true; main.postDelayed(refresher, 10000); } }
    @Override protected void onPause() { super.onPause(); polling = false; main.removeCallbacks(refresher); }

    private void show() {
        root.removeAllViews();
        if (!teams.configured()) setupScreen();
        else if (!teams.signedIn()) loginScreen();
        else chatList();
    }

    // ------------------------------------------------------------------ Einrichtung
    private void setupScreen() {
        title("Teams einrichten");
        text("Für den Teams-Chat braucht die App einmalig eine Anmeldung bei Microsoft (App-Registrierung). "
                + "Das geht nur mit einem Arbeits- oder Schulkonto. Die Schritte stehen in der Anleitung.");
        Button guide = button("Anleitung in Chrome öffnen");
        guide.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { Updater.openInChrome(TeamsActivity.this, GUIDE_URL); }});
        final EditText client = field("Anwendungs-ID (Client-ID)", teams.clientId());
        final EditText tenant = field("Verzeichnis-ID oder Domäne (leer = automatisch)",
                "organizations".equals(teams.tenant()) ? "" : teams.tenant());
        Button save = button("Speichern");
        save.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            String id = client.getText().toString().trim();
            if (!id.matches("[0-9a-fA-F-]{36}")) { alert("Bitte prüfen", "Die Anwendungs-ID sieht so aus: 1a2b3c4d-1234-5678-9abc-def012345678"); return; }
            teams.configure(id, tenant.getText().toString());
            show();
        }});
    }

    // ------------------------------------------------------------------ Anmeldung
    private void loginScreen() {
        title("Bei Microsoft anmelden");
        text("Du bekommst einen Code. Den gibst du in Chrome auf der Microsoft-Seite ein und meldest dich mit deinem Arbeitskonto an.");
        Button go = button("Anmelden");
        final TextView status = text("");
        Button change = button("Anwendungs-ID ändern");
        change.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            teams.configure("", teams.tenant()); show();
        }});
        go.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            status.setText("Hole Code …");
            new Thread(new Runnable() { public void run() {
                try {
                    final JSONObject dc = teams.deviceCode();
                    main.post(new Runnable() { public void run() { showCode(dc, status); } });
                    loginRunning = true;
                    long until = System.currentTimeMillis() + dc.optLong("expires_in", 900) * 1000L;
                    int interval = Math.max(2, dc.optInt("interval", 5));
                    while (loginRunning && System.currentTimeMillis() < until) {
                        Thread.sleep(interval * 1000L);
                        if (teams.pollToken(dc.getString("device_code"))) {
                            loginRunning = false;
                            main.post(new Runnable() { public void run() { show(); } });
                            return;
                        }
                    }
                    if (loginRunning) fail(status, "Der Code ist abgelaufen. Bitte erneut auf „Anmelden“ tippen.");
                } catch (final Exception e) { fail(status, String.valueOf(e.getMessage())); }
            }}, "teams-login").start();
        }});
    }

    private void showCode(JSONObject dc, TextView status) {
        final String code = dc.optString("user_code");
        final String uri = dc.optString("verification_uri", "https://microsoft.com/devicelogin");
        status.setText("Dein Code:");
        TextView big = text(code);
        big.setTextSize(30);
        big.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        big.setTextIsSelectable(true);
        Button open = button("Code kopieren und Microsoft in Chrome öffnen");
        open.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Code", code));
            Updater.openInChrome(TeamsActivity.this, uri);
        }});
        text("Nach der Anmeldung hierher zurückkehren – die App meldet sich dann von selbst.");
    }

    private void fail(final TextView status, final String msg) {
        main.post(new Runnable() { public void run() { status.setText(""); alert("Anmeldung nicht möglich", msg); } });
    }

    // ------------------------------------------------------------------ Chats
    private void chatList() {
        title("Deine Chats");
        TextView who = text("Angemeldet als " + teams.myName());
        who.setTextColor(MUTED);
        LinearLayout row = new LinearLayout(this);
        root.addView(row);
        Button reload = button(row, "Aktualisieren");
        Button out = button(row, "Abmelden");
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
        reload.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { show(); }});
        out.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { teams.signOut(); show(); }});
        final TextView loading = text("Lade Chats …");
        new Thread(new Runnable() { public void run() {
            try {
                JSONArray arr = new JSONObject(teams.get("/me/chats?$expand=members,lastMessagePreview&$top=50"))
                        .optJSONArray("value");
                final List<JSONObject> chats = new ArrayList<JSONObject>();
                for (int i = 0; arr != null && i < arr.length(); i++) chats.add(arr.getJSONObject(i));
                Collections.sort(chats, new Comparator<JSONObject>() { public int compare(JSONObject a, JSONObject b) {
                    return last(b).compareTo(last(a));
                }});
                main.post(new Runnable() { public void run() {
                    loading.setText(chats.isEmpty() ? "Keine Chats gefunden." : "");
                    for (final JSONObject c : chats) {
                        final String name = chatName(c);
                        JSONObject pv = c.optJSONObject("lastMessagePreview");
                        String preview = pv == null ? "" : plain(pv.optJSONObject("body"));
                        LinearLayout item = box(list);
                        TextView t = new TextView(TeamsActivity.this);
                        t.setText(name);
                        t.setTextSize(16);
                        t.setTypeface(Typeface.DEFAULT_BOLD);
                        t.setTextColor(BLUE);
                        item.addView(t);
                        if (!preview.isEmpty()) {
                            TextView p = new TextView(TeamsActivity.this);
                            p.setText(preview.length() > 90 ? preview.substring(0, 90) + " …" : preview);
                            p.setTextColor(MUTED);
                            item.addView(p);
                        }
                        item.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                            openChat = c.optString("id"); openChatTitle = name; chatView();
                        }});
                    }
                }});
            } catch (final Exception e) {
                main.post(new Runnable() { public void run() {
                    loading.setText("");
                    alert("Chats nicht geladen", e instanceof Teams.HttpError
                            ? Teams.explain(Teams.parse(((Teams.HttpError) e).body)) : String.valueOf(e.getMessage()));
                    if (!teams.signedIn()) show();
                }});
            }
        }}, "teams-chats").start();
    }

    static String last(JSONObject c) {
        JSONObject pv = c.optJSONObject("lastMessagePreview");
        return pv != null ? pv.optString("createdDateTime", "") : c.optString("lastUpdatedDateTime", "");
    }

    private String chatName(JSONObject c) {
        String topic = c.optString("topic", "");
        if (!topic.isEmpty() && !"null".equals(topic)) return topic;
        JSONArray m = c.optJSONArray("members");
        StringBuilder b = new StringBuilder();
        for (int i = 0; m != null && i < m.length(); i++) {
            JSONObject x = m.optJSONObject(i);
            if (x == null || teams.myId().equals(x.optString("userId"))) continue;
            if (b.length() > 0) b.append(", ");
            b.append(x.optString("displayName"));
        }
        return b.length() > 0 ? b.toString() : "Chat";
    }

    // ------------------------------------------------------------------ Ein Chat
    private void chatView() {
        root.removeAllViews();
        title(openChatTitle);
        Button back = button("← Alle Chats");
        back.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { onBackPressed(); }});
        msgBox = new LinearLayout(this);
        msgBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(msgBox);
        final EditText input = new EditText(this);
        input.setHint("Nachricht schreiben …");
        input.setMinLines(2);
        input.setBackgroundColor(Color.WHITE);
        input.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        root.addView(input, lp);
        Button send = button("Senden");
        send.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            final String text = input.getText().toString().trim();
            if (text.isEmpty()) return;
            input.setText("");
            new Thread(new Runnable() { public void run() {
                try {
                    JSONObject body = new JSONObject().put("body",
                            new JSONObject().put("contentType", "text").put("content", text));
                    teams.post("/chats/" + Teams.enc(openChat) + "/messages", body);
                    loadMessages(true);
                } catch (final Exception e) {
                    main.post(new Runnable() { public void run() {
                        input.setText(text);
                        alert("Nicht gesendet", e instanceof Teams.HttpError
                                ? Teams.explain(Teams.parse(((Teams.HttpError) e).body)) : String.valueOf(e.getMessage()));
                    }});
                }
            }}, "teams-send").start();
        }});
        text("Dateien (die Teile) schickst du nach dem Aufteilen mit „An Teams senden“.").setTextColor(MUTED);
        loadMessages(true);
        polling = true;
        main.removeCallbacks(refresher);
        main.postDelayed(refresher, 10000);
    }

    private void loadMessages(final boolean scroll) {
        final String chat = openChat;
        if (chat == null) return;
        new Thread(new Runnable() { public void run() {
            try {
                JSONArray arr = new JSONObject(teams.get("/chats/" + Teams.enc(chat) + "/messages?$top=40")).optJSONArray("value");
                final List<JSONObject> msgs = new ArrayList<JSONObject>();
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    JSONObject m = arr.getJSONObject(i);
                    if ("message".equals(m.optString("messageType", "message")) && m.isNull("deletedDateTime")) msgs.add(m);
                }
                Collections.sort(msgs, new Comparator<JSONObject>() { public int compare(JSONObject a, JSONObject b) {
                    return a.optString("createdDateTime").compareTo(b.optString("createdDateTime"));
                }});
                main.post(new Runnable() { public void run() {
                    if (!chat.equals(openChat) || msgBox == null) return;
                    msgBox.removeAllViews();
                    for (JSONObject m : msgs) addMessage(m);
                }});
            } catch (final Exception e) {
                main.post(new Runnable() { public void run() {
                    if (scroll) alert("Nachrichten nicht geladen", e instanceof Teams.HttpError
                            ? Teams.explain(Teams.parse(((Teams.HttpError) e).body)) : String.valueOf(e.getMessage()));
                }});
            }
        }}, "teams-messages").start();
    }

    private void addMessage(JSONObject m) {
        JSONObject from = m.optJSONObject("from");
        JSONObject user = from == null ? null : from.optJSONObject("user");
        boolean mine = user != null && teams.myId().equals(user.optString("id"));
        String who = user == null ? "System" : user.optString("displayName", "?");
        String time = m.optString("createdDateTime", "");
        try {
            java.text.SimpleDateFormat in = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ROOT);
            in.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            time = new java.text.SimpleDateFormat("dd.MM. HH:mm", java.util.Locale.GERMANY).format(in.parse(time.substring(0, 19)));
        } catch (Exception ignored) { }
        LinearLayout b = box(msgBox);
        ((GradientDrawable) b.getBackground()).setColor(mine ? MainActivity.BLUE_LIGHT : Color.WHITE);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
        if (mine) lp.leftMargin = dp(40); else lp.rightMargin = dp(40);
        TextView h = new TextView(this);
        h.setText(who + " · " + time);
        h.setTextSize(12);
        h.setTextColor(MUTED);
        b.addView(h);
        TextView t = new TextView(this);
        t.setText(plain(m.optJSONObject("body")));
        t.setTextSize(15);
        t.setTextColor(MainActivity.TEXT);
        t.setTextIsSelectable(true);
        b.addView(t);
        JSONArray att = m.optJSONArray("attachments");
        for (int i = 0; att != null && i < att.length(); i++) {
            JSONObject a = att.optJSONObject(i);
            if (a == null) continue;
            final String url = a.optString("contentUrl", "");
            TextView at = new TextView(this);
            at.setText("📎 " + a.optString("name", "Anhang"));
            at.setTextColor(BLUE);
            if (url.startsWith("https://")) at.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                Updater.openInChrome(TeamsActivity.this, url);
            }});
            b.addView(at);
        }
    }

    @SuppressWarnings("deprecation")
    static String plain(JSONObject body) {
        if (body == null) return "";
        String c = body.optString("content", "");
        if ("html".equalsIgnoreCase(body.optString("contentType"))) c = Html.fromHtml(c).toString();
        return c.replace('￼', ' ').trim();
    }

    // ------------------------------------------------------------------ Bausteine
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private TextView title(String s) {
        TextView t = text(s);
        t.setTextSize(20);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(BLUE);
        return t;
    }

    private TextView text(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(15);
        t.setTextColor(MainActivity.TEXT);
        t.setPadding(0, dp(6), 0, dp(6));
        root.addView(t);
        return t;
    }

    private EditText field(String hint, String value) {
        TextView l = text(hint);
        l.setTextColor(MUTED);
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(value);
        e.setBackgroundColor(Color.WHITE);
        e.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.addView(e);
        return e;
    }

    private Button button(String s) { return button(root, s); }

    private Button button(LinearLayout parent, String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        GradientDrawable g = new GradientDrawable();
        g.setColor(BLUE);
        g.setCornerRadius(dp(10));
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, dp(6), dp(8), dp(6));
        parent.addView(b, lp);
        return b;
    }

    private LinearLayout box(LinearLayout parent) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.WHITE);
        g.setCornerRadius(dp(12));
        g.setStroke(dp(1), MainActivity.BORDER);
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(4), 0, dp(4));
        parent.addView(b, lp);
        return b;
    }

    private void alert(String t, String m) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this).setTitle(t).setMessage(m).setPositiveButton("OK", null).show();
    }

}
