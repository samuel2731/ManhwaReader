# NUX READER

An offline manhwa reader for Android. It reads the PDF chapters already saved on your phone, so you can go through many chapters in one long scroll without leaving the app.

No ads, no accounts. Everything stays on your phone.

## Features

- **Library of covers**: every manhwa shows as a cover on the home screen. Tap one to open its chapters.
- **Continuous reading**: read many chapters as one long scroll. No going back out to find the next chapter.
- **Read from here**: tap a chapter, then choose to read from it to the end (for example from chapter 13 onward), read only that chapter, tick it for later, or select all.
- **Pick several manhwa**: tick chapters in one manhwa, go back, tick chapters in another, then read them all together.
- **Remembers your place**: Continue reading picks up where you stopped.
- **Covers**: uses a `cover.png` / `cover.jpg` in the manhwa's folder, or the first page of chapter 1, or a picture you choose with **Change cover**.
- **Send and Receive**: share manhwa with a friend's phone over Wi-Fi or hotspot. No internet needed.
- **Light and Dark theme**: switch in the menu (⋮) on the home screen.
- **About** page in the same menu.

## Using the app

### Adding your manhwa

Tap **Pick folder** (later **Folders**, then **Add folder**) and choose a folder. Add as many folders as you like. They all show together.

- A folder with PDFs directly inside is **one manhwa**, named after the folder. Subfolders such as "Season 1" stay part of it.
- A folder that only has subfolders: each subfolder is its **own manhwa**.

```
Manhwa/
  Solo Leveling/
    cover.png          (optional)
    Chapter 1.pdf
    Chapter 2.pdf
  Tower of God/
    Chapter 1.pdf
```

Chapters are sorted naturally, so Chapter 2 comes before Chapter 10.

**Removing a manhwa:** the app only lets you add folders. To remove one, delete the folder in your Files app (or a tool like ZArchiver). It disappears from the app by itself the next time it refreshes.

### Reading

1. Tap a manhwa cover.
2. Tap a chapter and choose what to do, or tick chapters with the boxes and press **Read selected** at the bottom.
3. While reading, tap the screen to show the top bar: current chapter, **Chapters** to jump, **Close**.

### Send and Receive

Both phones need NUX READER, and must be on the same Wi-Fi, or one phone's hotspot must be on with the other phone connected to it. No internet or mobile data is used.

1. **Receiving phone**: tap **Receive** and leave the screen open.
2. **Sending phone**: tap **Send**, tick one or more manhwa, tap **Next**, then tap the other phone's name.
3. **Receiving phone**: a box shows who is sending and how much. Tap **Accept** to collect it.

If the other phone doesn't show up, type the address shown on the receiving phone's screen.

Received manhwa are stored inside the app and appear on the home screen.

## Getting the app

This project builds on GitHub, so no computer is needed.

1. Open the **Actions** tab of this repository and open the latest successful **Build APK** run.
2. Download **NUX-Reader-apk** from the **Artifacts** section and unzip it.
3. Open `NUX-Reader.apk` on your phone and allow "install unknown apps" when asked.

To run a new build yourself: **Actions**, **Build APK**, **Run workflow**. Every push also starts a build.

## Building from source

- **GitHub**: as above. The workflow is in `.github/workflows/build.yml` (Java 17, Gradle 8.9, `gradle assembleRelease`).
- **Android Studio**: open this folder, let Gradle sync, then Run, or Build > Build APK(s).

## Technical notes

- Kotlin and Jetpack Compose (Material 3), minimum Android 8.0 (API 26).
- Pages are drawn with Android's built-in `PdfRenderer`.
- Folders are opened through Android's folder picker, so no storage permission is needed.
- Phone-to-phone sharing uses Android's local network discovery and a plain connection on port 45454. Only PDF and picture files are accepted, and only after the receiver taps Accept.
- Release builds are signed with the fixed key in `app/nux.jks`, so each new APK installs over the previous one. That key is for personal use. Make a private key of your own before publishing the app anywhere.

## Known limits

- No pinch-to-zoom yet.
- Password-protected or damaged PDFs are skipped, with a message.
- Very tall single-page PDFs are shown at reduced sharpness to avoid running out of memory.
- Manhwa received from a friend are stored in the app's own storage, which some file managers cannot open.
