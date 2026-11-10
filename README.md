# My CloudStream Plugins

A small [CloudStream](https://github.com/recloudstream/cloudstream) plugin repository.

## Plugins

| Plugin | Description |
|---|---|
| **YouTube** | Search YouTube videos and play them in CloudStream. |
| **Katworld** | Search videos and play them in CloudStream it include the katmoviehd pikahd moviebaba and many more. |

## Install

1. Open CloudStream and go to **Settings → Extensions → Add repository**.
2. Enter any name, and paste this link as the URL:

```
https://raw.githubusercontent.com/Deathscythe0x1/Extenstions-test/master/repo.json
```

3. Open the repository and download **Download the Extenstion you want**
4. Close CloudStream completely and reopen it. Then use the **Search** tab and select Extenstion as a source.

**One-tap install on Android:** open <https://deathscythe0x1.github.io/Extenstions-test/> and tap **Add to CloudStream**.

## Notes

- YouTube search relies on free public [Piped](https://github.com/TeamPiped/Piped) servers. The plugin tries several of them in turn, but they sometimes go down, so search may fail for a while. Playback uses CloudStream's built-in YouTube extractor.
- This is a personal learning project. It is not affiliated with YouTube, Google, or CloudStream.
- Using YouTube through third-party tools may go against YouTube's terms of service, so use it at your own risk.

## Build it yourself

This repo is a fork of [recloudstream/TestPlugins](https://github.com/recloudstream/TestPlugins). Every push to `master` builds the plugins automatically with GitHub Actions and publishes them to the `builds` branch.

To build locally you need JDK 17 and the Android SDK, then run:

```
./gradlew YoutubeProvider:make
```

On Windows, use `.\gradlew.bat YoutubeProvider:make`.

## License

Everything from the original template is released into the public domain.
