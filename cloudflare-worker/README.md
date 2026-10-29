# Pulse cover cleanup

The Worker deletes uploaded Discord cover art five hours after upload. It needs a scheduled trigger to run the cleanup.

After deploying `worker.js`, open the Worker in Cloudflare and go to **Settings > Triggers > Cron Triggers**. Add this schedule:

```
*/5 * * * *
```

This runs the cleanup every five minutes. A cover is deleted at five hours old, plus at most five minutes until the next scheduled run. Public R2 responses are cached for only five minutes, so deleted artwork stops being served shortly afterwards.

Keep the existing `COVERS` R2 binding and the `TURNSTILE_SECRET` and `SESSION_SECRET` Worker secrets.
