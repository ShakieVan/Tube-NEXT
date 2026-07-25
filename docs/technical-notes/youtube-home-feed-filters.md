# YouTube-Startseitenfilter

Stand: am 25.07.2026 gegen `master`, `v1.1.0` und den zugehoerigen
Projekt-Task geprueft

## Zweck und Bedienung

Unter `Startseite` koennen drei YouTube-Module einzeln ein- oder ausgeblendet
werden:

- Shorts,
- Community-Posts,
- Zuletzt gesehen.

Alle drei Optionen stehen standardmaessig auf `anzeigen`. Eine neue
Installation veraendert YouTubes Startseite daher nicht, bis der Nutzer einen
Haken entfernt.

Das historische Drei-Punkte-Popup wurde inzwischen in die gemeinsame
Tube-NEXT-Einstellungsseite integriert. Die gespeicherten Preference-Schluessel
und das Laufzeitverhalten blieben erhalten.

## Datenfluss

Die Einstellungen werden in Android gespeichert und als
`EngineHomeFeedSettings` an jeden lebenden Tab uebergeben. GeckoView sendet
sie ueber einen nativen WebExtension-Port an `content.js`.

Wenn die Extension ihren Port als bereit meldet, sendet die App den aktuellen
Stand erneut. Eine Aenderung wird unmittelbar an alle lebenden Tabs verteilt;
ein App-Neustart oder Reload ist dafuer nicht erforderlich.

## Begrenzung auf die Startseite

Der Filter greift nur auf einem unterstuetzten YouTube-Host mit leerem Pfad
beziehungsweise `/`. Watch-Seiten, Suche, Abos, Verlauf und andere
YouTube-Bereiche werden nicht als Home Feed behandelt.

Auszublendende Karten erhalten die eigene Klasse
`tubenext-home-feed-hidden`. Ein kleines Style-Element setzt nur diese Klasse
auf `display: none`. Die WebExtension loescht oder baut YouTubes DOM nicht
grundlegend um.

## Defensive Erkennung

Desktop-YouTube verwendet vorwiegend `ytd-*`, die mobile Startseite
`ytm-*`. Der erste historische Entwurf erkannte nur Desktop-Strukturen.
Dadurch blieb ein Community-Post auf `m.youtube.com` sichtbar, obwohl die
Option korrekt in der Extension angekommen war.

Seit `v1.1.0` werden fuer beide Varianten bekannte Container geprueft.

Shorts werden nur als solche behandelt, wenn:

- der Container ein bekanntes Reel-/Shorts-Shelf ist,
- er einen Link auf `/shorts/` enthaelt,
- oder ein Shelf-/Section-Container eindeutig als `Shorts` beschriftet ist.

Community-Posts werden ueber bekannte Post-Renderer, darin enthaltene
Post-Renderer oder eindeutige Community-Bezeichnungen in einem
Section-Container erkannt.

`Zuletzt gesehen` verwendet eng begrenzte deutsche und englische
Ueberschriften wie `Zuletzt angesehen`, `Weiterschauen`, `Watch again` und
`Continue watching`.

Die Textsuche wird nur auf Regal-/Section-Container angewendet. Ein normales
Video, dessen Titel zufaellig das Wort `Shorts` oder `Community` enthaelt,
soll dadurch nicht verschwinden.

## MutationObserver und Energie

YouTube baut die Startseite als Single-Page-App dynamisch nach. Wenn
mindestens ein Filter aktiv ist, beobachtet die Extension neu hinzugefuegte
DOM-Knoten und plant eine gedrosselte erneute Auswertung.

Sind alle drei Kategorien sichtbar, wird der Observer getrennt und die
Filterklasse entfernt. Damit erzeugt das optionale Feature im Standardzustand
keine dauerhafte eigene DOM-Beobachtung.

## Bewusste Nicht-Ziele

Nicht gefiltert werden:

- Werbung,
- Netzwerkrequests,
- allgemeine Videoempfehlungen,
- Mixes und Playlists,
- Live-/Premiere-Hinweise,
- Nachrichten- oder Themenregale,
- unbekannte Module, die nur ungefaehr zu einer Kategorie passen.

Weitere Kategorien werden erst bei einem konkreten Bedarf und mit eindeutigem
DOM-Signal aufgenommen. Das verhindert, dass ein immer breiterer
Selektorflickenteppich normale Videos entfernt.

## Grenzen

- YouTubes Renderer-Namen und DOM-Struktur sind nicht stabil.
- Neue Sprachen werden nicht automatisch erkannt.
- Ein geaendertes mobiles Element kann einen Filter ausfallen lassen, ohne
  dass die Einstellung oder native Bridge defekt ist.
- Die Klassenbezeichnung `EngineHomeFeedSettings` traegt inzwischen auch eine
  Watch-Seiten-Option; das ist begriffliche, nicht funktionale technische
  Schuld.

## Historie

`v1.1.0` beziehungsweise Commit `c7978e3` veroeffentlichte die drei Filter.
Der Test eines realen mobilen Community-Posts bestaetigte die notwendige
Unterstuetzung fuer `ytm-*`-Renderer. Die WebExtension-Version wurde damals
angehoben, damit GeckoView das geaenderte Built-in-Addon uebernimmt.

## Regressionstest

1. Alle Haken aktiv: Startseite muss unveraendert sein und der eigene
   MutationObserver inaktiv bleiben.
2. Jeden Filter einzeln sowie mehrere gemeinsam deaktivieren.
3. Aenderung muss in bereits offenen Tabs ohne Reload greifen.
4. Desktop- und mobile YouTube-Startseite pruefen.
5. Community-Post, Shorts-Shelf und Wiedergabeverlaufsregal jeweils mit
   passender Einstellung ein- und ausblenden.
6. Normales Video mit `Shorts` oder `Community` im Titel darf nicht
   verschwinden.
7. Navigation zu Watch, Suche und anderen Bereichen darf dort keine Karten
   filtern.
8. Werbung und nicht freigegebene Sonderregale muessen unangetastet bleiben.
