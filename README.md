# NUX READER (Android)

Offline manhwa reader for PDF chapters. No ads, and nothing goes through the internet.

## How it works

- **Home screen**: every manhwa shows as a cover. Tap a cover to open its chapter list. **Send** and **Receive** are at the top.
- **Tap a chapter** and choose: read from here to the end (for example from chapter 13 down), read only that chapter, tick it to read later with others, or select all chapters.
- **Ticking** works only inside a manhwa. Ticks are kept when you go back and open another manhwa, and the bar at the bottom of the chapter list reads everything ticked as one long scroll.
- **Continue reading**: the app remembers where you stopped.
- Tap the screen while reading to show the top bar (current chapter, "Chapters" to jump, "Close").

## Send and Receive (phone to phone)

Both phones need NUX READER, and must be on the same Wi-Fi, or one phone's hotspot must be on with the other phone connected to it. No internet or mobile data is used.

1. On the receiving phone: tap **Receive** and leave the screen open.
2. On the sending phone: tap **Send**, tick one or more manhwa, tap **Next**, then tap the other phone's name.
3. The receiving phone shows who is sending and how much. Tap **Accept** to collect it.

Received manhwa are saved inside the app and appear on the home screen. If the other phone doesn't show up, type the address shown on the receiving phone's screen.

## Folder layout

Tap **Folders** on the home screen to add as many folders as you like. They all show together.

- Add a folder that has PDFs directly inside: it is **one manhwa**, named after the folder. Subfolders such as "Season 1" stay part of it.
- Add a folder that only has subfolders: each subfolder is its **own manhwa**.

Example of the second kind:

```
Manhwa/
  Solo Leveling/
    cover.png          (optional: used as the icon)
    Chapter 1.pdf
    Chapter 2.pdf
  Tower of God/
    Chapter 1.pdf
```

If there is no cover image, the first page of chapter 1 is used as the icon.

You can also set a cover from inside the app: open a manhwa and tap **Change cover**, then pick any picture on your phone. **Reset** goes back to the default.

## Build the APK

1. Install **Android Studio** on a computer.
2. Open this `ManhwaReader` folder in it and wait for Gradle sync to finish (needs internet once).
3. Phone: Settings > About phone > tap Build number 7 times, then turn on USB debugging in Developer options. Plug the phone in and press Run.
   Or: Build > Generate Signed App Bundle / APK > APK > release, copy the APK to the phone and install it.
4. Open the app, tap **Pick folder**, choose your manhwa folder.

If Android Studio offers to update Gradle or library versions, accept.

## Build without a computer (GitHub)

1. Make a free GitHub account and create a new repository.
2. Upload the contents of this folder to it (keep the `.github` folder).
3. Open the repository's **Actions** tab, choose **Build APK**, tap **Run workflow**.
4. When it finishes, open the run and download **NUX-Reader-apk**. Unzip it and install `NUX-Reader.apk`.

The APK is an optimized release build signed with the fixed key in `app/nux.jks`, so each new build installs over the previous one (no uninstalling, and your folders stay). That key is for personal use only; do not publish the app with it.

## Known limits

- Password-protected or damaged PDFs are skipped, with a message.
- Extremely tall single-page PDFs are shown at reduced sharpness to avoid running out of memory.
- No pinch-to-zoom yet.
- Uses the default Android app icon.
