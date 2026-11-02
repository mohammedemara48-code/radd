# ردّ — Android (native)

Phase 1: design system, all five screens, real permission flow, sample-data previews. The scan engine comes in phase 2 (see `../docs/ANDROID_DESIGN.md`).

```bash
# previews → ../previews/*.png
gradle -Ppreviews=true :app:testDebugUnitTest
# unit tests
gradle :app:testDebugUnitTest
```
