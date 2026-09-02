package coop.registration;

public final class Pages {
    private Pages() {
    }

    public static String registerPage(String csrfToken, String errorMessage) {
        String error = errorMessage == null || errorMessage.isEmpty()
                ? ""
                : "<p class=\"error\" role=\"alert\">" + escape(errorMessage) + "</p>";
        return """
                <!DOCTYPE html>
                <html lang="de">
                <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <meta name="robots" content="noindex, nofollow">
                    <title>Account erstellen — MapleStory Co-op Remix</title>
                    <link rel="stylesheet" href="/assets/register.css">
                </head>
                <body>
                <main class="card">
                    <h1>Account erstellen</h1>
                    <p class="muted">Nur für eingeladene Mitspieler. Es werden keine E-Mail-Adressen,
                        Geburtsdaten oder Analyse-Daten erhoben.</p>
                    ERROR_PLACEHOLDER
                    <form method="post" action="/register" autocomplete="off">
                        <input type="hidden" name="csrf" value="CSRF_PLACEHOLDER">
                        <label for="invite">Einladungscode</label>
                        <input id="invite" name="invite" type="password" required maxlength="64">

                        <label for="username">Benutzername</label>
                        <input id="username" name="username" type="text" required minlength="4" maxlength="13"
                               pattern="[A-Za-z0-9_]+" autocomplete="username">

                        <label for="password">Passwort</label>
                        <input id="password" name="password" type="password" required minlength="12" maxlength="64"
                               autocomplete="new-password">

                        <label for="confirmation">Passwort bestätigen</label>
                        <input id="confirmation" name="confirmation" type="password" required minlength="12"
                               maxlength="64" autocomplete="new-password">

                        <button type="submit">Account erstellen</button>
                    </form>
                    <p class="muted">Der Account funktioniert danach direkt im v83-Client. PIN und PIC werden beim
                        ersten Login im Client gesetzt.</p>
                    <p><a href="/">Zurück zur Übersicht</a></p>
                </main>
                </body>
                </html>
                """.replace("ERROR_PLACEHOLDER", error).replace("CSRF_PLACEHOLDER", escape(csrfToken));
    }

    public static String successPage() {
        return """
                <!DOCTYPE html>
                <html lang="de">
                <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <meta name="robots" content="noindex, nofollow">
                    <title>Account erstellt — MapleStory Co-op Remix</title>
                    <link rel="stylesheet" href="/assets/register.css">
                </head>
                <body>
                <main class="card">
                    <h1>Account erstellt</h1>
                    <p>Der Account ist angelegt. Melde dich jetzt im Client an und setze dort PIN und PIC.</p>
                    <p><a href="/">Zurück zur Übersicht</a></p>
                </main>
                </body>
                </html>
                """;
    }

    static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
