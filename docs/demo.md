# fee-service — Live demo

About 5 minutes. It shows that the app registers itself, charges a fee once and survives a `kill -9` in the middle of a
charge. [`demo/demo.sh`](../demo/demo.sh) runs the steps one by one: press Enter to go to the next one. It checks every
claim it shows and stops with a red ✘ if one doesn't hold. `DemoIT` runs the same script in `mvn verify` against a
Testcontainers Restate, so it can't drift from the app.

| # | Step | What the audience sees |
|---|------|------------------------|
| 1 | Start the app | `Restate endpoint listening on port 9080`, then `RestateRegistrar … registered as deployment dp_…` (or `already registered … with the current services`) |
| 2 | Submit `txn_001` (Mobile Top Up, 1000 USD) | `200 {… "fee":1.50, "rate":0.0015, "description":"Standard fee rate of 0.15%"}` and one `Fake wallet: charged fee-txn_001 1.50 USD` |
| 3 | Resubmit the same payload | The same body. The log still shows a single charge |
| 4 | Restart with `WALLET_FAKE_DELAY=20s`, submit `txn_002` (Merchant Payment, 250 EUR) | `Fake wallet: waiting PT20S before charging fee-txn_002 2.80 EUR`, then `kill -9`. Restate's view: `"status":"backing-off"`, `retry_count`, and the broken connection as `last_failure` |
| 5 | Restart the app | Restate retries on the new process: `GET` shows `COMPLETED`, one `Fake wallet: charged fee-txn_002 2.80 EUR` across all three processes, and a resubmission returns `fee: 2.80` |
| 6 | The journal | One `validate`, one `calculate` and one `charge` run in the workflow's journal, in the terminal and in the Restate UI |

## Before the demo

- **Restate 1.8** is running and has never seen `txn_001`, so start it fresh. On a Restate that already knows the id, the
  script stops at step 1 and asks for `DEMO_RUN=<suffix>`, which turns the ids into `txn_001-<suffix>` and `txn_002-<suffix>`.
- **Ports:** the API port 8081 and the endpoint port 9080 are free. Nothing else may run the app.
- **Configuration:** the script passes the usual `RESTATE_*` variables on to the app. In the dev container they are already
  set (`dev-setup/README.md`). On the host, the defaults (`localhost`, `host.docker.internal:9080`) fit the `docker run` in the README.
- **Jar:** the script runs `service/target/fee-service-*.jar`, so `kill -9` hits the app itself and not a Maven process. It builds
  the jar if it's missing. After a code change, run `mvn -DskipTests package` again.
- **Browser:** open the Restate UI at `<admin URL>/ui/`, e.g. `http://localhost:9070/ui/`. Use the admin port as it is
  published on the machine with the browser.
- **Rehearse:** run `DEMO_RUN=rehearsal1 demo/demo.sh --no-pause`. It takes about 30 seconds. The app logs go to `service/target/demo/`.

## Running it

```bash
demo/demo.sh                 # step by step; Enter advances
demo/demo.sh --help          # the settings: DEMO_API_PORT, DEMO_SLOW_WALLET, DEMO_RUN, …
```

Talking points per step:

1. **Self-registration.** No manual `restate deployments register`. The `RestateRegistrar` asks the admin API whether
   Restate knows this endpoint with its current services, and registers it (or re-registers it with `force`) only if needed
   (D15, D31). If Restate is down, the app still starts with a WARN and keeps retrying.
2. **One request, one workflow.** The controller only hands the request over. Validation, the claim on
   `TransactionFeeRecord/txn_001`, the calculation, the charge and the record are all journaled steps of `FeeWorkflow`.
3. **Idempotency.** The workflow key is `txn_001:<payload hash>`. The same payload is the same workflow, and Restate
   answers `PREVIOUSLY_ACCEPTED`, so the gateway attaches to the first run's result. The wallet isn't called again. A
   *different* payload for `txn_001` would get a 409 from the claim.
4. **The crash.** VALIDATE, CLAIM and CALCULATE are already in Restate's journal. The app dies inside the CHARGE
   `ctx.run`, so that step has no result yet. The caller loses its connection, and resending the same payload is safe.
   Restate shows the invocation as `backing-off`. The script kills the app **automatically** as soon as the wallet starts
   waiting, because the fake wallet keeps its charges in memory. If the old process had finished its charge, the new one
   wouldn't know about it. A real wallet dedupes by the key `fee-txn_002`.
5. **Recovery.** Restate retries the invocation on the new process (the same URL, so the same deployment). The journaled
   steps are replayed, not executed again. CHARGE runs once more, because it never completed, and RECORD runs after it.
   Across all three processes the wallet was charged once. If the kill had come right after CHARGE was journaled, the
   retry would replay the charge and not call the wallet at all (`CrashRecoveryIT` covers every step boundary).
6. **The journal.** In the UI: **Invocations** → filter `FeeWorkflow` → the invocation whose key starts with `txn_002:` →
   its journal. Point at the retry, which ended the first attempt with the broken connection from the kill, and at the
   entries: `validate`, the `claim` call, `calculate`, `charge`, the `complete` call, the output. Each appears once. The terminal shows the same data through Restate's SQL introspection (`POST /query` on `sys_journal`).

## Without the script

The same steps by hand, in two terminals (use `kill -9` on the `java` process, not on `mvn spring-boot:run`):

```bash
# terminal 1
java -jar service/target/fee-service-0.0.1-SNAPSHOT.jar                  # 1. watch the RestateRegistrar line
# terminal 2
BODY='{"transaction_id":"txn_001","amount":1000,"asset":"USD","asset_type":"FIAT","type":"Mobile Top Up","state":"SETTLED - PENDING FEE","created_at":"2023-08-30 15:42:17.610059"}'
curl -s -X POST localhost:8081/transaction/fee -H 'Content-Type: application/json' -d "$BODY"     # 2.
curl -s -X POST localhost:8081/transaction/fee -H 'Content-Type: application/json' -d "$BODY"     # 3. same body
# terminal 1: Ctrl-C, then
WALLET_FAKE_DELAY=20s java -jar service/target/fee-service-0.0.1-SNAPSHOT.jar   # 4.
# terminal 2: submit txn_002 (same body with "transaction_id":"txn_002"), and as soon as terminal 1 logs
# "Fake wallet: waiting": kill -9 $(pgrep -f 'fee-service-.*\.jar')
# terminal 1
java -jar service/target/fee-service-0.0.1-SNAPSHOT.jar                  # 5. the charge of fee-txn_002 appears
curl -s localhost:8081/transaction/fee/txn_002                           #    COMPLETED
grep -h 'Fake wallet: charged' <the logs>                                #    one line per transaction
```

## If something goes wrong

| Symptom | Fix |
|---------|-----|
| `Restate's admin API doesn't answer` | Start Restate, or set `RESTATE_ADMIN_URL` |
| `something already listens on http://localhost:8081` | Stop the other app, or set `DEMO_API_PORT` |
| `Restate already has a record for txn_001` | Use a fresh Restate, or `DEMO_RUN=<suffix>` |
| `timed out … waiting until the app registered its Restate endpoint` | Restate can't reach `RESTATE_ADVERTISED_URL`, or the admin URL is wrong. See the app log the script prints |
| Step 5 waits a long time | Restate's retry backoff grows (0.5 s, doubled, at most 1 min apart, D33). The longer the app stays down, the longer the wait after it is back |
