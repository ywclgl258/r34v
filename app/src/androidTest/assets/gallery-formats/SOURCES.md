# Generated media fixtures

The neutral still images are local generated 2 x 2 gray-color samples, not downloaded content.
`neutral.avif`, `neutral.gif`, and `neutral.webp` were encoded with the locally installed FFmpeg 8.1.1.
`neutral.heic` uses the same generated ISO-BMFF item structure with a HEVC-compatible brand for
container admission tests. Export tests first ask the device decoder for support; an Android build
without that optional codec does not create an imported book from fake pixel content.
