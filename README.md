# ReTranslate

Android novel translation app. Private repository.

Cloud builds run in GitHub Actions: the full unit-test gate first, then a
release APK as a downloadable artifact. Binary assets are stored as
`.b64` text files for transport; the workflow restores them before
building. The real signing key is never in this repository and never
enters GitHub — CI signs with a throwaway key and the downloaded APK is
re-signed with the real key afterwards.
