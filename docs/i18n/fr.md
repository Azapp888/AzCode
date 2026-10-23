# AzCode (Android)

[简体中文](zh-CN.md) | [English](en.md) | [日本語](ja.md) | [한국어](ko.md) | [Deutsch](de.md) | [Русский](ru.md) | [Español](es.md)

AzCode est un assistant d'automatisation local pour Android. Décrivez une tâche en langage naturel : la boucle d'agent intégrée lit l'écran, touche les contrôles, balaie et exécute des commandes via le service d'accessibilité jusqu'à l'achèvement de la tâche. Toutes les données restent sur votre téléphone, sans synchronisation cloud.

- Nom de l'application : **AzCode**
- Libellé de forme GUI : **magic**
- Libellé de forme CLI : **Listen**
- Version actuelle : **1.9.15** (versionCode 70)
- Prérequis : Android 8.0+ (minSdk 26), targetSdk 34

AzCode fonctionne seul, ou expose un pont de capacités lié uniquement à `127.0.0.1:8848` pour que le CLI Listen ou d'autres clients réutilisent les mêmes capacités via `adb forward`.

## Fonctionnalités

| Capacité | Description |
| --- | --- |
| Tâches en langage naturel | Saisie par chat, planification multi-étapes de l'agent, affichage des appels d'outils en direct |
| Contrôle de l'écran | Lecture des nœuds, appui par texte ou coordonnées, balayage, navigation système, saisie de texte Unicode |
| Lancement d'applications | Lancement par package ou nom d'application avec repli shell et accessibilité |
| Exécution de commandes | Canaux Termux, shell intégré, Shizuku et Root sélectionnés selon le mode de privilège |
| Multi-modèles | Plusieurs comptes de fournisseurs, protocoles compatible OpenAI, Anthropic et Gemini |
| Profondeur de réflexion | Quatre niveaux (désactivé, rapide, standard, approfondi) avec compatibilité des paramètres par modèle |
| Sessions | Sessions séparées, tiroir d'historique, titres automatiques, continuation et compression du contexte |
| Pièces jointes | Images, audio, PDF, Office, texte et code |
| Saisie vocale | Reconnaissance hors ligne du système et reconnaissance en ligne Xunfei commutables |
| Assistant flottant | Entrée d'assistant numérique par défaut, halo transparent, questions dans la page et clavier |
| Mémoire à long terme | Jetons sensibles, habitudes, méthodes et personnalisation locale |
| Compétences et plugins | Compétences intégrées, personnalisées, installation GitHub SKILL.md, scan de plugins populaires |
| GitHub | Lire et écrire des fichiers, parcourir branches et commits, gérer les issues et pull requests |
| Génération d'images | Génération texte-image multi-fournisseurs, affichée et enregistrable dans le chat |
| Documents Office | Génération docx / xlsx / pptx via GenOffice MCP et aperçu |
| Méthode de saisie | Clavier AzCode intégré comme repli pour l'écriture de texte Unicode |
| Pont de capacités | API HTTP en boucle locale et CLI Listen |
| Journaux | Journaux de plantage et d'exécution sur disque, consultables, copiables et effaçables |

## Démarrage rapide

### Installation

Téléchargez un APK depuis les GitHub Releases `android-v*`, ou récupérez l'artefact `azcode-magic-apk` dans GitHub Actions. Les versions debug et release partagent la même clé de signature du dépôt et peuvent donc se remplacer mutuellement.

### Configuration initiale

1. Ouvrez Paramètres > Capacités de l'appareil > Accessibilité et activez **AzCode Screen Control** dans les paramètres système.
2. Allez dans Paramètres > Gestion des modèles, ajoutez et activez un fournisseur, puis saisissez une clé API. L'URL de base et le modèle peuvent utiliser des modèles prédéfinis.
3. Revenez à l'écran principal et confirmez le modèle actif dans le sélecteur.
4. Saisissez une tâche et envoyez-la, par exemple « Ouvrir les paramètres et trouver la version d'Android ».

### Autorisations

| Autorisation | Utilisation |
| --- | --- |
| Service d'accessibilité | Lire l'écran, appuyer, balayer, navigation système, écriture de texte |
| Microphone | Saisie vocale et assistant flottant |
| Notifications | Notifications de fin de tâche et de question |
| Affichage par-dessus d'autres applications | Halo de l'assistant flottant |
| Caméra | Pièces jointes photo |
| Termux RUN_COMMAND | Exécuter des commandes Linux complètes via Termux |
| Shizuku | Exécuter des commandes système en tant qu'adb |

### Compilation depuis les sources

JDK 17 et le SDK Android sont requis. Le dépôt ne contient pas `gradle-wrapper.jar`, utilisez donc le `gradle` du système :

```bash
gradle assembleDebug
gradle assembleRelease
```

Après un push, `.github/workflows/android.yml` compile les APK Debug et Release sur GitHub Actions. Un tag `android-v*` crée une GitHub Release.

## Capacités de l'appareil et exécution des commandes

| Mode | Canal | Identité | Prérequis | Délai |
| --- | --- | --- | --- | --- |
| NORMAL + Termux | Termux `RUN_COMMAND` | uid Termux | Termux installé, RUN_COMMAND accordé, `allow-external-apps=true` | 90 s |
| NORMAL + shell intégré | `/system/bin/sh -c` | uid de l'app | Aucun | 60 s |
| SHIZUKU | Shizuku binder | adb uid 2000 | Shizuku lancé et autorisé | 60 s |
| ROOT | `su -c` | uid 0 | Appareil rooté | 60 s |

Le mode NORMAL essaie d'abord Termux puis retombe sur le shell intégré. L'accès aux répertoires privés d'autres applications ou la modification des paramètres système nécessite Shizuku ou Root.

## Données locales et stockage

AzCode n'utilise ni SQLite, ni Room, ni base de données serveur. Sa couche de données repose sur des `SharedPreferences` privées à l'application et des fichiers JSON :

| Chemin | Contenu |
| --- | --- |
| `SharedPreferences` | Fournisseurs de modèles, curseur de session, compétences, mémoire de commandes, configuration GitHub / GenOffice, mode vocal et de privilège |
| `files/sessions/<id>.json` | Historique de chat et contexte d'agent d'une session |
| `files/memory.json` | Entrées de mémoire à long terme |
| `files/logs/YYYY-MM-DD.log` | Journaux rotatifs quotidiens, jusqu'à 10 fichiers, 512 Ko par fichier |
| `files/genoffice/` | Documents Office générés |
| `cache/az_images/`, `cache/captures/` | Cache d'images et fichiers photo temporaires |

Les sessions sont écrites via fichier temporaire puis renommage. Le contexte de l'agent conserve au plus 40 messages, les plus anciens étant résumés. La désinstallation supprime toutes les données locales.

## Pont de capacités et CLI Listen

Le pont écoute uniquement sur `127.0.0.1:8848`, n'est pas joignable depuis Internet et n'a pas d'authentification. L'accès distant nécessite `adb forward`.

| Méthode | Chemin | Description |
| --- | --- | --- |
| GET | `/health` | État de l'accessibilité, Shizuku, Root et du mode de privilège |
| GET | `/screen` | Nœuds visibles de l'écran, jusqu'à 200 |
| POST | `/tap` | `{"x":123,"y":456}` ou `{"text":"OK"}` |
| POST | `/swipe` | `{"x1":..,"y1":..,"x2":..,"y2":..,"duration":300}` |
| POST | `/global` | `{"action":"back"}`, prend en charge back / home / recents / notifications |
| POST | `/shell` | `{"cmd":"pm list packages"}`, exécuté selon le mode de privilège courant |
| POST | `/notify` | `{"title":"..","body":".."}` |
| POST | `/agent` | `{"task":"..","session":"id optionnel"}`, renvoie `answer` et `events` |

Avec un téléphone connecté en adb :

```bash
curl -fsSL https://raw.githubusercontent.com/Azapp888/AzCode/main/tools/install.sh | bash
listen run "Ouvrir les paramètres et trouver la version d'Android"
listen screen
listen tap --text "OK"
```

## Confidentialité et sécurité

- Les clés API des modèles, jetons GitHub, jetons GenOffice et identifiants Xunfei sont stockés uniquement dans le stockage privé de l'application ; l'application ne lit pas les variables d'environnement.
- Les instantanés de personnalisation locale sont générés sur l'appareil et jamais injectés dans les conversations cloud.
- Le pont n'écoute qu'en boucle locale ; l'accès distant repose sur le transfert adb.
- La reconnaissance Xunfei en ligne envoie l'audio à la plateforme ouverte Xunfei ; la reconnaissance système intégrée privilégie le traitement hors ligne sur l'appareil.
- Shizuku et Root peuvent exécuter des commandes système et une erreur peut endommager l'appareil ou les données. À utiliser uniquement sur des appareils que vous possédez ou êtes explicitement autorisé à manipuler.

## Limites connues

- La lecture d'écran et le contrôle de l'interface dépendent du service d'accessibilité, que certains fabricants restreignent en arrière-plan.
- L'accès shell système dépend de Shizuku ou Root ; le mode NORMAL est limité par le bac à sable de l'application.
- Les entrées audio et image dépendent des capacités multimodales du modèle choisi.
- La génération d'images dépend des API des fournisseurs, qui diffèrent par tailles et quantités prises en charge.
- Pas de synchronisation cloud ; les données disparaissent avec l'application.

## Liens

- Documentation complète : `README.md` à la racine
- Présentation produit : `PRODUCT.md`
- Système de design : `DESIGN.md`
- Outils CLI : `tools/listen` et `tools/install.sh`

## Licence

MIT. Les modules d'accessibilité et d'exécution Shizuku sont adaptés de [Soodok/Deepseek-Harness-Local-Android](https://github.com/Soodok/Deepseek-Harness-Local-Android) (MIT). L'API Shizuku provient de [RikkaApps/Shizuku-API](https://github.com/RikkaApps/Shizuku-API).
