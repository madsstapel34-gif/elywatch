# ElyWatch (Fabric, Minecraft 1.21.8)

Client-Mod: Prueft Spieler in der RTP-Queue per `/invsee` und pingt dich bei einer Elytra ueber einen Discord-Webhook mit `@Ely`.

## Bauen
Voraussetzung: JDK 21 (z. B. Temurin 21) und Gradle (oder IntelliJ IDEA, das den Gradle-Import selbst macht).

    gradle build        # oder ./gradlew build, falls ein Wrapper dabei ist

Die fertige Datei liegt in `build/libs/elywatch-1.0.0.jar` (nicht die `-sources.jar`).

## Installieren
1. Fabric Loader fuer 1.21.8 installieren (https://fabricmc.net/use/installer/).
2. `elywatch-1.0.0.jar` und die **Fabric API** (fuer 1.21.8) in den `mods`-Ordner legen.
3. Minecraft einmal starten und wieder schliessen. Dadurch entsteht `config/elywatch.json`.
4. In der Datei `webhookUrl` (Discord: Channel-Einstellungen > Integrationen > Webhooks) und `roleId` (ID der Rolle @Ely) eintragen.
5. Auf HugoSMP in die RTP-Queue stellen und `/elywatch area 15` eingeben. Der Bereich wird dann auf 15 Bloecke um deine Position gesetzt.

## Befehle
- `/elywatch on` / `/elywatch off`
- `/elywatch area <radius>`: Queue-Bereich um deine aktuelle Position
- `/elywatch test`: schickt eine Test-Nachricht an Discord
- `/elywatch status`

## Hinweise
- Spieler muessen in deiner Render-Distanz sein, sonst sieht der Client sie nicht.
- Beim Pruefen oeffnet und schliesst sich kurz das Inventar-Fenster.
- Du brauchst die Rechte fuer `/invsee` auf dem Server.
- Pruefe die Serverregeln, ob automatische Befehle per Mod erlaubt sind.
