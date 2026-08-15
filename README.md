# CJ2026

- `preview/` contains the early preview snapshot of the single-exhibition ChinaJoy planner.
- Root `app/` is the long-term Expo Planner. Its home screen lists projects; each project contains its own halls, booths, notes, plan order, and import/export data.

Build the long-term app:

```bash
gradle :app:assembleDebug
```

Build the early preview:

```bash
gradle -p preview :app:assembleDebug
```
