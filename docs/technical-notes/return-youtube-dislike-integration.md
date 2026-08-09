# Optionale Return-YouTube-Dislike-Integration

Stand: 09.08.2026, fuer `v1.4.5` geprueft

Tube NEXT kann auf Desktop-Watch-Seiten optional die von
[Return YouTube Dislike](https://returnyoutubedislike.com) geschaetzte
Dislike-Zahl im vorhandenen Dislike-Segment anzeigen. Die Funktion ist kein
YouTube-Messwert und bleibt standardmaessig deaktiviert.

## Einwilligung und Datenfluss

Beim ersten Aktivieren erscheint ein versionierter Datenschutzhinweis. Erst
nach ausdruecklicher Zustimmung wird die Einstellung gespeichert und an die
WebExtension verteilt. Abbrechen behaelt den deaktivierten Zustand bei.

Der Hinweis nennt:

- die Uebertragung der jeweils angesehenen Video-ID,
- die technisch beim Dienst sichtbare IP-Adresse und den Abrufzeitpunkt,
- die Uebertragung eigener Like-/Dislike-Entscheidungen zusammen mit einer
  zufaellig erzeugten, dauerhaften RYD-ID,
- die Moeglichkeit, daraus ein Nutzungs- oder Interessenprofil abzuleiten,
- den Schaetzcharakter der angezeigten Zahlen.

Die WebExtension sendet bei ihren RYD-Anfragen keine YouTube-Cookies und
keinen Referrer (`credentials: omit`, `referrerPolicy: no-referrer`). Das
verhindert nicht, dass der Dienst IP-Adresse, Zeitpunkt, Video-ID und bei
Stimmen die RYD-ID gemeinsam sieht.

Die Zustimmung wird als Versionsnummer gespeichert. Eine spaetere
Reaktivierung derselben Hinweisversion fragt nicht erneut. Wird der Inhalt des
Hinweises wesentlich erweitert, muss `RYD_CONSENT_VERSION` erhoeht werden.

Die dauerhafte Zuordnung und der Link zum Dienst stehen in der
Einstellungsbezeichnung. Auf der Watch-Seite wird kein zusaetzliches Label
eingebaut, damit die ohnehin knappe Aktionszeile nicht breiter wird.

## Abruf und Darstellung

`content.js` erkennt ausschliesslich echte Watch-URLs und extrahiert daraus
eine validierte elfstellige YouTube-Video-ID. Der Hintergrundteil der
WebExtension akzeptiert Nachrichten nur von unterstuetzten HTTPS-YouTube-
Hosts und ruft anschliessend `GET /votes?videoId=...` auf.

Die Zahl wird in den aktuell sichtbaren Dislike-Button unter
`ytd-watch-metadata #actions` eingesetzt. Falls YouTube dort keinen eigenen
Textcontainer liefert, wird nur der Textcontainer des benachbarten Like-
Buttons als Strukturvorlage geklont. Vorhandene Knoten, Titel und relevante
Button-Klassen werden gesichert und beim Deaktivieren, Seitenwechsel oder
Renderer-Austausch wiederhergestellt. Kommentare, Player-Controls und andere
Renderer werden nicht veraendert.

SPA-Navigation und spaet geladene Aktions-Renderer werden ueber die bereits
vorhandenen Watch-Nachlaeufe und einen eng begrenzten Mutation-Observer
abgedeckt. Antworten fuer eine inzwischen verlassene Video-ID werden anhand
von Generation und Video-ID verworfen.

## Rueckmeldung eigener Stimmen

Nach einer Like-/Dislike-Aktion liest die WebExtension den von YouTube
bestaetigten `aria-pressed`-Zustand. Erst eine tatsaechliche Zustandsaenderung
wird verarbeitet. `tap` und der haeufig nachfolgende `click` werden zeitlich
dedupliziert.

Beim Wechsel auf beziehungsweise weg von Dislike wird die sichtbare Zahl
sofort lokal um eins erhoeht beziehungsweise verringert. Dadurch entspricht
die Rueckmeldung der Nutzererwartung, ohne vor YouTubes eigener
Zustandsaenderung zu zaehlen.

Der neue Gesamtzustand (`1` Like, `0` neutral, `-1` Dislike) wird an RYD
uebermittelt. Beim ersten Beitrag erzeugt die WebExtension eine zufaellige
36-stellige pseudonyme RYD-ID, registriert sie ueber den dokumentierten
Proof-of-Work-Ablauf und bestaetigt danach die Stimme ebenfalls per
Proof-of-Work. Die ID wird nicht vor dem ersten eigenen Beitrag erzeugt.

## Cache, Limits und Backoff

Jede HTTP-Anfrage, auch jeder Schritt des Abstimmungsprotokolls, wird lokal
gezaehlt. Tageszaehler und Zeitpunkte des laufenden Minutenfensters werden
gespeichert, sodass ein App-Neustart die lokalen Grenzen nicht zuruecksetzt.
Die RYD-Dokumentation nennt derzeit 100 Anfragen pro Minute und 10.000 pro Tag
je Client. Tube NEXT bleibt bewusst darunter:

- maximal 30 Anfragen pro Minute,
- maximal 2.000 Anfragen pro UTC-Tag,
- mindestens 350 Millisekunden Abstand und nur eine laufende HTTP-Anfrage,
- Zusammenfassen gleichzeitiger Abrufe derselben Video-ID,
- maximal 128 im Speicher gehaltene Videoergebnisse,
- Cache-Laufzeit aus `Cache-Control`, standardmaessig drei Minuten, begrenzt
  auf eine Minute bis eine Stunde.

Ein fehlgeschlagener reiner Zaehlerabruf wird nicht automatisch wiederholt.
Ist noch ein abgelaufener Cache-Eintrag vorhanden, wird er als veraltet
zurueckgegeben; andernfalls bleibt die Zahl leer. Damit kann eine
automatisierte oder sehr schnelle Navigation keine Retry-Schleife erzeugen.

Auf HTTP 429 wird `Retry-After` beachtet und der Sperrzeitpunkt gespeichert.
Fehlt der Header, gelten 15 Minuten. Bis dahin wird bereits lokal abgewiesen,
ohne den Dienst erneut zu kontaktieren.

Eigene Stimmen werden pro Video auf den neuesten Zustand zusammengefasst und
in einer auf 100 Eintraege begrenzten lokalen Warteschlange gehalten.
Wiederholungen gibt es nur fuer temporaere Netzwerk-, Timeout-, 429- oder
Serverfehler: exponentiell mit Zufallsanteil, hoechstens sechs Versuche und
maximal sechs Stunden Abstand. Dauerhafte Protokoll- oder Clientfehler werden
nicht wiederholt.

Beim Deaktivieren werden aktive RYD-Anfragen abgebrochen, der Retry-Timer
beendet und ausstehende Stimmen geloescht. Die bereits registrierte
pseudonyme RYD-ID bleibt lokal erhalten, damit eine spaetere Reaktivierung
nicht unnoetig eine weitere Identitaet beim Dienst anlegt.

## Fehlerverhalten und Grenzen

- Eine nicht erreichbare API darf Watch-Seite und Wiedergabe nicht blockieren.
- Harte Fehler beim Uebermitteln einer eigenen Stimme werden kurz als
  Tube-NEXT-Hinweis angezeigt. Die lokale sichtbare Zustandsaenderung bleibt,
  weil sie weiterhin YouTubes bestaetigten Button-Zustand abbildet.
- RYD-Zahlen sind Schaetzungen. Die Qualitaet kann je Video stark variieren.
- YouTubes Aktions-DOM ist nicht stabil. Faellt die eng begrenzte
  Renderer-Erkennung aus, bleibt die Zahl unsichtbar, statt andere
  Watch-Elemente umzubauen.
- Die Option umfasst derzeit normale Watch-Seiten, nicht Shorts, Listen oder
  Startseiten-Karten.

## Abnahme

Automatisierte Hintergrundtests liegen in `tools/ryd-background.test.js` und
pruefen Opt-in, Cache/Deduplizierung, 429-Backoff sowie das Abbrechen beim
Deaktivieren. Sie laufen ohne externen Netzwerkzugriff mit:

```powershell
node --test tools/ryd-background.test.js
```

Der Emulator-Test muss zusaetzlich bestaetigen:

1. Option aus: kein Zaehler und kein RYD-Abruf.
2. Abbrechen im ersten Hinweis: Option bleibt aus.
3. Zustimmung: Zahl erscheint im vorhandenen Dislike-Segment.
4. Deaktivieren: urspruenglicher YouTube-Button wird ohne Reload
   wiederhergestellt.
5. SPA-Wechsel zwischen mindestens zwei Watch-Seiten: keine alte Zahl am
   neuen Video.
6. Like, Dislike und Neutral auf einem eigenen oder ausdruecklich fuer Tests
   vorgesehenen Video: genau eine sichtbare Zustandsaenderung und eine
   zusammengefasste RYD-Stimme.
7. Player, Audio-Kanal-Menue, Kommentare, Teilen, Branding-Option und
   Landscape-Taps bleiben bedienbar.

Tests auf fremden Videos duerfen keine kuenstlichen Like-/Dislike-Stimmen an
YouTube oder RYD senden.
