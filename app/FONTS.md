# Bundled typefaces

The UI design specifies both of these; they ship inside the APK.

| Font | Licence | Source |
|---|---|---|
| Vazirmatn (Regular/Bold/Black) | SIL Open Font License 1.1 | https://github.com/rastikerdar/vazirmatn |
| JetBrains Mono (Regular/Medium) | SIL Open Font License 1.1 | https://github.com/JetBrains/JetBrainsMono |

The OFL permits bundling in an application. Its text must ship with the app,
which `app/src/main/assets/open_source_licenses.html` covers.

Files live in `app/src/main/res/font/`. Nothing but `.ttf`/`.otf`/`.xml` may go
in that directory — the resource merger rejects anything else.
