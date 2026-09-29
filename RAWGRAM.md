# rawGram

A Telegram Android fork for developers: it shows what the official UI hides.

## Build setup

API credentials are **not** stored in the repository. Put them in `local.properties`
(git-ignored) in the project root:

```
sdk.dir=/path/to/Android/sdk
RAWGRAM_API_ID=12345678
RAWGRAM_API_HASH=0123456789abcdef0123456789abcdef
```

or export `RAWGRAM_API_ID` / `RAWGRAM_API_HASH` as environment variables (CI).
Without them the app builds, but it cannot log in.

Package: `dev.rawgram.app` (`.beta` for debug, `.web` for standalone).

## Features

- **Inline results**: long-press any inline bot result (article, contact, venue…)
  to open the raw viewer:
  - rendered preview of the message the result would send (text, entities,
    inline keyboard, photo/document);
  - `Fields` (flattened `path = value`) and `JSON` views, copy to clipboard;
  - `send_message`, full `messages.botResults` response, results hidden by the
    client (`Hidden`), the `messages.getInlineBotResults` request;
  - `Re-request` sends the query again bypassing the local cache;
  - `Send` sends the result as usual.
- **Stickers / GIFs**: the long-press preview menu has a `Raw` item that shows the
  `Document`, the `InputStickerSet` and the cached `messages.stickerSet`.

Code lives in `org.telegram.rawgram`; hooks in upstream files are marked `rawGram`.
