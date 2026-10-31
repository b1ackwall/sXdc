# Delivery safety

sXdc delivers into a folder that synthX also scans. synthX identifies a song by a hash of its
bytes, and a path belongs to one song, so sXdc must never let a scan see a file that is
incomplete, replaced or rewritten. Delivery lives in `StagedFileDelivery`; `SafFileMover` is the
SAF adapter around it.

## The issue #1 answers

- **Overwrite: never.** sXdc lists the folder, picks a free final name itself, and never deletes,
  replaces or opens a document that was already there.
- **Partial files: never under a final name.** Bytes go into a temporary document, the byte
  count is verified, and only then is it renamed to the final name.
- **Rename or rewrite after delivery: none.** Once the rename succeeds, sXdc does not touch the
  document again.
- **Tags: written only in app-private staging, before delivery.** yt-dlp's postprocessors
  (audio extraction, metadata, cover art) all finish in the staging folder before the copy starts.
  The embed-failure fallback also delivers a file that is already finished.
- **Duplicates: a new name, never a replacement.** If `Song.flac` exists (compared
  case-insensitively), the new file becomes `Song (1).flac`, then `Song (2).flac`, and so on. If
  the provider still adds its own suffix at rename time, sXdc accepts the name it reports.

## Delivery sequence

1. Choose a free final name from one listing of the folder.
2. Create the temporary document `.sxdc-<random uuid>.partial` as `application/octet-stream`.
3. Read back the name the provider gave it. If the name does not start with `.sxdc-`, or it ends
   in an audio extension, delete the document and fail.
4. Check that the document supports rename (`FLAG_SUPPORTS_RENAME`).
5. Copy the staged file and verify the byte count.
6. Rename the document to the final name, and read back the name and URI the provider reports.

Any failure after step 2 deletes the temporary document and logs whether the delete worked.

## Why the temporary name is safe

synthX's scanner keeps only names whose last extension is `mp3`, `flac`, `aac`, `m4a`, `wav` or
`ogg`. It ignores `.partial`. The temporary document uses `application/octet-stream` because a
provider may append an extension that matches the mime type. With `audio/flac`, the temporary
name could gain `.flac` and become visible to synthX. Step 3 catches any provider that alters the
name anyway.

## Providers without rename: fail closed

If the destination cannot rename documents, there is no safe way to deliver. sXdc deletes the
temporary document and fails the job with a message asking for a folder on device storage. It
never falls back to writing straight into a final name. The queue stops too, because every
remaining job would fail the same way.

## Leftovers after a crash or stop

If the process dies during a copy, the temporary document can stay behind. synthX ignores it
because of its extension. At the start of every queue run, before any download or move and inside
the queue lock, sXdc deletes the direct children of the destination whose names start with
`.sxdc-` and end with `.partial`. Nothing else is touched: no other files, and no subfolders.
