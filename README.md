# PhotoConverter

Android app (arm64-v8a, minSdk 34) that analyzes a batch of photos, sorts them into
JPEG vs HEIF, converts HEIF→JPEG and JPEG→HEIF into kept temporary files, and reports
per-phase timing, error, and warning statistics. Motion-photo videos, EXIF (including
GPS), and Ultra HDR gainmaps are preserved where platform APIs allow.

## UI flow

1. **Pick photos** — system photo picker, multi-select (no permission prompt needed;
   the picker grants per-item access).
2. Analysis phase with progress bar → count, total time, avg per image, plus
   per-image details (container/codec/resolution/bit depth/color space/gainmap/motion).
3. Sort summary: JPEG vs HEIF vs other counts.
4. HEIF→JPEG conversion with progress, then stats; error/warning counts are tappable
   and open the issue list.
5. JPEG→HEIF conversion, same reporting.
6. Tapping a conversion card opens a scrollable two-column comparison gallery
   (original left, converted right). The window runs in `COLOR_MODE_HDR` and images
   are decoded to hardware bitmaps, so Ultra HDR gainmaps get the real HDR boost and
   wide-gamut (P3) images render correctly. Motion photos play their embedded video
   straight from the container (fd + offset/length), stop on the photo, wait 5 s, and
   loop — on both sides independently.
7. **Clean up temp files** removes all outputs.

Outputs land in `/storage/emulated/0/Android/data/net.shehane.photoconverter/files/converted/`
(`toJpeg/`, `toHeif/`), report in `.../files/report.json`.

## CLI (adb)

```sh
# one-time grants (CLI enumerates MediaStore; picker path doesn't need them)
adb shell pm grant net.shehane.photoconverter android.permission.READ_MEDIA_IMAGES
adb shell pm grant net.shehane.photoconverter android.permission.ACCESS_MEDIA_LOCATION

# run the full pipeline over the 12 most recent gallery images
adb shell am start-foreground-service -n net.shehane.photoconverter/.cli.CliService -e cmd run --ei count 12

# watch progress / results
adb logcat -s HEIFConv

# fetch outputs + report
adb pull /sdcard/Android/data/net.shehane.photoconverter/files/converted .
adb pull /sdcard/Android/data/net.shehane.photoconverter/files/report.json .

# remove temp files
adb shell am start-foreground-service -n net.shehane.photoconverter/.cli.CliService -e cmd cleanup
```

## Validation

```sh
# EXIF/GPS/motion metadata
exiftool -Model -DateTimeOriginal -GPSLatitude -MotionPhoto -DirectoryItemSemantic -DirectoryItemLength <file>

# decode integrity
magick identify -regard-warnings <file>

# extract the appended motion video (last DirectoryItemLength bytes) and probe it
exiftool extracted.mp4
```

## Metadata preservation design

| Feature | HEIF→JPEG | JPEG→HEIF |
| --- | --- | --- |
| EXIF (incl. GPS) | raw TIFF lifted from HEIF `Exif` item, orientation normalized via carrier JPEG, spliced as APP1 | same TIFF flow, injected as an `Exif` item into the HEIC `meta` box (custom ISOBMFF editor in `Isobmff.kt`) |
| Ultra HDR gainmap | rides `ImageDecoder` → `Bitmap.compress`, which writes JPEG_R when the bitmap has a gainmap (validated with Galaxy S26 Ultra gainmap HEICs: output MPF secondary + XMP-hdrgm match the source) | **not possible with public Android APIs** — reported as a per-file warning; output is the SDR base image |
| Motion photo video | trailing MP4 re-appended after the JPEG, Google `Container:Directory` XMP rebuilt in APP1 | trailing MP4 wrapped in a Samsung-style `mpvd` box (keeps the ISOBMFF stream valid), XMP injected as a `mime` item |
| Color space | carried by the decoded bitmap (ICC written by encoders, e.g. Display P3) | same |

Notes:
- Decodes are software-allocated and orientation-normalized, so EXIF orientation is reset to 1.
- The injected HEIC metadata validates cleanly with exiftool (which can even rewrite the
  files in place); androidx `ExifInterface` writes IFD0 entries slightly out of canonical
  order, which exiftool flags as a fixable minor warning.
- `ACCESS_MEDIA_LOCATION` + `MediaStore.setRequireOriginal` is used so GPS EXIF survives;
  photo-picker URIs may still be location-redacted by the OS.

## Build

Gradle 8.9 wrapper, AGP 8.7.3, Kotlin 2.2.0, compose-bom 2024.12.01.

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/PhotoConverter-debug.apk
```
