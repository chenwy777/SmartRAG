# Chat Reconnect Smoke Test

This repo now includes a reproducible reconnect test script:

```bash
node scripts/chat-reconnect-smoke-test.mjs
```

Default behavior:

- logs in with `admin / SmartRAG2026!`
- opens a first WebSocket connection
- sends a long prompt that should stream for a while
- closes the first socket after a few chunks to simulate user disconnect
- checks `/api/v1/chat/active-generation`
- checks `/api/v1/chat/generation/{generationId}`
- opens a second WebSocket connection with the same user
- verifies that streaming continues on the new connection
- verifies the final generation snapshot becomes `COMPLETED`

The default prompt is intentionally moderate so the script is less likely to hit local token-balance limits while still producing enough chunks for reconnect verification.

The script exits with code `1` if any of these checks fail.

## Common usage

Local backend on `8081`:

```bash
node scripts/chat-reconnect-smoke-test.mjs
```

Custom backend:

```bash
node scripts/chat-reconnect-smoke-test.mjs \
  --api-base http://127.0.0.1:18081/api/v1 \
  --ws-base ws://127.0.0.1:18081/chat
```

Custom credentials:

```bash
node scripts/chat-reconnect-smoke-test.mjs \
  --username admin \
  --password SmartRAG2026!
```

Useful options:

- `--disconnect-after-chunks 10`
- `--disconnect-delay-ms 50`
- `--active-check-delay-ms 800`
- `--reconnect-delay-ms 1200`
- `--timeout-ms 30000`
- `--prompt "你的自定义长问题"`

If the script reports a quota or balance error, try a shorter `--prompt`.

## Expected success signals

The output should include these checkpoints:

- `login-ok`
- `ws1-start`
- `ws1-closed`
- `active-after-disconnect`
- `snapshot-before-reconnect`
- `ws2-completed`
- `summary`

If the script succeeds, the summary should show:

- `activeContentLength > ws1ContentLength`
- `ws2Chunks > 0`
- `finalStatus = COMPLETED`
