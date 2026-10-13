# Play Console store assets

Everything the **Main store listing** page asks for, ready to upload.
Raw device captures stay in [`../screenshots`](../screenshots); this folder
holds the Play-ready derivatives and the scripts that produce them.

| Listing field     | File                                              |
| ----------------- | ------------------------------------------------- |
| App icon          | `app-icon-512.png`                                |
| Feature graphic   | `feature-graphic-1024x500.png`                    |
| Phone screenshots | `screenshots/phone/` (6)                          |
| 7-inch tablet     | `screenshots/tablet-7in/` (6)                     |
| 10-inch tablet    | `screenshots/tablet-10in/` (6)                    |
            |

## App icon — `app-icon-512.png`

| Play requirement           | This file                                    |
| -------------------------- | -------------------------------------------- |
| PNG or JPEG                | PNG (24-bit RGB, sRGB)                       |
| 512 px × 512 px            | 512 × 512                                    |
| Up to 1 MB                 | ~346 KB                                      |
| No transparency            | No alpha channel                             |
| Square, no rounded corners | Full-bleed square; Play applies its own mask |

Same character as the launcher icon and the iOS app icon, framed at the 1.25×
scale `AppIcon.icon/icon.json` uses on iOS. Corners carry only the pastel
background, so Play's rounded-corner mask cuts nothing.

```sh
swiftc -O make-icon.swift -o /tmp/make-icon
/tmp/make-icon \
  ../../../Breakthrough-Swift6/Breakthrough/AppIcon.icon/Assets/character_1024x1024.png \
  app-icon-512.png 1.25 512
```

Arguments: `<src.png> <out.png> <zoom> <size>`. The script draws into an opaque
sRGB context and prints the source's minimum alpha, so a transparent source is
caught before upload.

## Feature graphic — `feature-graphic-1024x500.png`

1024 × 500, 24-bit PNG, ~432 KB (limit 15 MB). App icon, wordmark and tagline on
the left; the chat and voice-call screenshots on the right. All text and both
devices sit inside the middle 80% of the canvas, so nothing important is lost
where Play crops the graphic. Type is Roboto (Apache 2.0, from Android Studio),
matching the app's UI.

```sh
swiftc -O make-feature-graphic.swift -o /tmp/make-feature
/tmp/make-feature app-icon-512.png \
  "../screenshots/Phone/Screenshot_20260806_165739.png" \
  "../screenshots/Phone/Screenshot_20260806_165749.png" \
  feature-graphic-1024x500.png
```

Arguments: `<icon> <chat screenshot> <call screenshot> <out>`.

## Screenshots — `screenshots/`

Play requires 24-bit PNG (no alpha), each side 320–3840 px, and **the long side
at most twice the short side**. Raw Android captures fail both: they are 32-bit,
and phone frames are 1080 × 2400 (2.22:1).

`normalize-screenshots.swift` drops the alpha channel and pads any over-tall
frame to exactly 2:1 by replicating its edge pixels — invisible here because the
app's background is flat to the screen edge. Nothing is scaled or cropped.

| Device      | Raw         | Uploaded    |
| ----------- | ----------- | ----------- |
| Phone       | 1080 × 2400 | 1200 × 2400 |
| 7-inch      | 1200 × 1920 | unchanged   |
| 10-inch     | 1600 × 2560 | unchanged   |

```sh
swiftc -O normalize-screenshots.swift -o /tmp/normalize
/tmp/normalize screenshots/phone       ../screenshots/Phone/*.png
/tmp/normalize screenshots/tablet-7in  "../screenshots/7 Inch"/*.png
/tmp/normalize screenshots/tablet-10in "../screenshots/10 Inch"/*.png
```

Files are numbered `01…06`; Play shows them in upload order, so rename to
reorder.
