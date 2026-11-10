# Manhwa Reader (Android)

Offline manhwa reader for PDF chapters. No internet needed, no ads, nothing leaves your phone.

## How it works

- **Home screen**: every manhwa in your chosen folder shows as a cover. Tap the tick on a cover to select the whole manhwa, or tap the cover to open its chapter list.
- **Chapter list**: tick the chapters you want, then go Back and open another manhwa. Your ticks are kept.
- **Read selected**: all ticked chapters from all manhwa open as one long scroll. No leaving the app to find the next chapter. Chapters are in order (Chapter 2 before Chapter 10).
- **Continue reading**: the app remembers where you stopped.
- Tap the screen while reading to show the top bar (current chapter, "Chapters" to jump, "Close").

## Folder layout

Pick one main folder. Put each manhwa in its own subfolder:

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

## Build the APK

1. Install **Android Studio** on a computer.
2. Open this `ManhwaReader` folder in it and wait for Gradle sync to finish (needs internet once).
3. Phone: Settings > About phone > tap Build number 7 times, then turn on USB debugging in Developer options. Plug the phone in and press Run.
   Or: Build > Build APK(s), copy `app-debug.apk` to the phone and install it.
4. Open the app, tap **Pick folder**, choose your manhwa folder.

If Android Studio offers to update Gradle or library versions, accept.

## Build without a computer (GitHub)

1. Make a free GitHub account and create a new repository.
2. Upload the contents of this folder to it (keep the `.github` folder).
3. Open the repository's **Actions** tab, choose **Build APK**, tap **Run workflow**.
4. When it finishes, open the run and download **ManhwaReader-apk**. Unzip it and install `app-debug.apk`.

## Known limits

- Password-protected or damaged PDFs are skipped, with a message.
- Extremely tall single-page PDFs are shown at reduced sharpness to avoid running out of memory.
- No pinch-to-zoom yet.
- Uses the default Android app icon.
